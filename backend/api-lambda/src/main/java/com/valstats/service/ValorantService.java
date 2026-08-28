package com.valstats.service;

import com.valstats.client.ValorantApiClient;
import com.valstats.client.HenrikApiRequestQueue;
import com.valstats.model.stored.StoredMatchesResponse;
import com.valstats.model.queue.RefreshJob;
import com.valstats.service.queue.RefreshQueuePublisher;
import com.valstats.service.match.MatchDataService;
import com.valstats.service.SeasonNames;
import com.valstats.service.player.PlayerCacheService;
import com.valstats.service.player.PlayerStatsService;
import jakarta.inject.Singleton;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.*;
import java.time.Instant;

/**
 * Main service for Valorant API operations - READ ONLY.
 * This is a facade that delegates to specialized services:
 * - MatchDataService: handles match retrieval and caching
 * - PlayerStatsService: handles player statistics
 * - PlayerCacheService: handles player profile caching
 * - ValorantApiClient: calls HenrikDev API
 */
@Singleton
public class ValorantService {

    private static final Logger LOG = LoggerFactory.getLogger(ValorantService.class);
    private static final long NAME_HISTORY_SAMPLE_SECONDS = 7L * 24 * 60 * 60;
    private static final int NAME_HISTORY_CHECKPOINTS_PER_REQUEST = 5;
    private static final int ACT_DISCOVERY_MATCH_LIMIT = 50_000;

    private final MatchDataService matchDataService;
    private final PlayerStatsService playerStatsService;
    private final PlayerCacheService playerCacheService;
    private final ValorantApiClient apiClient;
    private final DynamoDbService dynamoDbService;
    private final HenrikApiRequestQueue apiRequestQueue;
    private final RefreshQueuePublisher refreshQueuePublisher;

