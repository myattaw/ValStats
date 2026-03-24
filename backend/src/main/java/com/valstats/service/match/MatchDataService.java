package com.valstats.service.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valstats.client.ValorantApiClient;
import com.valstats.service.DynamoDbService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.*;

/**
 * Handles all match data retrieval and caching logic.
 * Separates the logic of "get data from cache first, then API" from response formatting.
 */
@Singleton
public class MatchDataService {
    private static final Logger LOG = LoggerFactory.getLogger(MatchDataService.class);

    private final DynamoDbService dynamoDbService;
    private final ValorantApiClient apiClient;
    private final MatchResponseFormatter responseFormatter;
    private final MatchProcessor matchProcessor;
    private final String apiKey;

    public MatchDataService(
            DynamoDbService dynamoDbService,
            ValorantApiClient apiClient,
            MatchResponseFormatter responseFormatter,
            MatchProcessor matchProcessor
    ) {
        this.dynamoDbService = dynamoDbService;
        this.apiClient = apiClient;
        this.responseFormatter = responseFormatter;
        this.matchProcessor  = matchProcessor;
        this.apiKey = System.getenv("HDEV_KEY");
    }

    /**
     * Get match history for a player.
     * Strategy: Check cache first, then API if needed
     */
    public Map<String, Object> getPlayerMatches(
            String puuid,
            String region,
            String name,
            String tag,
            int size,
            String lastKeyJson,
            String act
    ) {
        // 🔥 Check how many matches we actually have
        QueryResponse check = dynamoDbService.getMatchesFromGSI(puuid, 50, null);
        int currentCount = check.items().size();

        boolean needsBackfill = currentCount < 50;

        if (needsBackfill) {
            LOG.info("Backfilling matches for player {} (currently {} matches)...", puuid, currentCount);

            Map<String, Object> storedMatches =
                    apiClient.getStoredMatches(region, name, tag, 1000, 1, "competitive", apiKey);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches =
                    (List<Map<String, Object>>) storedMatches.getOrDefault("data", Collections.emptyList());

            for (Map<String, Object> match : matches) {
                matchProcessor.processStoredMatchSummary(match, puuid);
            }

            // ✅ Always refresh MMR during backfill
            Map<String, Object> mmrHistory =
                    apiClient.getMMRHistory(region, name, tag, apiKey);

            cacheMMRHistory(puuid, mmrHistory);
        }

        List<Map<String, AttributeValue>> cachedMatches;
        Map<String, AttributeValue> responseLastKey = null;

        if (act != null && !"all".equalsIgnoreCase(act)) {
            // season mode (still not cursor-based yet)
            cachedMatches = dynamoDbService.getMatchesBySeason(puuid, act, size, 1);
        } else {
            Map<String, AttributeValue> exclusiveStartKey = parseLastKey(lastKeyJson);

            QueryResponse response = dynamoDbService.getMatchesFromGSI(
                    puuid,
                    size,
                    exclusiveStartKey
            );

            cachedMatches = response.items();
            responseLastKey = response.lastEvaluatedKey();
        }

        // ✅ Ensure MMR exists even if backfill didn't run
        List<Map<String, AttributeValue>> cachedMMR =
                dynamoDbService.getMMRHistory(puuid);

        if (cachedMMR.isEmpty()) {
            LOG.info("MMR cache empty for {}. Fetching...", puuid);

            Map<String, Object> mmrHistory =
                    apiClient.getMMRHistory(region, name, tag, apiKey);

            cacheMMRHistory(puuid, mmrHistory);

            cachedMMR = dynamoDbService.getMMRHistory(puuid);
        }

        Map<String, Object> result =
                responseFormatter.formatCachedMatches(cachedMatches, cachedMMR);

        if (act != null && !"all".equalsIgnoreCase(act)) {
            result.put("lastKey", null);
        } else {
            result.put("lastKey",
                    (responseLastKey != null && !responseLastKey.isEmpty())
                            ? convertLastKey(responseLastKey)
                            : null
            );
        }

        return result;
    }

