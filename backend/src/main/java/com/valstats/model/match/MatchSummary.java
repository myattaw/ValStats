package com.valstats.model.match;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record MatchSummary(
        String id,
        String map,
        long dateRaw,
        String agent,
        String kda,
        int acs,
        String result,
        int score,
        int enemyScore,

        String rank,
        int rrChange,
        int rankingInTier
) {
}