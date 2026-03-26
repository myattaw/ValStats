package com.valstats.service.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valstats.client.ValorantApiClient;
import com.valstats.model.response.MatchResponses;
import com.valstats.model.stored.StoredMatchesResponse;
import com.valstats.service.DynamoDbService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        this.matchProcessor = matchProcessor;
        this.apiKey = System.getenv("HDEV_KEY");
    }

    /**
     * Get match history for a player.
     * Strategy: Check cache first, then API if needed
     */
    public MatchResponses.MatchHistoryResponse getPlayerMatches(
            String puuid,
            String region,
            String name,
            String tag,
            int size,
            String lastKeyJson,
            String act
    ) {
        QueryResponse check = dynamoDbService.getMatchesFromGSI(puuid, 50, null);

        boolean didSync = false;

        // =========================
        // INITIAL BACKFILL / TOP-UP
        // =========================
        boolean hasAnyMatches = !check.items().isEmpty();

        if (!hasAnyMatches) {
            LOG.info("Initial backfill for {}", puuid);

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
            Optional<Long> lastUpdateTime = dynamoDbService.getPlayerLastRecentMatchUpdate(region, name, tag);

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
        List<Map<String, AttributeValue>> cachedMMR = dynamoDbService.getMMRHistory(puuid);

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
        MatchResponses.MatchHistoryResponse result =
                responseFormatter.formatCachedMatches(cachedMatches, cachedMMR);

        MatchResponses.Cursor cursor = (responseLastKey != null && !responseLastKey.isEmpty())
                ? convertLastKey(responseLastKey)
                : null;

        return new MatchResponses.MatchHistoryResponse(
                result.status(),
                result.cached(),
                result.data(),
                cursor
        );
    }

    private MatchResponses.Cursor convertLastKey(Map<String, AttributeValue> lastKey) {
        if (lastKey == null || lastKey.isEmpty()) return null;

        String pk = lastKey.containsKey("PK") ? lastKey.get("PK").s() : null;
        String sk = lastKey.containsKey("SK") ? lastKey.get("SK").s() : null;
        String gsi1Pk = lastKey.containsKey("GSI1PK") ? lastKey.get("GSI1PK").s() : null;
        Long gsi1Sk = lastKey.containsKey("GSI1SK") ? Long.parseLong(lastKey.get("GSI1SK").n()) : null;

        return new MatchResponses.Cursor(pk, sk, gsi1Pk, gsi1Sk);
    }

    private Map<String, AttributeValue> parseLastKey(String json) {
        if (json == null || json.isBlank()) return null;

        try {
            String decoded = URLDecoder.decode(json, StandardCharsets.UTF_8);

            MatchResponses.Cursor cursor = new ObjectMapper().readValue(
                    decoded,
                    MatchResponses.Cursor.class
            );

            Map<String, AttributeValue> result = new HashMap<>();

            if (cursor.pk() != null && !cursor.pk().isBlank()) {
                result.put("PK", AttributeValue.fromS(cursor.pk()));
            }
            if (cursor.sk() != null && !cursor.sk().isBlank()) {
                result.put("SK", AttributeValue.fromS(cursor.sk()));
            }
            if (cursor.gsi1Pk() != null && !cursor.gsi1Pk().isBlank()) {
                result.put("GSI1PK", AttributeValue.fromS(cursor.gsi1Pk()));
            }
            if (cursor.gsi1Sk() != null) {
                result.put("GSI1SK", AttributeValue.fromN(String.valueOf(cursor.gsi1Sk())));
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
    public Object getMatchDetails(String matchId) {
        // Try cache first
        Optional<Map<String, AttributeValue>> cached = dynamoDbService.getMatchById(matchId);
        if (cached.isPresent()) {
            LOG.debug("Returning cached match details for {}", matchId);
            List<Map<String, AttributeValue>> players = dynamoDbService.getMatchPlayers(matchId);
            return responseFormatter.formatCachedMatchDetails(cached.get(), players);
        }

        // Cache miss - fetch from API
        LOG.info("Cache miss for match {}. Fetching from API...", matchId);
        return apiClient.getMatchById(matchId, apiKey);
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
            StoredMatchesResponse response;
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

            List<StoredMatchesResponse.StoredMatch> matches =
                    response != null && response.data() != null ? response.data() : Collections.emptyList();

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

            for (StoredMatchesResponse.StoredMatch match : matches) {
                StoredMatchesResponse.Meta meta = match != null ? match.meta() : null;

                if (meta == null) {
                    skipped++;
                    continue;
                }

                Object tsObj = meta.startedAt();
                String matchId = Objects.toString(meta.id(), "");

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
        List<Map<String, Object>> entries = (List<Map<String, Object>>) rawEntries;

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
            return Instant.parse(startedAt.toString()).getEpochSecond();
        } catch (Exception e) {
            LOG.debug("Failed to parse started_at timestamp: {}", startedAt);
            return -1L;
        }
    }

    private String extractStoredMatchId(StoredMatchesResponse.StoredMatch match) {
        if (match == null) {
            return "";
        }

        StoredMatchesResponse.Meta meta = match.meta();
        if (meta == null) {
            return "";
        }

        return Objects.toString(meta.id(), "");
    }

}

