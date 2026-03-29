package com.valstats;

import com.valstats.service.match.MatchDataService;
import io.micronaut.runtime.Micronaut;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class LocalRunner {

    @Inject
    private MatchDataService matchDataService;

    public void run() {
        matchDataService.syncStoredMatches(
                "e70e9f85-d38b-5253-989c-89d16d2c4cc9",
                "na",
                "FertileHippo374",
                "374",
                50,
                true
        );
    }
}