package com.valstats.model.queue;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record RefreshJob(
        String puuid,
        String region,
        String name,
        String tag,
        long requestedAt,
        String kind,
        int page,
        int pagesPerJob,
        String targetSeasonId,
        boolean targetSeen
) {
    public RefreshJob(String puuid, String region, String name, String tag, long requestedAt) {
        this(puuid, region, name, tag, requestedAt, "RECENT", 1, 2, "", false);
    }

    public static RefreshJob matches(String puuid, String region, String name, String tag) {
        return recent(puuid, region, name, tag);
    }

    public static RefreshJob recent(String puuid, String region, String name, String tag) {
        return new RefreshJob(puuid, region, name, tag, Instant.now().getEpochSecond(), "RECENT", 1, 2, "", false);
    }

    public static RefreshJob history(String puuid, String region, String name, String tag, int page) {
        return new RefreshJob(puuid, region, name, tag, Instant.now().getEpochSecond(), "HISTORY", page, 1, "", false);
    }

    public static RefreshJob act(String puuid, String region, String name, String tag, String seasonId) {
        return new RefreshJob(puuid, region, name, tag, Instant.now().getEpochSecond(), "ACT", 1, 1, seasonId, false);
    }

    public RefreshJob nextPage(int nextPage) {
        return new RefreshJob(puuid, region, name, tag, requestedAt, kind, nextPage, pagesPerJob, targetSeasonId, targetSeen);
    }

    public RefreshJob withProgress(int nextPage, boolean hasSeenTarget) {
        return new RefreshJob(puuid, region, name, tag, requestedAt, kind, nextPage, pagesPerJob,
                targetSeasonId, hasSeenTarget);
    }
}
