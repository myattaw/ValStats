package com.valstats.service.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valstats.client.ValorantApiClient;
import com.valstats.client.HenrikApiRequestQueue;
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
    private final HenrikApiRequestQueue apiRequestQueue;

    public MatchDataService(
            DynamoDbService dynamoDbService,
            ValorantApiClient apiClient,
            MatchResponseFormatter responseFormatter,
            MatchProcessor matchProcessor,
            HenrikApiRequestQueue apiRequestQueue
    ) {
        this.dynamoDbService = dynamoDbService;
        this.apiClient = apiClient;
        this.responseFormatter = responseFormatter;
        this.matchProcessor = matchProcessor;
        this.apiRequestQueue = apiRequestQueue;
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
            String act,
            String mode
    ) {
        QueryResponse check = dynamoDbService.getMatchesFromGSI(puuid, 50, null);

        boolean didSync = false;

        // =========================
        // INITIAL BACKFILL / TOP-UP
        // =========================
        boolean hasAnyMatches = !check.items().isEmpty();
        boolean needsModeBackfill = hasAnyMatches && check.items().stream().anyMatch(item -> !item.containsKey("mode"));
        boolean needsDamageBackfill = hasAnyMatches && check.items().stream().anyMatch(item ->
                !item.containsKey("damage_made") || !item.containsKey("rounds_played")
                        || !item.containsKey("adr")
                        || "0".equals(item.get("damage_made").n()));

        if (!hasAnyMatches || needsModeBackfill || needsDamageBackfill) {
            String backfillType = !hasAnyMatches ? "Initial"
                    : needsDamageBackfill ? "Damage metadata" : "Game-mode metadata";
            LOG.info("{} backfill for {}", backfillType, puuid);

            boolean syncSucceeded = syncStoredMatches(
                    puuid,
                    region,
                    name,
                    tag,
                    STORED_MATCH_PAGE_SIZE,
                    true
            );

            if (syncSucceeded) {
                dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag);
                didSync = true;
            }

        } else {
            // =========================
            // INCREMENTAL SYNC
            // =========================
            Optional<Long> lastUpdateTime = dynamoDbService.getPlayerLastRecentMatchUpdate(region, name, tag);

            long now = System.currentTimeMillis() / 1000;
            long lastUpdate = lastUpdateTime.orElse(0L);

            if (now - lastUpdate > 300) {
                LOG.info("Running stored-match sync for {}#{}", name, tag);

                boolean syncSucceeded = syncStoredMatches(
                        puuid,
                        region,
                        name,
                        tag,
                        STORED_MATCH_PAGE_SIZE,
                        false
                );

                if (syncSucceeded) {
                    dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag);
                    didSync = true;
                }
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

        cachedMatches = new ArrayList<>();
        String normalizedMode = mode == null ? "all"
                : mode.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        Map<String, AttributeValue> queryCursor = exclusiveStartKey;
        do {
            int remaining = size - cachedMatches.size();
            QueryResponse response = act != null && !"all".equalsIgnoreCase(act)
                    ? dynamoDbService.getMatchesBySeasonPaginated(puuid, act, remaining, queryCursor)
                    : dynamoDbService.getMatchesFromGSI(puuid, remaining, queryCursor);

            cachedMatches.addAll(response.items().stream()
                    .filter(item -> "all".equals(normalizedMode)
                            || (item.containsKey("mode") && normalizedMode.equals(item.get("mode").s())))
                    .toList());
            queryCursor = response.lastEvaluatedKey();
        } while (cachedMatches.size() < size && queryCursor != null && !queryCursor.isEmpty());
        responseLastKey = queryCursor;

        // =========================
        // ENSURE MMR IS FRESH
        // =========================
        List<Map<String, AttributeValue>> cachedMMR = dynamoDbService.getMMRHistory(puuid);

        if (!cachedMatches.isEmpty() && cachedMMR.isEmpty() && !didSync) {
            LOG.info("Refreshing MMR for {}", puuid);

            try {
                Map<String, Object> mmrHistory = apiRequestQueue.execute(
                        "MMR history for " + name + "#" + tag,
                        () -> apiClient.getMMRHistory(region, name, tag));

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
        // Stored-match records only contain summary statistics. The complete
        // Henrik payload is required here for ranks, economy and round events.
        // Do not return the lossy cached summary from this detail endpoint.
        LOG.info("Fetching full match details for {}", matchId);
        return apiRequestQueue.execute(
                "match details " + matchId,
                () -> apiClient.getMatchById(matchId));
    }

    public boolean syncStoredMatches(
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
        boolean requestFailed = false;

        while (!foundExisting && page <= MAX_STORED_MATCH_PAGES) {
            StoredMatchesResponse response;
            try {
                int requestedPage = page;
                response = apiRequestQueue.execute(
                        "stored matches page " + requestedPage + " for " + name + "#" + tag,
                        () -> apiClient.getStoredMatches(
                                region,
                                name,
                                tag,
                                batchSize,
                                requestedPage,
                                null
                        ));
            } catch (Exception e) {
                LOG.warn("Stored-match sync deferred after request failure page={} size={} for {}#{}: {}",
                        page, batchSize, name, tag, e.getMessage());
                requestFailed = true;
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

        if (requestFailed) {
            LOG.warn("Stored-match sync incomplete for {}#{}; cached data remains available "
                            + "(initialBackfill={}, processed={}, skipped={}, pages={})",
                    name, tag, isInitialBackfill, processed, skipped, pagesFetched);
        } else {
            LOG.info("Stored-match sync complete for {}#{} (initialBackfill={}, processed={}, skipped={}, pages={})",
                    name, tag, isInitialBackfill, processed, skipped, pagesFetched);
        }
        return !requestFailed && pagesFetched > 0;
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

