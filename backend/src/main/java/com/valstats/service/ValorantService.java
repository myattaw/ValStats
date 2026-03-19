package com.valstats.service;

import com.valstats.client.ValorantApiClient;
import com.valstats.service.match.MatchDataService;
import com.valstats.service.player.PlayerCacheService;
import com.valstats.service.player.PlayerStatsService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Main service for Valorant API operations.
 * This is now a facade that delegates to specialized services:
 * - MatchDataService: handles match retrieval and caching
 * - PlayerStatsService: handles player statistics
 * - PlayerCacheService: handles player profile caching
 * - ValorantApiClient: calls HenrikDev API
 *
 * Strategy:
 * 1. Match History: Cache stored matches + MMR history in DynamoDB. Only call API when cache is empty.
 * 2. MMR History: Store all MMR changes in DynamoDB so data isn't lost (expires every 2 weeks on API side)
 * 3. Recent Matches: Update every 5 minutes from /v3/matches endpoint to find new matches
 * 4. Player Stats: Aggregate from cached matches, or load from API if needed
 */
@Singleton
public class ValorantService {

    private static final Logger LOG = LoggerFactory.getLogger(ValorantService.class);

    private final MatchDataService matchDataService;
    private final PlayerStatsService playerStatsService;
    private final PlayerCacheService playerCacheService;
    private final ValorantApiClient apiClient;
    private final String apiKey;

    public ValorantService(
            MatchDataService matchDataService,
            PlayerStatsService playerStatsService,
            PlayerCacheService playerCacheService,
            ValorantApiClient apiClient
    ) {
        this.matchDataService = matchDataService;
        this.playerStatsService = playerStatsService;
        this.playerCacheService = playerCacheService;
        this.apiClient = apiClient;
        this.apiKey = System.getenv("HDEV_KEY");
    }

    /**
     * Get match history for a player with pagination.
     * Delegates to MatchDataService which handles caching strategy.
     */
    public Map<String, Object> getUnifiedMatches(String region, String name, String tag, int size, int page) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        return matchDataService.getPlayerMatches(puuid, region, name, tag, size, page);
    }

    /**
     * Get account details from HenrikDev API
     */
    public Map<String, Object> getAccountDetails(String name, String tag) {
        return apiClient.getAccount(name, tag, apiKey);
    }

    /**
     * Get full match details by ID.
     * Delegates to MatchDataService which handles caching strategy.
     */
    public Map<String, Object> getMatchById(String matchId) {
        return matchDataService.getMatchDetails(matchId);
    }

    /**
     * Get aggregated player stats (K/D, HS%, ACS, K/R, ADR).
     * Delegates to PlayerStatsService.
     */
    public Map<String, Object> getPlayerStats(String region, String name, String tag, String seasonId) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        return playerStatsService.getPlayerStats(puuid, region, name, tag, seasonId);
    }

    /**
     * Get only ADR for a player.
     * Delegates to PlayerStatsService.
     */
    public Map<String, Object> getPlayerAdr(String region, String name, String tag, String seasonId) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        return playerStatsService.getPlayerAdr(puuid, region, name, tag, seasonId);
    }

    /**
     * Update recently played matches for a player.
     * This enforces a 5-minute cooldown to avoid excessive API calls.
     */
    public Map<String, Object> updateRecentMatches(String region, String name, String tag) {
        matchDataService.updateRecentMatches(region, name, tag);
        return Map.of("status", 200, "message", "Recently played matches updated");
    }

    /**
     * Resolve a player's PUUID from their name and tag.
     * Uses cache first, then API if needed.
     */
    private String resolvePuuid(String name, String tag, String region) {
        Optional<String> cached = playerCacheService.getPuuidByNameTag(name, tag);
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            Map<String, Object> account = apiClient.getAccount(name, tag, apiKey);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) account.getOrDefault("data", new HashMap<>());

            if (data.get("puuid") != null) {
                String puuid = String.valueOf(data.get("puuid"));
                String playerRegion = String.valueOf(data.getOrDefault("region", region != null ? region : "na"));
                playerCacheService.storePlayerProfile(puuid, name, tag, playerRegion);
                return puuid;
            }
        } catch (Exception e) {
            LOG.error("Failed to resolve puuid for {}#{}", name, tag, e);
        }

        return null;
    }

    private Map<String, Object> errorResponse(String msg) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 404);
        response.put("error", msg);
        return response;
    }
}

