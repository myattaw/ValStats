package com.valstats.model;

import com.valstats.model.player.Players;
import com.valstats.model.teams.Teams;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Match(Metadata metadata, Players players, Teams teams) {
}
