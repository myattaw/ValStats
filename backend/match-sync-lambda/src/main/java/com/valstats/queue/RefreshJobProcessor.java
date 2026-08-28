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
        // RECENT completion only means the foreground slice is ready. Always
        // hand it off to the complete-history fetch, even when Henrik returned
        // fewer recent records than the requested page size.
        if (result.complete() && !"RECENT".equalsIgnoreCase(job.kind())) return;

        RefreshJob continuation;
        if ("RECENT".equalsIgnoreCase(job.kind())) {
            // HISTORY uses a different (large) page size, so it starts at its own page 1.
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
