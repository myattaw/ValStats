package com.valstats.model.assets;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Assets(Card card, Agent agent) {

    @Introspected
    @Serdeable.Deserializable
    @Serdeable.Serializable
    public record Card(String small) {}

    @Introspected
    @Serdeable.Deserializable
    @Serdeable.Serializable
    public record Agent(String small) {}

}

