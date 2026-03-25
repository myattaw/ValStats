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
    private static final int STORED_MATCH_PAGE_SIZE = 50;
    private static final int MAX_STORED_MATCH_PAGES = 200;
    private static final int INITIAL_BACKFILL_THRESHOLD = 100;

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
        QueryResponse check = dynamoDbService.getMatchesFromGSI(puuid, 50, null);
        int currentCount = check.items().size();

        boolean didSync = false;

        // =========================
        // INITIAL BACKFILL / TOP-UP
        // =========================
        if (currentCount < INITIAL_BACKFILL_THRESHOLD) {
            LOG.info("Initial backfill/top-up for {} (currentCount={})", puuid, currentCount);

            syncStoredMatches(
                    puuid,
                    region,
                    name,
                    tag,
                    STORED_MATCH_PAGE_SIZE,
                    true
            );

            dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag);
            didSync = true;

        } else {
            // =========================
            // INCREMENTAL SYNC
            // =========================
            Optional<Long> lastUpdateTime =
                    dynamoDbService.getPlayerLastRecentMatchUpdate(region, name, tag);

            long now = System.currentTimeMillis() / 1000;
            long lastUpdate = lastUpdateTime.orElse(0L);

            if (now - lastUpdate > 300) {
                LOG.info("Running stored-match sync for {}#{}", name, tag);

                syncStoredMatches(
                        puuid,
                        region,
                        name,
                        tag,
                        STORED_MATCH_PAGE_SIZE,
                        false
                );

                dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag);
                didSync = true;
            } else {
                LOG.debug("Skipping sync (cooldown active) for {}#{}", name, tag);
            }
        }

        // =========================
        // FETCH MATCHES
        // =========================
        List<Map<String, AttributeValue>> cachedMatches;
        Map<String, AttributeValue> responseLastKey = null;

        Map<String, AttributeValue> exclusiveStartKey = parseLastKey(lastKeyJson);

        if (act != null && !"all".equalsIgnoreCase(act)) {
            QueryResponse response = dynamoDbService.getMatchesBySeasonPaginated(
                    puuid,
                    act,
                    size,
                    exclusiveStartKey
            );

            cachedMatches = response.items();
            responseLastKey = response.lastEvaluatedKey();

        } else {
            QueryResponse response = dynamoDbService.getMatchesFromGSI(
                    puuid,
                    size,
                    exclusiveStartKey
            );

            cachedMatches = response.items();
            responseLastKey = response.lastEvaluatedKey();
        }

        // =========================
        // ENSURE MMR IS FRESH
        // =========================
        List<Map<String, AttributeValue>> cachedMMR =
                dynamoDbService.getMMRHistory(puuid);

        if (cachedMMR.isEmpty() || didSync) {
            LOG.info("Refreshing MMR for {}", puuid);

            try {
                Map<String, Object> mmrHistory =
                        apiClient.getMMRHistory(region, name, tag, apiKey);

                cacheMMRHistory(puuid, mmrHistory);
            } catch (Exception e) {
                LOG.warn("MMR refresh failed for {}#{}. Returning matches without fresh MMR.", name, tag, e);
            }

            cachedMMR = dynamoDbService.getMMRHistory(puuid);
        }

        // =========================
        // FORMAT RESPONSE
        // =========================
        Map<String, Object> result =
                responseFormatter.formatCachedMatches(cachedMatches, cachedMMR);

        result.put(
                "lastKey",
                (responseLastKey != null && !responseLastKey.isEmpty())
                        ? convertLastKey(responseLastKey)
                        : null
        );

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
                matchProcessor.processRecentMatchSummary(match, puuid);
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

    public void syncStoredMatches(
            String puuid,
            String region,
            String name,
            String tag,
            int batchSize,
            boolean isInitialBackfill
    ) {
        LOG.info("Starting stored-match sync for {}#{} (initialBackfill={})", name, tag, isInitialBackfill);

        QueryResponse latest = dynamoDbService.getMatchesFromGSI(puuid, 1, null);

        long latestTimestamp = 0;
        if (!latest.items().isEmpty() && latest.items().get(0).containsKey("gameStart")) {
            latestTimestamp = Long.parseLong(latest.items().get(0).get("gameStart").n());
        }

        LOG.info("Latest stored timestamp: {}", latestTimestamp);

        int page = 1;
        int pagesFetched = 0;
        boolean foundExisting = false;
        int processed = 0;
        int skipped = 0;
        String previousFirstMatchId = null;

        while (!foundExisting && page <= MAX_STORED_MATCH_PAGES) {
            Map<String, Object> response;
            try {
                response = apiClient.getStoredMatches(
                        region,
                        name,
                        tag,
                        batchSize,
                        page,
                        "competitive",
                        apiKey
                );
            } catch (Exception e) {
                LOG.error("Failed stored-match request page={} size={} for {}#{}", page, batchSize, name, tag, e);
                break;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches =
                    (List<Map<String, Object>>) response.getOrDefault("data", Collections.emptyList());

            if (matches.isEmpty()) {
                LOG.info("Stored-match sync reached end at page {} for {}#{}", page, name, tag);
                break;
            }

            pagesFetched++;

            String currentFirstMatchId = extractStoredMatchId(matches.get(0));
            if (previousFirstMatchId != null && previousFirstMatchId.equals(currentFirstMatchId)) {
                LOG.warn("Stored-match API repeated page data at page {} for {}#{}. Stopping to avoid loop.", page, name, tag);
                break;
            }
            previousFirstMatchId = currentFirstMatchId;

            for (Map<String, Object> match : matches) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) match.get("meta");

                if (meta == null) {
                    skipped++;
                    continue;
                }

                Object tsObj = meta.get("started_at");
                String matchId = Objects.toString(meta.get("id"), "");

                if (tsObj == null || matchId.isBlank()) {
                    skipped++;
                    continue;
                }

                long matchTimestamp = parseTimestamp(tsObj);
                if (matchTimestamp < 0) {
                    skipped++;
                    continue;
                }

                // Only stop on existing timestamps during incremental sync
                if (!isInitialBackfill && latestTimestamp > 0 && matchTimestamp <= latestTimestamp) {
                    foundExisting = true;
                    break;
                }

                if (matchProcessor.processStoredMatchSummary(match, puuid)) {
                    processed++;
                } else {
                    skipped++;
                }
            }

            if (matches.size() < batchSize) {
                LOG.info("Stored-match sync reached final partial page {} for {}#{}", page, name, tag);
                break;
            }

            page++;
        }

        if (page > MAX_STORED_MATCH_PAGES) {
            LOG.warn("Stored-match sync hit safety page cap ({}) for {}#{}", MAX_STORED_MATCH_PAGES, name, tag);
        }

        LOG.info(
                "Stored-match sync complete for {}#{} (initialBackfill={}, processed={}, skipped={}, pages={})",
                name, tag, isInitialBackfill, processed, skipped, pagesFetched
        );
    }

    /**
     * Cache MMR history from API response
     */
    private void cacheMMRHistory(String puuid, Map<String, Object> apiResponse) {
        if (apiResponse == null) {
            LOG.debug("MMR response is null for {}", puuid);
            return;
        }

        Object dataObj = apiResponse.get("data");
        if (!(dataObj instanceof List<?> rawEntries)) {
            LOG.debug("MMR response has no data list for {}", puuid);
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) (List<?>) rawEntries;

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

    private long parseTimestamp(Object startedAt) {
        if (startedAt == null) {
            return -1L;
        }

        if (startedAt instanceof Number n) {
            return n.longValue();
        }

        try {
            return java.time.Instant.parse(startedAt.toString()).getEpochSecond();
        } catch (Exception e) {
            LOG.debug("Failed to parse started_at timestamp: {}", startedAt);
            return -1L;
        }
    }

    private String extractStoredMatchId(Map<String, Object> match) {
        if (match == null) {
            return "";
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) match.get("meta");
        if (meta == null) {
            return "";
        }

        return Objects.toString(meta.get("id"), "");
    }

}

