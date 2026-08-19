package com.valstats;

import com.valstats.service.match.MatchDataService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class LocalRunner {

    @Inject
    private MatchDataService matchDataService;

    public void run() {
        matchDataService.syncStoredMatches(
                "28b2d23e-a656-54a9-a003-61f2193fc72d",
                "na",
                "Rages",
                "alt",
                50,
                true
        );
    }
}