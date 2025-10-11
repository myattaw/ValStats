package com.valstats.service;

import com.valstats.client.ValorantApiClient;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class ValorantService {

    private final ValorantApiClient valorantApiClient;
    private static final String AUTH_TOKEN = System.getenv("HDEV_KEY");

    public ValorantService(ValorantApiClient valorantApiClient) {
        this.valorantApiClient = valorantApiClient;
    }

    public Map<String, Object> getMatches(String region, String playerName, String playerTag) {
        return valorantApiClient.getMatches(region, playerName, playerTag, AUTH_TOKEN);
    }



}

