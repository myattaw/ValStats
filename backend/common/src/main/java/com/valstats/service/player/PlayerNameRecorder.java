package com.valstats.service.player;

/**
 * Receives player identities observed while processing a match.
 * Implementations decide where and how the history is persisted.
 */
public interface PlayerNameRecorder {
    void record(String puuid, String name, String tag, long observedAt);
}
