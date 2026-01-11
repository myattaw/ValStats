package com.valstats.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Metadata(String map, String mode, String matchid, String season_id, int rounds_played) {
}
