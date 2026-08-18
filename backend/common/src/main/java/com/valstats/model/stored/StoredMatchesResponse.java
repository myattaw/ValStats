package com.valstats.model.stored;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Introspected
@Serdeable
public record StoredMatchesResponse(
        int status,
        String name,
        String tag,
        Results results,
        List<StoredMatch> data
) {

    @Introspected
    @Serdeable
    public record Results(
            int total,
            int returned,
            int before,
            int after
    ) {
    }

    @Introspected
    @Serdeable
    public record StoredMatch(
            Meta meta,
            Stats stats,
            Teams teams,
            List<Player> players
    ) {
    }

    @Introspected
    @Serdeable
    public record Player(
            String puuid,
            String name,
            String tag,
            String team
    ) {
    }

    @Introspected
    @Serdeable
    public record Meta(
            String id,
            MapInfo map,
            String version,
            String mode,
            @JsonProperty("started_at") String startedAt,
            Season season,
            String region,
            String cluster
    ) {
    }

    @Introspected
    @Serdeable
    public record MapInfo(
            String id,
            String name
    ) {
    }

    @Introspected
    @Serdeable
    public record Season(
            String id,
            @JsonProperty("short") String shortName
    ) {
    }

    @Introspected
    @Serdeable
    public record Stats(
            String puuid,
            String name,
            String tag,
            String team,
            int level,
            Character character,
            int tier,
            int score,
            int kills,
            int deaths,
            int assists,
            Shots shots,
            Damage damage
    ) {
    }

    @Introspected
    @Serdeable
    public record Character(
            String id,
            String name
    ) {
    }

    @Introspected
    @Serdeable
    public record Shots(
            int head,
            int body,
            int leg
    ) {
    }

    @Introspected
    @Serdeable
    public record Damage(
            int dealt,
            int received
    ) {
    }

    @Introspected
    @Serdeable
    public record Teams(
            int red,
            int blue
    ) {
    }
}

