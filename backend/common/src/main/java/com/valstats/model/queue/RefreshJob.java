package com.valstats.model.queue;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record RefreshJob(
        String puuid,
        String region,
        String name,
        String tag,
        long requestedAt
) {
    public static RefreshJob matches(String puuid, String region, String name, String tag) {
        return new RefreshJob(puuid, region, name, tag, Instant.now().getEpochSecond());
    }
}
