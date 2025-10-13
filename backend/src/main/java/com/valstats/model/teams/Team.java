package com.valstats.model.teams;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Team(
    boolean has_won,
    int rounds_won,
    int rounds_lost,
    Object roster // Use Object for now, or change to appropriate type if needed
) {
}
