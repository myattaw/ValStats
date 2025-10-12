package com.valstats.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record MatchResponse(int status, List<Match> data) {
}
