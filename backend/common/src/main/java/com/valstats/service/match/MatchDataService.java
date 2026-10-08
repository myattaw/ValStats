package com.valstats.service.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.valstats.client.ValorantApiClient;
import com.valstats.client.HenrikApiRequestQueue;
import com.valstats.model.response.MatchResponses;
import com.valstats.model.stored.StoredMatchesResponse;
import com.valstats.model.queue.RefreshJob;
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
    private static final int RECENT_MATCH_PAGE_SIZE = 20;
    // Keep a history page small enough that its match and derived-insight writes
    // reliably finish inside the worker Lambda timeout. Each completed page is
    // checkpointed and continued by a separate SQS message.
    private static final int BULK_MATCH_PAGE_SIZE = 500;
    private static final int MAX_BULK_MATCH_PAGES = 100; // 50,000 matches
    private static final int MAX_STORED_MATCH_PAGES = 1_000; // legacy local sync safety cap

    private final DynamoDbService dynamoDbService;
    private final ValorantApiClient apiClient;
    private final MatchResponseFormatter responseFormatter;
    private final MatchProcessor matchProcessor;
    private final HenrikApiRequestQueue apiRequestQueue;
    private final FullMatchCache fullMatchCache;

    public MatchDataService(
            DynamoDbService dynamoDbService,
            ValorantApiClient apiClient,
            MatchResponseFormatter responseFormatter,
            MatchProcessor matchProcessor,
            HenrikApiRequestQueue apiRequestQueue,
            FullMatchCache fullMatchCache
    ) {
        this.dynamoDbService = dynamoDbService;
        this.apiClient = apiClient;
        this.responseFormatter = responseFormatter;
        this.matchProcessor = matchProcessor;
        this.apiRequestQueue = apiRequestQueue;
        this.fullMatchCache = fullMatchCache;
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
        return getPlayerMatches(puuid, region, name, tag, size, lastKeyJson, act, mode, false);
    }

    public MatchResponses.MatchHistoryResponse getPlayerMatches(
            String puuid, String region, String name, String tag, int size,
            String lastKeyJson, String act, String mode, boolean bootstrap) {
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
        boolean seasonQuery = act != null && !"all".equalsIgnoreCase(act);
        do {
            int remaining = size - cachedMatches.size();
            // A sparse mode must not degrade into one database request per
            // nonmatching game when only one displayed row is still missing.
            int querySize = "all".equals(normalizedMode) ? remaining : Math.max(100, remaining);
            QueryResponse response = seasonQuery
                    ? dynamoDbService.getMatchesBySeasonPaginated(puuid, act, querySize, queryCursor)
                    : dynamoDbService.getMatchesFromGSI(puuid, querySize, queryCursor);

            var matching = response.items().stream()
                    .filter(item -> "all".equals(normalizedMode)
                            || (item.containsKey("mode") && normalizedMode.equals(item.get("mode").s())))
                    .toList();
            cachedMatches.addAll(matching.subList(0, Math.min(remaining, matching.size())));
            queryCursor = response.lastEvaluatedKey();
            if (matching.size() > remaining) {
                var last = cachedMatches.get(cachedMatches.size() - 1);
                queryCursor = new HashMap<>();
                for (String key : seasonQuery ? List.of("PK", "SK") : List.of("PK", "SK", "GSI1PK", "GSI1SK")) {
                    queryCursor.put(key, last.get(key));
                }
            }
        } while (cachedMatches.size() < size && queryCursor != null && !queryCursor.isEmpty());
        responseLastKey = queryCursor;

        // A new account has no local history yet. Return one compact stored page
        // directly; full scoreboards and historical aggregation run separately.
        if (bootstrap && cachedMatches.size() < Math.min(size, RECENT_MATCH_PAGE_SIZE) && exclusiveStartKey == null
                && (act == null || "all".equalsIgnoreCase(act))) {
            StoredMatchesResponse stored = apiRequestQueue.execute(
                    "initial stored matches for " + name + "#" + tag,
                    () -> apiClient.getStoredMatches(region, name, tag,
                            Math.min(size, RECENT_MATCH_PAGE_SIZE), 1,
                            "all".equals(normalizedMode) ? null : normalizedMode));
            if (stored != null && stored.status() == 200 && stored.data() != null) {
                Set<String> existingIds = new HashSet<>();
                cachedMatches.forEach(row -> existingIds.add(row.get("matchId").s()));
                for (var match : stored.data()) {
                    var row = matchProcessor.storedMatchItem(match, puuid);
                    if (row != null && existingIds.add(row.get("matchId").s())) cachedMatches.add(row);
                }
                cachedMatches.sort(Comparator.comparingLong(
                        (Map<String, AttributeValue> row) -> Long.parseLong(row.get("gameStart").n())).reversed());
                if (cachedMatches.size() > size) cachedMatches = new ArrayList<>(cachedMatches.subList(0, size));
            }
        }

        // =========================
        // ENSURE MMR IS FRESH
        // =========================
        List<Map<String, AttributeValue>> cachedMMR = dynamoDbService.getMMRHistory(puuid);

        // =========================
        // FORMAT RESPONSE
        // =========================
        MatchResponses.MatchHistoryResponse result =
                responseFormatter.formatCachedMatches(cachedMatches, cachedMMR,
                        fullMatchCache.getPlacements(cachedMatches.stream()
                                .map(row -> row.get("matchId").s()).toList(), puuid));

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

    /** Performs slow external refresh work. Never call this from the cached read path. */
    public boolean refreshPlayerMatches(String puuid, String region, String name, String tag) {
        if (!needsRefresh(puuid, region, name, tag)) {
            LOG.debug("Skipping sync (cooldown active) for {}#{}", name, tag);
            return false;
        }
        QueryResponse check = dynamoDbService.getMatchesFromGSI(puuid, 50, null);
        boolean hasAnyMatches = !check.items().isEmpty();
        boolean needsModeBackfill = hasAnyMatches && check.items().stream()
                .anyMatch(item -> !item.containsKey("mode"));
        boolean needsDamageBackfill = hasAnyMatches && check.items().stream().anyMatch(item ->
                !item.containsKey("damage_made") || !item.containsKey("rounds_played")
                        || !item.containsKey("adr") || !item.containsKey("damageSchemaVersion"));
        boolean initialBackfill = !hasAnyMatches || needsModeBackfill || needsDamageBackfill;

        boolean succeeded = syncStoredMatches(
                puuid, region, name, tag, BULK_MATCH_PAGE_SIZE, initialBackfill);
        if (!succeeded) return false;
        // Preserve the old history watermark until incremental discovery finishes.
        refreshRecentMatches(puuid, region, name, tag);

        dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag);
        // Match history and MMR history are separate Henrik datasets. Refresh MMR
        // after every successful match sync so newly discovered matches receive
        // their RR changes even when older MMR records already exist in DynamoDB.
        try {
            Map<String, Object> mmrHistory = apiRequestQueue.execute(
                    "MMR history for " + name + "#" + tag,
                    () -> apiClient.getMMRHistory(region, name, tag));
            cacheMMRHistory(puuid, mmrHistory);
        } catch (Exception e) {
            LOG.warn("MMR refresh failed for {}#{}: {}", name, tag, e.getMessage());
        }
        return true;
    }

    /** Processes a bounded, resumable SQS backfill job. */
    public BackfillResult processBackfill(RefreshJob job) {
        String syncScope = syncScope(job);
        dynamoDbService.updateBackfillState(job.puuid(), syncScope, "RUNNING", Math.max(1, job.page()));
        if (job.kind().startsWith("RECENT_VIEW#")) {
            String mode = normalizeMode(job.kind().substring("RECENT_VIEW#".length()));
            RecentBatch batch = refreshRecentMatches(job.puuid(), job.region(), job.name(), job.tag(), mode, false, 10);
            dynamoDbService.putRecentMatchSnapshot(job.puuid(), mode, batch.rows());
            dynamoDbService.updatePlayerLastRecentMatchUpdate(job.region(), job.name(), job.tag(), mode);
            dynamoDbService.updateBackfillState(job.puuid(), syncScope, "COMPLETE", 1);
            return new BackfillResult(true, 1, false);
        }
        if ("RECENT".equalsIgnoreCase(job.kind())) {
            refreshRecentMatches(job.puuid(), job.region(), job.name(), job.tag());
            dynamoDbService.updatePlayerLastRecentMatchUpdate(job.region(), job.name(), job.tag());
            refreshMmrHistory(job.puuid(), job.region(), job.name(), job.tag());
            dynamoDbService.updateBackfillState(job.puuid(), syncScope, "COMPLETE", 1);
            // The worker always follows this bounded recent fetch with stored-history backfill.
            return new BackfillResult(true, 1, false);
        }
        int startPage = Math.max(1, job.page());
        int pageBudget = Math.max(1, Math.min(job.pagesPerJob(), 20));
        int requestSize = BULK_MATCH_PAGE_SIZE;
        int maximumPages = MAX_BULK_MATCH_PAGES;
        boolean targeted = "ACT".equalsIgnoreCase(job.kind()) && job.targetSeasonId() != null
                && !job.targetSeasonId().isBlank();
        boolean targetSeen = job.targetSeen();
        int page = startPage;
        boolean complete = false;

        for (int fetched = 0; fetched < pageBudget && page <= maximumPages; fetched++, page++) {
            int requestedPage = page;
            StoredMatchesResponse response;
            if ("HISTORY".equalsIgnoreCase(job.kind())) {
                response = apiRequestQueue.executeLowPriority(
                        "stored matches page " + requestedPage + " for " + job.name() + "#" + job.tag(),
                        () -> apiClient.getStoredMatches(job.region(), job.name(), job.tag(),
                                requestSize, requestedPage, null));
            } else {
                response = apiRequestQueue.execute(
                        "stored matches page " + requestedPage + " for " + job.name() + "#" + job.tag(),
                        () -> apiClient.getStoredMatches(job.region(), job.name(), job.tag(),
                                requestSize, requestedPage, null));
            }
            List<StoredMatchesResponse.StoredMatch> matches = response != null && response.data() != null
                    ? response.data() : List.of();
            if (matches.isEmpty()) {
                complete = true;
                break;
            }

            boolean pageContainsTarget = false;
            for (StoredMatchesResponse.StoredMatch match : matches) {
                if (targeted && match != null && match.meta() != null && match.meta().season() != null
                        && job.targetSeasonId().equals(match.meta().season().id())) {
                    pageContainsTarget = true;
                    targetSeen = true;
                }
            }
            matchProcessor.processStoredMatchBatch(matches, job.puuid(), requestedPage,
                    "HISTORY".equalsIgnoreCase(job.kind()));
            if ("HISTORY".equalsIgnoreCase(job.kind()) && requestedPage == 1) {
                refreshMmrHistory(job.puuid(), job.region(), job.name(), job.tag());
            }

            if (targeted && targetSeen && !pageContainsTarget) {
                complete = true;
                page++;
                break;
            }
            if (response.results() != null && response.results().total() > 0
                    && (long) requestedPage * requestSize >= response.results().total()) {
                complete = true;
                page++;
                break;
            }
            if (matches.size() < requestSize) {
                complete = true;
                page++;
                break;
            }
        }

        if (page > maximumPages) complete = true;
        dynamoDbService.updateBackfillState(job.puuid(), syncScope,
                complete ? "COMPLETE" : "QUEUED", page);
        return new BackfillResult(complete, page, targetSeen);
    }

    private String syncScope(RefreshJob job) {
        if (job.kind().startsWith("RECENT_VIEW#")) return job.kind();
        return "ACT".equalsIgnoreCase(job.kind())
                ? "ACT#" + job.targetSeasonId()
                : job.kind().toUpperCase(Locale.ROOT);
    }

    private record RecentBatch(List<Map<String, AttributeValue>> rows, Map<String, Map<String, Object>> details) {}

    private RecentBatch refreshRecentMatches(String puuid, String region, String name, String tag) {
        return refreshRecentMatches(puuid, region, name, tag, "all", false);
    }

    private RecentBatch refreshRecentMatches(String puuid, String region, String name, String tag, String mode, boolean interactive) {
        return refreshRecentMatches(puuid, region, name, tag, mode, interactive, interactive ? 10 : RECENT_MATCH_PAGE_SIZE);
    }

    private RecentBatch refreshRecentMatches(String puuid, String region, String name, String tag, String mode,
                                             boolean interactive, int limit) {
        List<Object> matches = new ArrayList<>();
        // Henrik caps each matches response at 10, including requests with size=20.
        // v4 exposes start pagination; v3 does not.
        for (int start = 0; start < limit; start += 10) {
            int offset = start;
            String operation = "recent matches offset " + offset + " for " + name + "#" + tag;
            java.util.concurrent.Callable<Map<String, Object>> request = () -> "all".equals(mode)
                            ? apiClient.getRecentMatches(region, name, tag, 10, offset)
                            : apiClient.getRecentMatchesByMode(region, name, tag, 10, offset, mode);
            Map<String, Object> response = interactive
                    ? apiRequestQueue.executeOnce(operation, request) : apiRequestQueue.execute(operation, request);
            if (response == null || !(response.get("status") instanceof Number status) || status.intValue() != 200
                    || !(response.get("data") instanceof List<?> page)) {
                throw new IllegalStateException("Henrik returned an invalid recent-match response");
            }
            matches.addAll(page);
            if (page.size() < 10) break;
        }
        Set<String> processed = new HashSet<>();
        List<Map<String, AttributeValue>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> details = new LinkedHashMap<>();
        for (Object value : matches) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> fields = new HashMap<>();
            raw.forEach((key, field) -> fields.put(key.toString(), field));
            Map<String, Object> match = RecentMatchAdapter.adapt(fields);
            String id = match.get("metadata") instanceof Map<?, ?> metadata
                    ? Objects.toString(metadata.get("matchid"), "") : "";
            if (id.isBlank() || !processed.add(id) || !FullMatchCache.isComplete(id, match)) continue;
            // Detail caching must not depend on optional summary metadata such as season_id.
            fullMatchCache.putIfChanged(id, match);
            details.put(id, match);
        }
        // Cache every scoreboard before performing the more expensive derived
        // summary writes, so progress is visible across the entire recent slice.
        for (var match : details.values()) {
            RecentMatchMapper.summary(match, puuid).ifPresent(summary -> {
                // A later stored-history response supplies missing season identity. Avoid duplicate keys.
                if (!"unknown".equals(summary.meta().season().id())) {
                    matchProcessor.processRecentMatchSummary(summary, puuid);
                }
                var row = matchProcessor.storedMatchItem(summary, puuid);
                if (row != null) rows.add(row);
            });
        }
        LOG.info("Recent matches: requested={}, returned={}, cached={}, summaries={}",
                limit, matches.size(), details.size(), rows.size());
        return new RecentBatch(rows, details);
    }

    /** Return the new recent slice directly; do not wait for history, MMR, or GSI propagation. */
    public Map<String, Object> getRecentMatchHistory(String puuid, String region, String name, String tag,
                                                    String mode, boolean refresh) {
        MatchResponses.MatchHistoryResponse history;
        Map<String, Object> preloaded;
        if (refresh) {
            String normalizedMode = normalizeMode(mode);
            RecentBatch batch = refreshRecentMatches(puuid, region, name, tag, normalizedMode, true);
            var rows = batch.rows().stream().filter(row -> "all".equals(normalizedMode)
                    || normalizedMode.equals(row.get("mode").s())).toList();
            Map<String, MatchResponses.MatchPlacement> placements = new HashMap<>();
            batch.details().forEach((id, detail) -> {
                var placement = FullMatchCache.placements(detail).get(puuid);
                if (placement != null) placements.put(id, placement);
            });
            history = responseFormatter.formatCachedMatches(rows, dynamoDbService.getMMRHistory(puuid), placements);
            preloaded = projectDetails(history.data().stream().map(MatchResponses.MatchSummary::id).toList(),
                    id -> Optional.ofNullable(batch.details().get(id)));
            dynamoDbService.putRecentMatchSnapshot(puuid, normalizedMode, rows);
            dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag, normalizedMode);
        } else {
            String normalizedMode = normalizeMode(mode);
            var snapshot = dynamoDbService.getRecentMatchSnapshot(puuid, normalizedMode);
            history = snapshot.map(rows -> responseFormatter.formatCachedMatches(rows,
                            dynamoDbService.getMMRHistory(puuid), fullMatchCache.getPlacements(
                                    rows.stream().map(row -> row.get("matchId").s()).toList(), puuid)))
                    .orElseGet(() -> getPlayerMatches(puuid, region, name, tag, 10, null, "all", normalizedMode));
            // The browser preloads scoreboards separately; keep every poll a small cached read.
            preloaded = Map.of("data", Map.of(), "deferred", List.of());
        }
        return Map.of("status", 200, "data", history.data(), "details", preloaded.get("data"),
                "deferred", preloaded.get("deferred"), "updated", refresh);
    }

    /** Records a terminal worker failure so clients do not display a permanent refresh state. */
    public void markBackfillFailed(RefreshJob job) {
        dynamoDbService.updateBackfillState(
                job.puuid(), syncScope(job), "FAILED", Math.max(1, job.page()));
    }

    /** Makes a continuation visible to status clients before it reaches SQS. */
    public void markBackfillQueued(RefreshJob job) {
        dynamoDbService.updateBackfillState(
                job.puuid(), syncScope(job), "QUEUED", Math.max(1, job.page()));
    }

    private void refreshMmrHistory(String puuid, String region, String name, String tag) {
        try {
            Map<String, Object> mmrHistory = apiRequestQueue.execute(
                    "MMR history for " + name + "#" + tag,
                    () -> apiClient.getMMRHistory(region, name, tag));
            cacheMMRHistory(puuid, mmrHistory);
        } catch (Exception e) {
            LOG.warn("MMR refresh failed for {}#{}: {}", name, tag, e.getMessage());
        }
    }

    public record BackfillResult(boolean complete, int nextPage, boolean targetSeen) {}

    public boolean needsRefresh(String puuid, String region, String name, String tag) {
        QueryResponse check = dynamoDbService.getMatchesFromGSI(puuid, 50, null);
        if (check.items().isEmpty()) return true;
        boolean incompleteMetadata = check.items().stream().anyMatch(item ->
                !item.containsKey("mode") || !item.containsKey("damage_made")
                        || !item.containsKey("rounds_played") || !item.containsKey("adr")
                        || !item.containsKey("damageSchemaVersion"));
        if (incompleteMetadata) return true;
        long lastUpdate = dynamoDbService.getPlayerLastRecentMatchUpdate(region, name, tag).orElse(0L);
        return System.currentTimeMillis() / 1000 - lastUpdate > 300;
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

            JsonNode cursor = new ObjectMapper().readTree(decoded);

            Map<String, AttributeValue> result = new HashMap<>();

            String pk = textValue(cursor, "PK");
            String sk = textValue(cursor, "SK");
            String gsi1Pk = textValue(cursor, "GSI1PK");
            JsonNode gsi1Sk = cursor.get("GSI1SK");

            if (pk != null && !pk.isBlank()) {
                result.put("PK", AttributeValue.fromS(pk));
            }
            if (sk != null && !sk.isBlank()) {
                result.put("SK", AttributeValue.fromS(sk));
            }
            if (gsi1Pk != null && !gsi1Pk.isBlank()) {
                result.put("GSI1PK", AttributeValue.fromS(gsi1Pk));
            }
            if (gsi1Sk != null && !gsi1Sk.isNull()) {
                result.put("GSI1SK", AttributeValue.fromN(gsi1Sk.asText()));
            }

            return result;

        } catch (Exception e) {
            LOG.error("Failed to parse lastKey: {}", json, e);
            return null;
        }
    }

    private static String textValue(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Get full match details by match ID.
     * Strategy: Check cache first, then API if needed
     */
    public Object getMatchDetails(String matchId) {
        var cached = fullMatchCache.get(matchId);
        if (cached.isPresent()) {
            fullMatchCache.persistPlacements(matchId, cached.get());
            return Map.of("status", 200, "cached", true, "data", cached.get());
        }
        LOG.info("Fetching full match details for {}", matchId);
        Map<String, Object> response = apiRequestQueue.execute(
                "match details " + matchId,
                () -> apiClient.getMatchById(matchId));
        if (response != null && response.get("status") instanceof Number status && status.intValue() == 200
                && response.get("data") instanceof Map<?, ?> raw) {
            Map<String, Object> data = new HashMap<>();
            raw.forEach((key, value) -> data.put(key.toString(), value));
            fullMatchCache.put(matchId, data);
        }
        return response;
    }

    public static String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "all"
                : mode.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    public boolean needsRefresh(String puuid, String region, String name, String tag, String mode) {
        String normalizedMode = normalizeMode(mode);
        long lastUpdate = dynamoDbService.getPlayerLastRecentMatchUpdate(region, name, tag, normalizedMode).orElse(0L);
        return Instant.now().getEpochSecond() - lastUpdate > 300;
    }

    /** Cache-only preloading must never fan out into Henrik calls for older history. */
    public Map<String, Object> getCachedMatchDetails(List<String> ids) {
        return projectDetails(ids, id -> {
            var cached = fullMatchCache.get(id);
            cached.ifPresent(data -> fullMatchCache.persistPlacements(id, data));
            return cached;
        });
    }

    private Map<String, Object> projectDetails(List<String> ids,
            java.util.function.Function<String, Optional<Map<String, Object>>> source) {
        Map<String, Object> matches = new LinkedHashMap<>();
        List<String> deferred = new ArrayList<>();
        int bytes = 0;
        ObjectMapper json = new ObjectMapper();
        for (String id : ids.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().limit(20).toList()) {
            var cached = source.apply(id);
            if (cached.isEmpty()) continue;
            try {
                var projected = MatchDetailProjection.forDisplay(cached.get());
                int size = json.writeValueAsBytes(projected).length;
                // Leave room for JSON escaping in the Lambda proxy envelope.
                if (size > 2_000_000) continue;
                if (bytes + size > 2_000_000) {
                    deferred.add(id);
                    continue;
                }
                matches.put(id, projected);
                bytes += size;
            } catch (java.io.IOException e) {
                LOG.warn("Cannot serialize cached match {}", id, e);
            }
        }
        return Map.of("status", 200, "data", matches, "deferred", deferred);
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