    private Map<String, Object> convertLastKey(Map<String, AttributeValue> lastKey) {
        if (lastKey == null || lastKey.isEmpty()) return null;

        Map<String, Object> result = new HashMap<>();

        for (Map.Entry<String, AttributeValue> entry : lastKey.entrySet()) {
            AttributeValue val = entry.getValue();

            if (val.s() != null) {
                result.put(entry.getKey(), val.s());
            } else if (val.n() != null) {
                result.put(entry.getKey(), Long.parseLong(val.n()));
            }
        }

        return result;
    }

    private Map<String, AttributeValue> parseLastKey(String json) {
        if (json == null || json.isBlank()) return null;

        try {
            String decoded = java.net.URLDecoder.decode(json, java.nio.charset.StandardCharsets.UTF_8);

            Map<String, Object> map = new ObjectMapper().readValue(
                    decoded,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
            );

            Map<String, AttributeValue> result = new HashMap<>();

            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object val = entry.getValue();

                if (val instanceof Number) {
                    result.put(entry.getKey(), AttributeValue.fromN(val.toString()));
                } else {
                    result.put(entry.getKey(), AttributeValue.fromS(val.toString()));
                }
            }

            return result;

        } catch (Exception e) {
            LOG.error("Failed to parse lastKey: {}", json, e);
            return null;
        }
    }

    /**
     * Get full match details by match ID.
     * Strategy: Check cache first, then API if needed
     */
    public Map<String, Object> getMatchDetails(String matchId) {
        // Try cache first
        Optional<Map<String, AttributeValue>> cached = dynamoDbService.getMatchById(matchId);
        if (cached.isPresent()) {
            LOG.debug("Returning cached match details for {}", matchId);
            List<Map<String, AttributeValue>> players = dynamoDbService.getMatchPlayers(matchId);
            return responseFormatter.formatCachedMatchDetails(cached.get(), players);
        }

        // Cache miss - fetch from API
        LOG.info("Cache miss for match {}. Fetching from API...", matchId);
        Map<String, Object> apiResponse = apiClient.getMatchById(matchId, apiKey);
        return apiResponse;
    }

    /**
     * Update recently played matches for a player.
     * This should be called periodically (every 5 minutes) to get new matches.
     * Returns only the new matches since last update.
     */
    public List<Map<String, Object>> updateRecentMatches(String region, String name, String tag) {
        Optional<Long> lastUpdateTime = dynamoDbService.getPlayerLastRecentMatchUpdate(region, name, tag);
        long timeSinceLastUpdate = System.currentTimeMillis() / 1000 - lastUpdateTime.orElse(0L);

        // Only update if 5+ minutes have passed
        if (timeSinceLastUpdate < 300) {
            LOG.debug("Recent match update skipped for {}#{} - cooldown active", name, tag);
            return Collections.emptyList();
        }

        LOG.info("Updating recent matches for {}#{}", name, tag);
        // Note: getRecentMatches returns MatchResponse, not Map - it will be handled by its own response handler
        apiClient.getRecentMatches(region, name, tag, 20, 0, apiKey);
        dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag);

        return Collections.emptyList();
    }

    /**
     * Cache MMR history from API response
     */
    private void cacheMMRHistory(String puuid, Map<String, Object> apiResponse) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) apiResponse.getOrDefault("data", Collections.emptyList());

        for (Map<String, Object> entry : entries) {
            String matchId = Objects.toString(entry.getOrDefault("match_id", ""), "");
            if (matchId.isBlank()) {
                continue;
            }

            int rrChange = getInt(entry.get("mmr_change_to_last_game"));
            int elo = getInt(entry.get("elo"));

            int currentTier = getInt(entry.get("currenttier"));
            int rankingInTier = getInt(entry.get("ranking_in_tier"));

            String rank = Objects.toString(entry.get("currenttier_patched"), "Unknown");

            long timestamp = getLong(entry.get("date_raw"));

            dynamoDbService.storeMMREntry(puuid, matchId, rrChange, elo, rankingInTier, currentTier, rank, timestamp);
        }
    }

    private int getInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private long getLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}

