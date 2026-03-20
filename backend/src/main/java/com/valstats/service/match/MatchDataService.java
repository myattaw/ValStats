package com.valstats.service.match;

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
            int page, // (ignored for GSI, kept for compatibility)
            String act
    ) {
        // =========================
        // 1. CHECK CACHE (FIXED)
        // =========================
        QueryResponse check = dynamoDbService.getMatchesFromGSI(puuid, 1, null);
        boolean hasAnyMatches = !check.items().isEmpty();

        // =========================
        // 2. BACKFILL IF EMPTY
        // =========================
        if (!hasAnyMatches) {
            LOG.info("First-time load for player {}. Backfilling...", puuid);

            Map<String, Object> storedMatches =
                    apiClient.getStoredMatches(region, name, tag, 1000, 1, "competitive", apiKey);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches =
                    (List<Map<String, Object>>) storedMatches.getOrDefault("data", Collections.emptyList());

            for (Map<String, Object> match : matches) {
                matchProcessor.processStoredMatchSummary(match, puuid);
            }

            Map<String, Object> mmrHistory =
                    apiClient.getMMRHistory(region, name, tag, apiKey);

            cacheMMRHistory(puuid, mmrHistory);
        }

        // =========================
        // 3. FETCH MATCHES
        // =========================
        List<Map<String, AttributeValue>> cachedMatches;
        Map<String, AttributeValue> lastKey = null;

        if (act != null && !"all".equals(act)) {
            // ✅ SEASON FILTER (old logic still OK)
            cachedMatches = dynamoDbService.getMatchesBySeason(puuid, act, size, page);

        } else {
            // 🔥 GSI QUERY (correct ordering)
            QueryResponse response = dynamoDbService.getMatchesFromGSI(
                    puuid,
                    size,
                    null // TODO: replace with cursor later
            );

            cachedMatches = response.items();
            lastKey = response.lastEvaluatedKey();
        }

        // =========================
        // 4. FETCH MMR
        // =========================
        List<Map<String, AttributeValue>> cachedMMR =
                dynamoDbService.getMMRHistory(puuid);

        // =========================
        // 5. FORMAT RESPONSE
        // =========================
        Map<String, Object> result =
                responseFormatter.formatCachedMatches(cachedMatches, cachedMMR);

        // 🔥 IMPORTANT: return cursor for frontend
        if (lastKey != null && !lastKey.isEmpty()) {
            Map<String, Object> safeLastKey = convertLastKey(lastKey);

            if (safeLastKey != null) {
                result.put("lastKey", safeLastKey);
            }
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

