package com.valstats.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Metadata(
        String map,
        @JsonProperty("game_length") int gameLength,
        @JsonProperty("game_start") long gameStart,
        String mode,
        @JsonProperty("matchid") String matchId,
        @JsonProperty("season_id") String seasonId,
        @JsonProperty("rounds_played") int roundsPlayed
) {
}
