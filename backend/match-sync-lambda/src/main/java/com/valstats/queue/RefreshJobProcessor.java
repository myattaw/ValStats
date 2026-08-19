package com.valstats.queue;

import com.valstats.model.queue.RefreshJob;
import com.valstats.service.match.MatchDataService;
import jakarta.inject.Singleton;

@Singleton
public class RefreshJobProcessor {
    private final MatchDataService matchDataService;

    public RefreshJobProcessor(MatchDataService matchDataService) {
        this.matchDataService = matchDataService;
    }

    public void process(RefreshJob job) {
        if (job == null || isBlank(job.puuid()) || isBlank(job.region())
                || isBlank(job.name()) || isBlank(job.tag())) {
            throw new IllegalArgumentException("Refresh job is missing required player fields");
        }
        matchDataService.refreshPlayerMatches(job.puuid(), job.region(), job.name(), job.tag());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
