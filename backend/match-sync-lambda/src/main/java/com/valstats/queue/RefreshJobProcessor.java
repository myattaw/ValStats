package com.valstats.queue;

import com.valstats.model.queue.RefreshJob;
import com.valstats.service.match.MatchDataService;
import jakarta.inject.Singleton;

@Singleton
public class RefreshJobProcessor {

    private final MatchDataService matchDataService;
    private final BackfillQueuePublisher queuePublisher;

    public RefreshJobProcessor(MatchDataService matchDataService, BackfillQueuePublisher queuePublisher) {
        this.matchDataService = matchDataService;
        this.queuePublisher = queuePublisher;
    }

    public void process(RefreshJob job) {
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
        if (result.complete()) return;

        RefreshJob continuation;
        if ("RECENT".equalsIgnoreCase(job.kind())) {
            continuation = RefreshJob.history(job.puuid(), job.region(), job.name(), job.tag(), result.nextPage());
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
