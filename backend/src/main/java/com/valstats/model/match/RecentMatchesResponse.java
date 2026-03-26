package com.valstats.model.match;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record RecentMatchesResponse(
        int status,
        String name,
        String tag,
        List<Match> data
) {
}

