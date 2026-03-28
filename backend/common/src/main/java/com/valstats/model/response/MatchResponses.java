package com.valstats.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

public final class MatchResponses {

    private MatchResponses() {
    }

    @Introspected
    @Serdeable
    public record MatchHistoryResponse(
            int status,
            boolean cached,
            List<MatchSummary> data,
            Cursor lastKey
    ) {
    }

    @Introspected
    @Serdeable
    public record MatchSummary(
            String id,
            String map,
            String mapId,
            String result,
            int score,
            @JsonProperty("enemy_score") int enemyScore,
            String kda,
            String agent,
            String agentIcon,
            int acs,
            String timestamp,
            @JsonProperty("date_raw") long dateRaw,
            String rank,
            @JsonProperty("rank_tier") int rankTier,
            @JsonProperty("ranking_in_tier") int rankingInTier,
            int rrChange,
            @JsonProperty("rounds_played") int roundsPlayed,
            long adr,
            Teams teams,
            String puuid,
            boolean hasDetails
    ) {
    }

    @Introspected
    @Serdeable
    public record Teams(
            TeamRound red,
            TeamRound blue
    ) {
    }

    @Introspected
    @Serdeable
    public record TeamRound(
            @JsonProperty("rounds_won") int roundsWon,
            @JsonProperty("has_won") Boolean hasWon
    ) {
    }

    @Introspected
    @Serdeable
    public record MatchDetailsResponse(
            int status,
            boolean cached,
            MatchDetails data
    ) {
    }

    @Introspected
    @Serdeable
    public record MatchDetails(
            MatchMetadata metadata,
            MatchPlayers players
    ) {
    }

    @Introspected
    @Serdeable
    public record MatchMetadata(
            String matchid,
            String map,
            @JsonProperty("game_start") long gameStart,
            @JsonProperty("rounds_played") int roundsPlayed
    ) {
    }

    @Introspected
    @Serdeable
    public record MatchPlayers(
            @JsonProperty("all_players") List<MatchPlayer> allPlayers
    ) {
    }

    @Introspected
    @Serdeable
    public record MatchPlayer(
            String puuid,
            String name,
            String team,
            String character,
            MatchPlayerStats stats,
            @JsonProperty("damage_made") int damageMade
    ) {
    }

    @Introspected
    @Serdeable
    public record MatchPlayerStats(
            int kills,
            int deaths,
            int assists,
            int score,
            int headshots,
            int bodyshots,
            int legshots
    ) {
    }

    @Introspected
    @Serdeable
    public record Cursor(
            @JsonProperty("PK") String pk,
            @JsonProperty("SK") String sk,
            @JsonProperty("GSI1PK") String gsi1Pk,
            @JsonProperty("GSI1SK") Long gsi1Sk
    ) {
    }
}