    private static final Map<String, String> SEASON_MAP = new LinkedHashMap<>();
    private static final Map<String, String> SEASON_TO_HENRIK = new HashMap<>();

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
            DynamoDbService dynamoDbService,
            HenrikApiRequestQueue apiRequestQueue,
            RefreshQueuePublisher refreshQueuePublisher
    ) {
        this.matchDataService = matchDataService;
        this.playerStatsService = playerStatsService;
        this.playerCacheService = playerCacheService;
        this.apiClient = apiClient;
        this.dynamoDbService = dynamoDbService;
        this.apiRequestQueue = apiRequestQueue;
        this.refreshQueuePublisher = refreshQueuePublisher;
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
            String act,
            String mode
    ) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        Object matches = matchDataService.getPlayerMatches(
                puuid,
                region,
                name,
                tag,
                size,
                lastKey,
                act,
                mode
        );
        return matches;
    }

    public Map<String, Object> refreshMatches(String region, String name, String tag) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) return errorResponse("Player not found");
        if (!matchDataService.needsRefresh(puuid, region, name, tag)) {
            return Map.of("status", 200, "data", Map.of("updated", false, "refreshing", false));
        }
        if (refreshQueuePublisher.isConfigured()) {
            if (!dynamoDbService.tryQueueBackfill(puuid, "RECENT")) {
                return Map.of("status", 202, "data", Map.of(
                        "updated", false, "refreshing", true, "queued", false));
            }
            refreshQueuePublisher.enqueue(RefreshJob.matches(puuid, region, name, tag));
            return Map.of("status", 202, "data", Map.of(
                    "updated", false,
                    "refreshing", true,
                    "queued", true));
        }

        // Local development remains usable without SQS configuration.
        boolean updated = matchDataService.refreshPlayerMatches(puuid, region, name, tag);
        return Map.of("status", 200, "data", Map.of("updated", updated, "queued", false));
    }

    public Map<String, Object> getMatchRefreshStatus(String region, String name, String tag) {
        Optional<String> puuid = playerCacheService.getPuuidByNameTag(name, tag);
        if (puuid.isEmpty()) {
            return Map.of("status", 200, "data", Map.of(
                    "refreshRequired", true, "refreshing", false, "backfillStatus", "NOT_STARTED"));
        }
        boolean required = matchDataService.needsRefresh(puuid.get(), region, name, tag);
        Map<String, Object> state = dynamoDbService.getBackfillState(puuid.get(), "RECENT")
                .orElse(Map.of("status", "NOT_STARTED", "refreshing", false,
                        "nextPage", 1L, "updatedAt", ""));
        Map<String, Object> data = new HashMap<>(state);
        data.put("backfillStatus", state.get("status"));
        data.put("refreshRequired", required);
        return Map.of("status", 200, "data", data);
    }

    public Map<String, Object> refreshActMatches(
            String region, String name, String tag, String seasonId) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) return errorResponse("Player not found");
        if (seasonId == null || seasonId.isBlank() || "all".equalsIgnoreCase(seasonId)) {
            return errorResponse("A specific season is required");
        }
        if (!refreshQueuePublisher.isConfigured()) {
            return errorResponse("Background refresh is not configured");
        }
        String scope = "ACT#" + seasonId;
        Optional<Map<String, Object>> existingState = dynamoDbService.getBackfillState(puuid, scope);
        if (existingState.filter(state -> "COMPLETE".equals(state.get("status"))).isPresent()) {
            return Map.of("status", 200, "data", Map.of(
                    "refreshing", false, "seasonId", seasonId, "updated", false));
        }
        if (!dynamoDbService.tryQueueBackfill(puuid, scope)) {
            return Map.of("status", 202, "data", Map.of(
                    "refreshing", true, "seasonId", seasonId, "queued", false));
        }
        refreshQueuePublisher.enqueue(RefreshJob.act(puuid, region, name, tag, seasonId));
        return Map.of("status", 202, "data", Map.of(
                "refreshing", true, "seasonId", seasonId, "priority", "high"));
    }

    public Map<String, Object> getBackfillStatus(String region, String name, String tag, String seasonId) {
        Optional<String> puuid = playerCacheService.getPuuidByNameTag(name, tag);
        if (puuid.isEmpty()) return Map.of("status", 200, "data", Map.of("state", "NOT_STARTED"));
        String scope = seasonId == null || seasonId.isBlank() || "all".equalsIgnoreCase(seasonId)
                ? "HISTORY" : "ACT#" + seasonId;
        Map<String, Object> state = dynamoDbService.getBackfillState(puuid.get(), scope)
                .orElse(Map.of("status", "NOT_STARTED", "nextPage", 1L, "updatedAt", ""));
        return Map.of("status", 200, "data", state);
    }

    /**
     * Get account details from HenrikDev API
     */
    public Map<String, Object> getAccountDetails(String name, String tag) {
        Optional<Map<String, Object>> cached = playerCacheService.getCachedAccount(name, tag);
        if (cached.isPresent()) return Map.of("status", 200, "data", cached.get());
        return refreshAccountDetails(name, tag, "na");
    }

    private Map<String, Object> refreshAccountDetails(String name, String tag, String region) {
        Map<String, Object> response = apiRequestQueue.execute(
                "account for " + name + "#" + tag,
                () -> apiClient.getAccount(name, tag));
        if (response != null && response.get("data") instanceof Map<?, ?> data) {
            playerCacheService.storeAccountProfile(data, name, tag, region);
        }
        return response;
    }

    /**
     * Get full match details by ID.
     * Delegates to MatchDataService which handles caching strategy.
     */
    public Object getMatchById(String matchId) {
        Object response = matchDataService.getMatchDetails(matchId);
        recordMatchPlayerNames(response, null);
        return response;
    }

    private boolean recordMatchPlayerNames(Object response, String requiredPuuid) {
        recordSeasonMetadata(response);
        if (!(response instanceof Map<?, ?> root) || !(root.get("data") instanceof Map<?, ?> data)) return false;
        if (!(data.get("players") instanceof Map<?, ?> players) || !(players.get("all_players") instanceof List<?> allPlayers)) return false;

        long observedAt = Instant.now().getEpochSecond();
        if (data.get("metadata") instanceof Map<?, ?> metadata && metadata.get("game_start") instanceof Number gameStart) {
            observedAt = gameStart.longValue();
            if (observedAt > 10_000_000_000L) observedAt /= 1000;
        }

        boolean requiredPlayerFound = requiredPuuid == null;
        for (Object value : allPlayers) {
            if (!(value instanceof Map<?, ?> player)) continue;
            String playerPuuid = Objects.toString(player.get("puuid"), "");
            playerCacheService.recordPlayerName(
                    playerPuuid,
                    Objects.toString(player.get("name"), ""),
                    Objects.toString(player.get("tag"), ""),
                    observedAt
            );
            if (requiredPuuid != null && requiredPuuid.equals(playerPuuid)) {
                requiredPlayerFound = true;
            }
        }
        return requiredPlayerFound;
    }

    private void recordSeasonMetadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object seasonValue = map.get("season");
            if (seasonValue instanceof Map<?, ?> season) {
                String id = Objects.toString(season.get("id"), "");
                String shortCode = Objects.toString(season.get("short"), "");
                dynamoDbService.storeSeason(id, shortCode);
            }

            String seasonId = Objects.toString(map.get("season_id"), "");
            String seasonShort = Objects.toString(map.get("season_short"), "");
            if (!seasonId.isBlank() && !seasonShort.isBlank()) {
                dynamoDbService.storeSeason(seasonId, seasonShort);
            }
            map.values().forEach(this::recordSeasonMetadata);
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(this::recordSeasonMetadata);
        }
    }

    public List<Map<String, Object>> getPlayerNameHistory(String puuid) {
        return playerCacheService.getPlayerNameHistory(puuid);
    }

    public Map<String, Object> getPlayerNameHistoryRefreshStatus(String puuid) {
        return Map.of("status", 200, "data", Map.of(
                "refreshRequired", playerCacheService.shouldBackfillNameHistory(puuid),
                "source", "sampled-match-details"));
    }

    public Map<String, Object> refreshPlayerNameHistory(String puuid) {
        if (!playerCacheService.shouldBackfillNameHistory(puuid)) {
            return Map.of("status", 200, "data", Map.of(
                    "updated", false,
                    "source", "sampled-match-details"));
        }
        backfillRecentNameHistory(puuid);
        return Map.of("status", 200, "data", Map.of(
                "updated", true,
                "source", "sampled-match-details"));
    }

    public Map<String, Object> getPlayerIdentity(String puuid) {
        Optional<Map<String, String>> cached = playerCacheService.getCurrentIdentity(puuid)
                .filter(identity -> !identity.getOrDefault("name", "").isBlank()
                        && !identity.getOrDefault("tag", "").isBlank());
        if (cached.isPresent()) {
            Map<String, String> identity = cached.get();
            return identityResponse(
                    puuid,
                    identity.get("name"),
                    identity.get("tag"),
                    identity.getOrDefault("region", "na")
            );
        }

        try {
            Map<String, Object> response = apiRequestQueue.execute(
                    "account for PUUID " + puuid,
                    () -> apiClient.getAccountByPuuid(puuid));
            if (response != null && response.get("data") instanceof Map<?, ?> data) {
                String resolvedPuuid = Objects.toString(data.get("puuid"), puuid);
                String name = Objects.toString(data.get("name"), "");
                String tag = Objects.toString(data.get("tag"), "");
                String region = Objects.toString(data.get("region"), "na");
                if (!name.isBlank() && !tag.isBlank()) {
                    playerCacheService.storeAccountProfile(data, name, tag, region);
                    return identityResponse(resolvedPuuid, name, tag, region);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve account for PUUID {}", puuid, e);
        }

        return Map.of(
                "status", 404,
                "error", "Player identity could not be resolved for this PUUID"
        );
    }

    private Map<String, Object> identityResponse(String puuid, String name, String tag, String region) {
        return Map.of(
                "status", 200,
                "data", Map.of(
                        "puuid", puuid,
                        "name", name,
                        "tag", tag,
                        "region", region
                )
        );
    }

    private void backfillRecentNameHistory(String puuid) {
        if (!playerCacheService.shouldBackfillNameHistory(puuid)) return;
        Optional<Map<String, String>> identity = playerCacheService.getCurrentIdentity(puuid);
        if (identity.isEmpty()) return;

        boolean completed = true;
        try {
            Map<Long, String> checkpoints = new TreeMap<>();
            List<Map<String, AttributeValue>> cachedMatches =
                    dynamoDbService.getStoredMatchesForPlayer(puuid, 10_000, 1);

            for (Map<String, AttributeValue> match : cachedMatches) {
                AttributeValue matchIdValue = match.get("matchId");
                AttributeValue gameStartValue = match.get("gameStart");
                if (matchIdValue == null || matchIdValue.s() == null
                        || gameStartValue == null || gameStartValue.n() == null) continue;
                try {
                    long observedAt = Long.parseLong(gameStartValue.n());
                    checkpoints.putIfAbsent(
                            Math.floorDiv(observedAt, NAME_HISTORY_SAMPLE_SECONDS),
                            matchIdValue.s()
                    );
                } catch (NumberFormatException ignored) {
                    // Ignore malformed cached timestamps.
                }
            }

            if (checkpoints.isEmpty()) {
                completed = false;
                LOG.debug("Name-history backfill for {} is waiting for cached matches", puuid);
            }

            int attempted = 0;
            for (String matchId : spreadAcrossTimeline(checkpoints)) {
                if (playerCacheService.isNameHistoryCheckpointProcessed(puuid, matchId)) continue;
                if (attempted >= NAME_HISTORY_CHECKPOINTS_PER_REQUEST) {
                    completed = false;
                    break;
                }
                attempted++;
                try {
                    if (recordMatchPlayerNames(apiRequestQueue.executeLowPriority(
                            "match details " + matchId,
                            () -> apiClient.getMatchById(matchId)), puuid)) {
                        playerCacheService.markNameHistoryCheckpointProcessed(puuid, matchId);
                    } else {
                        completed = false;
                    }
                } catch (Exception e) {
                    completed = false;
                    LOG.warn("Failed name-history checkpoint match {} for {}. Backfill will resume later.", matchId, puuid, e);
                    break;
                }
            }
        } catch (Exception e) {
            completed = false;
            LOG.warn("Failed to backfill recent name history for {}", puuid, e);
        } finally {
            if (completed) playerCacheService.markNameHistoryBackfilled(puuid);
        }
    }

    private List<String> spreadAcrossTimeline(Map<Long, String> checkpoints) {
        List<String> chronological = new ArrayList<>(checkpoints.values());
        if (chronological.size() <= 2) return chronological;

        List<String> spread = new ArrayList<>(chronological.size());
        boolean[] added = new boolean[chronological.size()];
        addCheckpoint(chronological, spread, added, 0);
        addCheckpoint(chronological, spread, added, chronological.size() - 1);

        ArrayDeque<int[]> ranges = new ArrayDeque<>();
        ranges.add(new int[]{0, chronological.size() - 1});
        while (!ranges.isEmpty()) {
            int[] range = ranges.removeFirst();
            if (range[1] - range[0] <= 1) continue;
            int midpoint = (range[0] + range[1]) / 2;
            addCheckpoint(chronological, spread, added, midpoint);
            ranges.addLast(new int[]{range[0], midpoint});
            ranges.addLast(new int[]{midpoint, range[1]});
        }
        return spread;
    }

    private void addCheckpoint(List<String> source, List<String> target, boolean[] added, int index) {
        if (!added[index]) {
            target.add(source.get(index));
            added[index] = true;
        }
    }

    /**
     * Get aggregated player stats (K/D, HS%, ACS, K/R, ADR).
     * Delegates to PlayerStatsService.
     */
    public Map<String, Object> getPlayerStats(String region, String name, String tag, String seasonId, String mode) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        return playerStatsService.getPlayerStats(puuid, region, name, tag, seasonId, mode);
    }

    /**
     * Get only ADR for a player.
     * Delegates to PlayerStatsService.
     */
    public Map<String, Object> getPlayerAdr(String region, String name, String tag, String seasonId, String mode) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        return playerStatsService.getPlayerAdr(puuid, region, name, tag, seasonId, mode);
    }

    public List<Map<String, String>> getAvailableActs(String region, String name, String tag) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) return List.of();

        List<Map<String, AttributeValue>> items =
                dynamoDbService.getStoredMatchesForPlayer(puuid, ACT_DISCOVERY_MATCH_LIMIT, 1);

        Map<String, Long> seasonLatestGame = new HashMap<>();
        Map<String, String> seasonLabels = new HashMap<>();

        for (Map<String, AttributeValue> item : items) {
            AttributeValue skAttr = item.get("SK");
            if (skAttr == null || skAttr.s() == null) continue;

            String sk = skAttr.s();

            if (!sk.contains("#MATCH#")) continue;

            String[] parts = sk.split("#");
            if (parts.length < 4) continue;

            String seasonId = parts[1];

            AttributeValue storedName = item.get("seasonName");
            if (storedName != null && storedName.s() != null && !storedName.s().isBlank()) {
                seasonLabels.put(seasonId, storedName.s());
            } else {
                AttributeValue storedShort = item.get("seasonShort");
                if (storedShort != null && storedShort.s() != null) {
                    String formatted = SeasonNames.format(storedShort.s());
                    if (!formatted.isBlank()) seasonLabels.put(seasonId, formatted);
                }
            }

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
                        "label", resolveSeasonLabel(entry.getKey(), seasonLabels.get(entry.getKey()))
                ))
                .toList();
    }

    public List<Map<String, String>> getAvailableModes(String region, String name, String tag) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) return List.of();

        Map<String, String> modes = new TreeMap<>();
        for (Map<String, AttributeValue> item : dynamoDbService.getStoredMatchesForPlayer(puuid, 10_000, 1)) {
            AttributeValue mode = item.get("mode");
            if (mode == null || mode.s() == null || mode.s().isBlank()) continue;
            AttributeValue storedName = item.get("modeName");
            String label = storedName != null && storedName.s() != null && !storedName.s().isBlank()
                    ? storedName.s() : mode.s();
            modes.putIfAbsent(mode.s(), label);
        }

        if (modes.isEmpty()) modes.put("competitive", "Competitive");
        return modes.entrySet().stream()
                .sorted((a, b) -> {
                    if ("competitive".equals(a.getKey())) return -1;
                    if ("competitive".equals(b.getKey())) return 1;
                    return a.getValue().compareToIgnoreCase(b.getValue());
                })
                .map(entry -> Map.of("value", entry.getKey(), "label", entry.getValue()))
                .toList();
    }

    public Map<String, Object> getPlayerInsights(String region, String name, String tag) {
        String puuid = resolvePuuid(name, tag, region);
        if (puuid == null) return errorResponse("Player not found");
        String pk = "PLAYER#" + puuid;
        List<Map<String, Object>> maps = insightRows(dynamoDbService.queryByPkPrefix(pk, "MAP#"));
        List<Map<String, Object>> agents = insightRows(dynamoDbService.queryByPkPrefix(pk, "AGENT#"));
        List<Map<String, Object>> playedWith = insightRows(dynamoDbService.queryByPkPrefix(pk, "SOCIAL#WITH#"));
        List<Map<String, Object>> playedAgainst = insightRows(dynamoDbService.queryByPkPrefix(pk, "SOCIAL#AGAINST#"));
        List<Map<String, Object>> topDuos = playedWith.stream()
                .filter(row -> ((Number) row.get("games")).longValue() >= 2)
                .sorted(Comparator
                        .<Map<String, Object>>comparingDouble(row -> -((Number) row.get("winRate")).doubleValue())
                        .thenComparingLong(row -> -((Number) row.get("games")).longValue()))
                .limit(10)
                .toList();
        return Map.of("status", 200, "data", Map.of(
                "maps", maps, "agents", agents,
                "frequentlyPlayedWith", playedWith,
                "frequentlyPlayedAgainst", playedAgainst,
                "topDuos", topDuos));
    }

    private List<Map<String, Object>> insightRows(List<Map<String, AttributeValue>> items) {
        return items.stream().map(item -> {
                    long games = attributeLong(item, "games");
                    long wins = attributeLong(item, "wins");
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String key : List.of("mapName", "agentName", "playerPuuid", "name", "tag")) {
                        AttributeValue value = item.get(key);
                        if (value != null && value.s() != null) row.put(key, value.s());
                    }
                    row.put("games", games);
                    row.put("wins", wins);
                    row.put("losses", attributeLong(item, "losses"));
                    row.put("draws", attributeLong(item, "draws"));
                    row.put("kills", attributeLong(item, "kills"));
                    row.put("deaths", attributeLong(item, "deaths"));
                    row.put("assists", attributeLong(item, "assists"));
                    row.put("winRate", games == 0 ? 0.0 : Math.round(wins * 10_000.0 / games) / 100.0);
                    return row;
                })
                .sorted(Comparator.comparingLong(row -> -((Number) row.get("games")).longValue()))
                .toList();
    }

    private long attributeLong(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        if (value == null || value.n() == null) return 0L;
        try { return Long.parseLong(value.n()); } catch (NumberFormatException ignored) { return 0L; }
    }

    private String resolveSeasonLabel(String seasonId, String matchLabel) {
        if (matchLabel != null && !matchLabel.isBlank()) return matchLabel;
        return dynamoDbService.getSeason(seasonId)
                .map(season -> season.get("name"))
                .filter(name -> name != null && !name.isBlank())
                .orElse("Unknown Act");
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
            Map<String, Object> mmrResponse = apiRequestQueue.execute(
                    "MMR for " + name + "#" + tag,
                    () -> apiClient.getMMR(region, name, tag));

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
            normalizeHighestRank(data, bySeason);
            enrichSeasonMetadata(bySeason);

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

    private void enrichSeasonMetadata(Map<String, Object> bySeason) {
        SEASON_TO_HENRIK.forEach((seasonId, henrikKey) -> {
            if (!(bySeason.get(henrikKey) instanceof Map<?, ?> rawSeason)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> season = (Map<String, Object>) rawSeason;
            season.put("season_id", seasonId);
            season.put("season_name", SEASON_MAP.getOrDefault(seasonId, SeasonNames.format(henrikKey)));
        });
    }

    private void normalizeHighestRank(Map<String, Object> data, Map<String, Object> bySeason) {
        RankCandidate peak = new RankCandidate(0, "Unranked", "");

        if (data.get("highest_rank") instanceof Map<?, ?> highest) {
            peak = higherRank(peak, Objects.toString(highest.get("patched_tier"), ""),
                    Objects.toString(highest.get("season"), ""));
        }

        if (data.get("current_data") instanceof Map<?, ?> current) {
            peak = higherRank(peak, Objects.toString(current.get("currenttierpatched"), ""), "");
        }

        for (Map.Entry<String, Object> seasonEntry : bySeason.entrySet()) {
            if (!(seasonEntry.getValue() instanceof Map<?, ?> season)) continue;
            if (season.containsKey("error") && season.get("error") != null
                    && !Boolean.FALSE.equals(season.get("error"))) continue;
            peak = higherRank(peak, Objects.toString(season.get("final_rank_patched"), ""), seasonEntry.getKey());
            if (season.get("act_rank_wins") instanceof Iterable<?> wins) {
                for (Object value : wins) {
                    if (!(value instanceof Map<?, ?> win)) continue;
                    peak = higherRank(peak, Objects.toString(win.get("patched_tier"), ""), seasonEntry.getKey());
                }
            }
        }

        if (peak.order() > 0) {
            Map<String, Object> normalized = new HashMap<>();
            normalized.put("tier", peak.order());
            normalized.put("patched_tier", peak.name());
            normalized.put("season", peak.season());
            normalized.put("normalized", true);
            data.put("highest_rank", normalized);
        }
    }

    private RankCandidate higherRank(RankCandidate current, String name, String season) {
        int order = canonicalRankTier(name);
        return order > current.order() ? new RankCandidate(order, name, season) : current;
    }

    private int canonicalRankTier(String rankName) {
        if (rankName == null) return 0;
        return switch (rankName.trim().toLowerCase(Locale.ROOT)) {
            case "iron 1" -> 3; case "iron 2" -> 4; case "iron 3" -> 5;
            case "bronze 1" -> 6; case "bronze 2" -> 7; case "bronze 3" -> 8;
            case "silver 1" -> 9; case "silver 2" -> 10; case "silver 3" -> 11;
            case "gold 1" -> 12; case "gold 2" -> 13; case "gold 3" -> 14;
            case "platinum 1" -> 15; case "platinum 2" -> 16; case "platinum 3" -> 17;
            case "diamond 1" -> 18; case "diamond 2" -> 19; case "diamond 3" -> 20;
            case "ascendant 1" -> 21; case "ascendant 2" -> 22; case "ascendant 3" -> 23;
            case "immortal 1" -> 24; case "immortal 2" -> 25; case "immortal 3" -> 26;
            case "radiant" -> 27;
            default -> 0;
        };
    }

    private record RankCandidate(int order, String name, String season) {}

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
            Map<String, Object> account = apiRequestQueue.execute(
                    "account for " + name + "#" + tag,
                    () -> apiClient.getAccount(name, tag));

            if (account == null || !(account.get("data") instanceof Map<?, ?> data)) {
                LOG.debug("No Henrik account found for {}#{}", name, tag);
                return null;
            }

            String puuid = Objects.toString(data.get("puuid"), "");
            if (!puuid.isBlank()) {
                String playerRegion = Objects.toString(
                        data.get("region"), region != null ? region : "na");
                playerCacheService.storePlayerProfile(puuid, name, tag, playerRegion);
                return puuid;
            }
        } catch (HttpClientResponseException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                LOG.debug("Henrik account not found for {}#{}", name, tag);
            } else {
                LOG.error("Failed to resolve puuid for {}#{}", name, tag, e);
            }
        } catch (Exception e) {
            LOG.error("Failed to resolve puuid for {}#{}", name, tag, e);
        }

        return null;
    }

    private String mapToHenrikSeason(String seasonId) {
        return dynamoDbService.getSeason(seasonId)
                .map(season -> season.get("short"))
                .filter(shortCode -> shortCode != null && !shortCode.isBlank())
                .orElseGet(() -> SEASON_TO_HENRIK.get(seasonId));
    }

    private Map<String, Object> errorResponse(String msg) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 404);
        response.put("error", msg);
        return response;
    }
}

