package com.valstats.service.player;

import jakarta.inject.Singleton;

@Singleton
public class DynamoPlayerNameRecorder implements PlayerNameRecorder {

    private final PlayerCacheService playerCacheService;

    public DynamoPlayerNameRecorder(PlayerCacheService playerCacheService) {
        this.playerCacheService = playerCacheService;
    }

    @Override
    public void record(String puuid, String name, String tag, long observedAt) {
        playerCacheService.recordPlayerName(puuid, name, tag, observedAt);
    }
}
