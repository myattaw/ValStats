package com.valstats.queue;

import com.valstats.model.queue.RefreshJob;
import com.valstats.client.HenrikApiRequestQueue;
import com.valstats.client.ValorantApiClient;
import com.valstats.service.match.MatchDataService;
import com.valstats.service.player.AccountProfileWriter;
import jakarta.inject.Singleton;

@Singleton
public class RefreshJobProcessor {

    private final MatchDataService matchDataService;
    private final BackfillQueuePublisher queuePublisher;
    private final NameHistoryJobProcessor nameHistoryProcessor;
    private final ValorantApiClient apiClient;
    private final HenrikApiRequestQueue requestQueue;
    private final AccountProfileWriter profileWriter;

    public RefreshJobProcessor(MatchDataService matchDataService, BackfillQueuePublisher queuePublisher,
                               NameHistoryJobProcessor nameHistoryProcessor, ValorantApiClient apiClient,
                               HenrikApiRequestQueue requestQueue, AccountProfileWriter profileWriter) {
        this.matchDataService = matchDataService;
        this.queuePublisher = queuePublisher;
        this.nameHistoryProcessor = nameHistoryProcessor;
        this.apiClient = apiClient;
        this.requestQueue = requestQueue;
        this.profileWriter = profileWriter;
    }

    public void process(RefreshJob job) {
        if (job != null && "PROFILE".equalsIgnoreCase(job.kind())) {
            if (isBlank(job.puuid()) || isBlank(job.name()) || isBlank(job.tag())) {
                throw new IllegalArgumentException("Profile job is missing required player fields");
            }
            var response = requestQueue.execute("account for " + job.name() + "#" + job.tag(),
                    () -> apiClient.getAccount(job.name(), job.tag()));
            if (response != null && response.get("data") instanceof java.util.Map<?, ?> data) {
                profileWriter.store(data, job.puuid(), job.name(), job.tag(), job.region());
            }
            return;
        }
        if (job != null && "NAME_HISTORY".equalsIgnoreCase(job.kind())) {
            try {
                nameHistoryProcessor.process(job);
            } catch (RuntimeException failure) {
                nameHistoryProcessor.markFailed(job.puuid());
                throw failure;
            }
            return;
        }
        if (job == null || isBlank(job.puuid()) || isBlank(job.region())
                || isBlank(job.name()) || isBlank(job.tag())) {
            throw new IllegalArgumentException("Refresh job is missing required player fields");
        }
        MatchDataService.BackfillResult result;
        try {
            result = matchDataService.processBackfill(job);
        } catch (RuntimeException failure) {
            try {
                matchDataService.markBackfillFailed(job);
            } catch (RuntimeException stateFailure) {
                failure.addSuppressed(stateFailure);
            }
            throw failure;
        }
        // RECENT completion only means the foreground slice is ready. Always
        // hand it off to the complete-history fetch, even when Henrik returned
        // fewer recent records than the requested page size.
        if (result.complete() && !"RECENT".equalsIgnoreCase(job.kind())) return;

        RefreshJob continuation;
        if ("RECENT".equalsIgnoreCase(job.kind())) {
            // HISTORY has its own checkpoint sequence, so it starts at page 1.
            continuation = RefreshJob.history(job.puuid(), job.region(), job.name(), job.tag(), 1);
        } else {
            continuation = job.withProgress(result.nextPage(), result.targetSeen());
        }
        matchDataService.markBackfillQueued(continuation);
        try {
            queuePublisher.enqueue(continuation, "HISTORY".equalsIgnoreCase(continuation.kind()));
        } catch (RuntimeException failure) {
            try {
                matchDataService.markBackfillFailed(continuation);
            } catch (RuntimeException stateFailure) {
                failure.addSuppressed(stateFailure);
            }
            throw failure;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
