package com.valstats.service;

import com.valstats.client.ValorantApiClient;
import com.valstats.service.match.MatchDataService;
import com.valstats.service.player.PlayerCacheService;
import com.valstats.service.player.PlayerStatsService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.*;

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
    private final DynamoDbService dynamoDbService;

    private static final Map<String, String> SEASON_MAP = new LinkedHashMap<>();
    private static final Map<String, String> SEASON_TO_HENRIK = new HashMap<>();

    //TODO: automatically generate these later
    static {
        SEASON_MAP.put("9d85c932-4820-c060-09c3-668636d4df1b", "Episode 11 Act 2");
        SEASON_MAP.put("3ea2b318-423b-cf86-25da-7cbb0eefbe2d", "Episode 11 Act 1");
        SEASON_MAP.put("4c4b8cff-43eb-13d3-8f14-96b783c90cd2", "Episode 10 Act 6");
        SEASON_MAP.put("5adc33fa-4f30-2899-f131-6fba64c5dd3a", "Episode 10 Act 5");
        SEASON_MAP.put("ac12e9b3-47e6-9599-8fa1-0bb473e5efc7", "Episode 10 Act 4");
        SEASON_MAP.put("aef237a0-494d-3a14-a1c8-ec8de84e309c", "Episode 10 Act 3");
        SEASON_MAP.put("16118998-4705-5813-86dd-0292a2439d90", "Episode 10 Act 2");
        SEASON_MAP.put("476b0893-4c2e-abd6-c5fe-708facff0772", "Episode 10 Act 1");
        SEASON_MAP.put("dcde7346-4085-de4f-c463-2489ed47983b", "Episode 9 Act 3");
        SEASON_MAP.put("292f58db-4c17-89a7-b1c0-ba988f0e9d98", "Episode 9 Act 2");
        SEASON_MAP.put("52ca6698-41c1-e7de-4008-8994d2221209", "Episode 9 Act 1");
        SEASON_MAP.put("4539cac3-47ae-90e5-3d01-b3812ca3274e", "Episode 8 Act 3");
        SEASON_MAP.put("22d10d66-4d2a-a340-6c54-408c7bd53807", "Episode 8 Act 2");
        SEASON_MAP.put("ec876e6c-43e8-fa63-ffc1-2e8d4db25525", "Episode 8 Act 1");
        SEASON_MAP.put("4401f9fd-4170-2e4c-4bc3-f3b4d7d150d1", "Episode 7 Act 3");
        SEASON_MAP.put("03dfd004-45d4-ebfd-ab0a-948ce780dac4", "Episode 7 Act 2");
        SEASON_MAP.put("0981a882-4e7d-371a-70c4-c3b4f46c504a", "Episode 7 Act 1");
        SEASON_MAP.put("2de5423b-4aad-02ad-8d9b-c0a931958861", "Episode 6 Act 3");
        SEASON_MAP.put("34093c29-4306-43de-452f-3f944bde22be", "Episode 6 Act 2");
        SEASON_MAP.put("9c91a445-4f78-1baa-a3ea-8f8aadf4914d", "Episode 6 Act 1");
        SEASON_MAP.put("aca29595-40e4-01f5-3f35-b1b3d304c96e", "Episode 5 Act 3");
        SEASON_MAP.put("7a85de9a-4032-61a9-61d8-f4aa2b4a84b6", "Episode 5 Act 2");
        SEASON_MAP.put("67e373c7-48f7-b422-641b-079ace30b427", "Episode 5 Act 1");
        SEASON_MAP.put("3e47230a-463c-a301-eb7d-67bb60357d4f", "Episode 4 Act 3");
        SEASON_MAP.put("d929bc38-4ab6-7da4-94f0-ee84f8ac141e", "Episode 4 Act 2");

        SEASON_TO_HENRIK.put("9d85c932-4820-c060-09c3-668636d4df1b", "e11a2");
        SEASON_TO_HENRIK.put("3ea2b318-423b-cf86-25da-7cbb0eefbe2d", "e11a1");

        SEASON_TO_HENRIK.put("4c4b8cff-43eb-13d3-8f14-96b783c90cd2", "e10a6");
        SEASON_TO_HENRIK.put("5adc33fa-4f30-2899-f131-6fba64c5dd3a", "e10a5");
        SEASON_TO_HENRIK.put("ac12e9b3-47e6-9599-8fa1-0bb473e5efc7", "e10a4");
        SEASON_TO_HENRIK.put("aef237a0-494d-3a14-a1c8-ec8de84e309c", "e10a3");
        SEASON_TO_HENRIK.put("16118998-4705-5813-86dd-0292a2439d90", "e10a2");
        SEASON_TO_HENRIK.put("476b0893-4c2e-abd6-c5fe-708facff0772", "e10a1");

        SEASON_TO_HENRIK.put("dcde7346-4085-de4f-c463-2489ed47983b", "e9a3");
        SEASON_TO_HENRIK.put("292f58db-4c17-89a7-b1c0-ba988f0e9d98", "e9a2");
        SEASON_TO_HENRIK.put("52ca6698-41c1-e7de-4008-8994d2221209", "e9a1");

        SEASON_TO_HENRIK.put("4539cac3-47ae-90e5-3d01-b3812ca3274e", "e8a3");
        SEASON_TO_HENRIK.put("22d10d66-4d2a-a340-6c54-408c7bd53807", "e8a2");
        SEASON_TO_HENRIK.put("ec876e6c-43e8-fa63-ffc1-2e8d4db25525", "e8a1");

        SEASON_TO_HENRIK.put("4401f9fd-4170-2e4c-4bc3-f3b4d7d150d1", "e7a3");
        SEASON_TO_HENRIK.put("03dfd004-45d4-ebfd-ab0a-948ce780dac4", "e7a2");
        SEASON_TO_HENRIK.put("0981a882-4e7d-371a-70c4-c3b4f46c504a", "e7a1");

        SEASON_TO_HENRIK.put("2de5423b-4aad-02ad-8d9b-c0a931958861", "e6a3");
        SEASON_TO_HENRIK.put("34093c29-4306-43de-452f-3f944bde22be", "e6a2");
        SEASON_TO_HENRIK.put("9c91a445-4f78-1baa-a3ea-8f8aadf4914d", "e6a1");

        SEASON_TO_HENRIK.put("aca29595-40e4-01f5-3f35-b1b3d304c96e", "e5a3");
        SEASON_TO_HENRIK.put("7a85de9a-4032-61a9-61d8-f4aa2b4a84b6", "e5a2");
        SEASON_TO_HENRIK.put("67e373c7-48f7-b422-641b-079ace30b427", "e5a1");

        SEASON_TO_HENRIK.put("3e47230a-463c-a301-eb7d-67bb60357d4f", "e4a3");
        SEASON_TO_HENRIK.put("d929bc38-4ab6-7da4-94f0-ee84f8ac141e", "e4a2");
    }

    public ValorantService(
            MatchDataService matchDataService,
            PlayerStatsService playerStatsService,
            PlayerCacheService playerCacheService,
            ValorantApiClient apiClient,
            DynamoDbService dynamoDbService
    ) {
        this.matchDataService = matchDataService;
        this.playerStatsService = playerStatsService;
        this.playerCacheService = playerCacheService;
        this.apiClient = apiClient;
        this.apiKey = System.getenv("HDEV_KEY");
        this.dynamoDbService = dynamoDbService;
    }

    /**
     * Get match history for a player with pagination.
     * Delegates to MatchDataService which handles caching strategy.
     */
    public Object getUnifiedMatches(
            String region,
            String name,
            String tag,
            int size,
            String lastKey,
            String act
    ) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        return matchDataService.getPlayerMatches(
                puuid,
                region,
                name,
                tag,
                size,
                lastKey,
                act
        );
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
    public Object getMatchById(String matchId) {
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

    public List<Map<String, String>> getAvailableActs(String region, String name, String tag) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) return List.of();

        List<Map<String, AttributeValue>> items =
                dynamoDbService.getStoredMatchesForPlayer(puuid, 1000, 1);

        Map<String, Long> seasonLatestGame = new HashMap<>();

        for (Map<String, AttributeValue> item : items) {
            AttributeValue skAttr = item.get("SK");
            if (skAttr == null || skAttr.s() == null) continue;

            String sk = skAttr.s();

            if (!sk.contains("#MATCH#")) continue;

            String[] parts = sk.split("#");
            if (parts.length < 4) continue;

            String seasonId = parts[1];

            long gameStart;
            try {
                gameStart = Long.parseLong(parts[3]);
            } catch (Exception e) {
                continue;
            }

            seasonLatestGame.merge(seasonId, gameStart, Math::max);
        }

        return seasonLatestGame.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(entry -> Map.of(
                        "value", entry.getKey(),
                        "label", SEASON_MAP.getOrDefault(entry.getKey(), entry.getKey())
                ))
                .toList();
    }

    /**
     * Returns MMR data. If seasonId is a Riot season UUID, this adds:
     * - selected_season_key
     * - selected_season
     *
     * So the frontend can use act-specific rank/winrate cleanly.
     */
    public Map<String, Object> getPlayerMMR(String region, String name, String tag, String seasonId) {
        try {
            Map<String, Object> mmrResponse = apiClient.getMMR(region, name, tag, apiKey);

            if (mmrResponse == null) {
                return errorResponse("MMR response was null");
            }

            Object dataObj = mmrResponse.get("data");
            if (!(dataObj instanceof Map<?, ?> rawData)) {
                return mmrResponse;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) rawData;

            Object bySeasonObj = data.get("by_season");
            if (!(bySeasonObj instanceof Map<?, ?> rawBySeason)) {
                return mmrResponse;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> bySeason = (Map<String, Object>) rawBySeason;

            if (seasonId != null && !seasonId.isBlank() && !"all".equalsIgnoreCase(seasonId)) {
                String henrikSeasonKey = mapToHenrikSeason(seasonId);

                if (henrikSeasonKey != null) {
                    data.put("selected_season_key", henrikSeasonKey);
                    data.put("selected_season", bySeason.get(henrikSeasonKey));

                    Object selectedSeasonObj = bySeason.get(henrikSeasonKey);
                    if (selectedSeasonObj instanceof Map<?, ?> rawSelectedSeason) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> selectedSeason = (Map<String, Object>) rawSelectedSeason;

                        Object finalRank = selectedSeason.get("final_rank");
                        Object finalRankPatched = selectedSeason.get("final_rank_patched");

                        if (finalRank != null) {
                            data.put("selected_rank_tier", finalRank);
                        }
                        if (finalRankPatched != null) {
                            data.put("selected_rank_name", finalRankPatched);
                        }
                    }
                } else {
                    data.put("selected_season_key", null);
                    data.put("selected_season", null);
                }
            } else {
                data.put("selected_season_key", null);
                data.put("selected_season", null);
            }

            return mmrResponse;

        } catch (Exception e) {
            LOG.error("Failed to get MMR for {}#{}", name, tag, e);
            return errorResponse("Failed to load MMR");
        }
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
            Map<String, Object> data =
                    (Map<String, Object>) account.getOrDefault("data", new HashMap<>());

            if (data.get("puuid") != null) {
                String puuid = String.valueOf(data.get("puuid"));
                String playerRegion = String.valueOf(
                        data.getOrDefault("region", region != null ? region : "na")
                );
                playerCacheService.storePlayerProfile(puuid, name, tag, playerRegion);
                return puuid;
            }
        } catch (Exception e) {
            LOG.error("Failed to resolve puuid for {}#{}", name, tag, e);
        }

        return null;
    }

    private String mapToHenrikSeason(String seasonId) {
        return SEASON_TO_HENRIK.get(seasonId);
    }

    private Map<String, Object> errorResponse(String msg) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 404);
        response.put("error", msg);
        return response;
    }
}