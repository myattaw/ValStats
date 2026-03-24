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

        boolean didSync = false;

        // =========================
        // 🔥 INITIAL BACKFILL
        // =========================
        if (currentCount == 0) {
            LOG.info("Initial backfill for {}", puuid);

            Map<String, Object> storedMatches =
                    apiClient.getStoredMatches(region, name, tag, 1000, 1, "competitive", apiKey);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches =
                    (List<Map<String, Object>>) storedMatches.getOrDefault("data", Collections.emptyList());

            for (Map<String, Object> match : matches) {
                matchProcessor.processStoredMatchSummary(match, puuid);
            }

            didSync = true;

        } else {
            // =========================
            // 🔥 INCREMENTAL SYNC (5 min cooldown)
            // =========================
            Optional<Long> lastUpdateTime =
                    dynamoDbService.getPlayerLastRecentMatchUpdate(region, name, tag);

            long now = System.currentTimeMillis() / 1000;
            long lastUpdate = lastUpdateTime.orElse(0L);

            if (now - lastUpdate > 300) { // 5 minutes
                LOG.info("Running incremental sync for {}#{}", name, tag);

                syncNewMatches(puuid, region, name, tag);

                dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag);
                didSync = true;

            } else {
                LOG.debug("Skipping sync (cooldown active) for {}#{}", name, tag);
            }
        }

        // =========================
        // 🔥 FETCH MATCHES (GSI PAGINATION)
        // =========================
        List<Map<String, AttributeValue>> cachedMatches;
        Map<String, AttributeValue> responseLastKey = null;

        if (act != null && !"all".equalsIgnoreCase(act)) {
            // season mode (still offset-based for now)
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

        // =========================
        // 🔥 ENSURE MMR IS FRESH
        // =========================
        List<Map<String, AttributeValue>> cachedMMR =
                dynamoDbService.getMMRHistory(puuid);

        if (cachedMMR.isEmpty() || didSync) {
            LOG.info("Refreshing MMR for {}", puuid);

            Map<String, Object> mmrHistory =
                    apiClient.getMMRHistory(region, name, tag, apiKey);

            cacheMMRHistory(puuid, mmrHistory);

            cachedMMR = dynamoDbService.getMMRHistory(puuid);
        }

        // =========================
        // 🔥 FORMAT RESPONSE
        // =========================
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

    public void syncNewMatches(String puuid, String region, String name, String tag) {

        LOG.info("Starting incremental sync for {}#{}", name, tag);

        // 🔥 Get latest stored match timestamp
        QueryResponse latest = dynamoDbService.getMatchesFromGSI(puuid, 1, null);

        long latestTimestamp = 0;

        if (!latest.items().isEmpty()) {
            latestTimestamp = Long.parseLong(
                    latest.items().get(0).get("gameStart").n()
            );
        }

        LOG.info("Latest stored timestamp: {}", latestTimestamp);

        int start = 0;
        int batchSize = 10;
        int maxIterations = 10;

        boolean foundExistingMatch = false;
        int iterations = 0;

        while (!foundExistingMatch && iterations < maxIterations) {

            Map<String, Object> response;

            try {
                response = apiClient.getMatches(region, name, tag, batchSize, start, "competitive", apiKey);
            } catch (Exception e) {
                LOG.error("Failed to fetch matches at start={} for {}#{}", start, name, tag, e);
                break;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches =
                    (List<Map<String, Object>>) response.getOrDefault("data", Collections.emptyList());

            if (matches.isEmpty()) break;

            for (Map<String, Object> match : matches) {

                Map<String, Object> metadata = (Map<String, Object>) match.get("metadata");

                if (metadata == null) {
                    LOG.warn("Metadata is null for match: {}", match);
                    continue;
                }

                String matchId = Objects.toString(metadata.get("matchid"), "");
                Object tsObj = metadata.get("game_start");

                if (matchId.isBlank() || tsObj == null) {
                    LOG.warn("Invalid match metadata: {}", metadata);
                    continue;
                }

                long matchTimestamp = ((Number) tsObj).longValue();

                LOG.info("API matchId: {}, timestamp: {}", matchId, matchTimestamp);

                // 🔥 STOP CONDITION (correct one)
                if (matchTimestamp <= latestTimestamp) {
                    LOG.info("Reached existing match → stopping sync at {} (iterations: {})", matchId,  iterations);
                    foundExistingMatch = true;
                    break;
                }

                // 🔥 NEW MATCH
                LOG.info("Storing NEW match {}", matchId);
                matchProcessor.processStoredMatchSummary(match, puuid);
            }

            // stop if no more matches available
            if (matches.size() < batchSize) {
                break;
            }

            start += batchSize;
            iterations++;
        }

        LOG.info("Incremental sync complete for {}#{}", name, tag);
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

