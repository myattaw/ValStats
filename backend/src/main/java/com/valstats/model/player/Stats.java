package com.valstats.model.player;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Stats(int score, int kills, int deaths, int assists) {
}
