package com.valstats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valstats.client.ValorantApiClient;
import com.valstats.model.match.Match;
import io.micronaut.context.annotation.Bean;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class ValorantService {

    private static final Logger LOG = LoggerFactory.getLogger(ValorantService.class);

    private final ValorantApiClient valorantApiClient;
    private final PlayerCacheService playerCacheService;
    private final DynamoDbService dynamoDbService;
    private final MatchProcessor matchProcessor;
    private final ObjectMapper objectMapper;

    private static final String AUTH_TOKEN = System.getenv("HDEV_KEY");

    public ValorantService(
            ValorantApiClient valorantApiClient,
            PlayerCacheService playerCacheService,
            DynamoDbService dynamoDbService,
            MatchProcessor matchProcessor,
            ObjectMapper objectMapper
    ) {
        this.valorantApiClient = valorantApiClient;
        this.playerCacheService = playerCacheService;
        this.dynamoDbService = dynamoDbService;
        this.matchProcessor = matchProcessor;
        this.objectMapper = objectMapper;
    }

    /* =========================================================
       MAIN MATCH ENDPOINT
    ========================================================= */

    public Map<String, Object> getUnifiedMatches(String region, String name, String tag, int size, int page) {
        String puuid = resolvePuuid(name, tag);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        List<Map<String, AttributeValue>> stored = dynamoDbService.getStoredMatchesForPlayer(puuid, size, page);
        List<Map<String, AttributeValue>> mmr = dynamoDbService.getMMRHistory(puuid);

        if (!stored.isEmpty()) {
            return mergeStoredAndMMR(stored, mmr);
        }

        Map<String, Object> storedApi = valorantApiClient.getStoredMatches(
                region, name, tag, size, page, "competitive", AUTH_TOKEN
        );
        Map<String, Object> mmrApi = valorantApiClient.getMMRHistory(
                region, name, tag, AUTH_TOKEN
        );

        storeMMRHistoryFromResponse(mmrApi, name, tag);

        return mergeApiResponses(storedApi, mmrApi);
    }

    /* =========================================================
       ACCOUNT
    ========================================================= */

    public Map<String, Object> getAccountDetails(String name, String tag) {
        return valorantApiClient.getAccount(name, tag, AUTH_TOKEN);
    }

    /* =========================================================
       MATCH DETAILS
    ========================================================= */

    public Map<String, Object> getMatchById(String matchId) {
        Optional<Map<String, AttributeValue>> cached = dynamoDbService.getMatchById(matchId);

        if (cached.isPresent()) {
            Map<String, AttributeValue> item = cached.get();
            List<Map<String, AttributeValue>> players = dynamoDbService.getMatchPlayers(matchId);

            List<Map<String, Object>> allPlayers = players.stream()
                    .map(p -> {
                        Map<String, Object> stats = new HashMap<>();
                        stats.put("kills", getInt(p, "kills"));
                        stats.put("deaths", getInt(p, "deaths"));
                        stats.put("assists", getInt(p, "assists"));
                        stats.put("score", getInt(p, "score"));
                        stats.put("headshots", getInt(p, "headshots"));
                        stats.put("bodyshots", getInt(p, "bodyshots"));
                        stats.put("legshots", getInt(p, "legshots"));

                        Map<String, Object> player = new HashMap<>();
                        player.put("puuid", getString(p, "puuid"));
                        player.put("name", getString(p, "name"));
                        player.put("team", getString(p, "team"));
                        player.put("character", getString(p, "character"));
                        player.put("stats", stats);
                        player.put("damage_made", getInt(p, "damage_made"));
                        return player;
                    })
                    .toList();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("matchid", getString(item, "matchId"));
            metadata.put("map", getString(item, "map"));
            metadata.put("game_start", getLong(item, "gameStart"));
            metadata.put("rounds_played",
                    getInt(item, "redRoundsWon") + getInt(item, "blueRoundsWon"));

            Map<String, Object> playersMap = new HashMap<>();
            playersMap.put("all_players", allPlayers);

            Map<String, Object> data = new HashMap<>();
            data.put("metadata", metadata);
            data.put("players", playersMap);

            Map<String, Object> response = new HashMap<>();
            response.put("status", 200);
            response.put("cached", true);
            response.put("data", data);
            return response;
        }

        return valorantApiClient.getMatchById(matchId, AUTH_TOKEN);
    }

    /* =========================================================
       PLAYER STATS
    ========================================================= */
    public Map<String, Object> getPlayerStats(String region, String name, String tag, String seasonId) {
        String puuid = resolvePuuid(name, tag);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        Map<String, Long> stats;

        if (seasonId == null || seasonId.equalsIgnoreCase("all")) {
            stats = dynamoDbService.getPlayerTotalStats(puuid);

            if (stats.isEmpty() || stats.getOrDefault("matches_played", 0L) == 0) {
                LOG.info("No cached stats found for player {}. Fetching and processing matches...", puuid);
                processPlayerMatchesFromAPI(region, name, tag, puuid);
                stats = dynamoDbService.getPlayerTotalStats(puuid);

                if (stats.isEmpty() || stats.getOrDefault("matches_played", 0L) == 0) {
                    stats = aggregateStatsFromStoredMatchesApi(region, name, tag, "all");
                }
            }
        } else {
            Optional<Map<String, AttributeValue>> season = dynamoDbService.getPlayerSeasonStats(puuid, seasonId);

            if (season.isEmpty()) {
                LOG.info("No cached stats found for season {}. Fetching and processing matches...", seasonId);
                processPlayerMatchesFromAPI(region, name, tag, puuid);
                season = dynamoDbService.getPlayerSeasonStats(puuid, seasonId);

                if (season.isEmpty()) {
                    stats = aggregateStatsFromStoredMatchesApi(region, name, tag, seasonId);
                    if (stats.getOrDefault("matches_played", 0L) == 0) {
                        return errorResponse("No stats found");
                    }
                } else {
                    stats = readStatsItem(season.get());
                }
            } else {
                stats = readStatsItem(season.get());
            }
        }

        long kills = stats.getOrDefault("total_kills", 0L);
        long deaths = stats.getOrDefault("total_deaths", 0L);
        long matches = stats.getOrDefault("matches_played", 0L);
        long score = stats.getOrDefault("total_score", 0L);
        long damage = stats.getOrDefault("total_damage", 0L);
        long totalRounds = stats.getOrDefault("total_rounds", 0L);

        long head = stats.getOrDefault("total_headshots", 0L);
        long body = stats.getOrDefault("total_bodyshots", 0L);
        long leg = stats.getOrDefault("total_legshots", 0L);
        long totalShots = head + body + leg;

        double kd = deaths > 0 ? (double) kills / deaths : kills;
        double hs = totalShots > 0 ? (double) head / totalShots * 100 : 0;
        long rounds = stats.getOrDefault("total_rounds", 0L);

        double acs = rounds > 0 ? (double) score / rounds : 0;
        double kpr = rounds > 0 ? (double) kills / rounds : 0;
        double adr = rounds > 0 ? (double) damage / rounds : 0;

        Map<String, Object> data = new HashMap<>();
        data.put("kd_ratio", round(kd));
        data.put("headshot_percent", round(hs));
        data.put("avg_combat_score", round(acs));
        data.put("kills_per_round", Math.round(kpr * 1000.0) / 1000.0);
        data.put("adr", Math.round(adr * 100.0) / 100.0);

        return Map.of("status", 200, "data", data);
    }

    public Map<String, Object> getPlayerAdr(String region, String name, String tag, String seasonId) {
        Map<String, Object> stats = getPlayerStats(region, name, tag, seasonId);
        if (!Objects.equals(stats.get("status"), 200)) return stats;
        Map<String, Object> data = castMap(stats.get("data"));
        return Map.of("status", 200, "data", Map.of("adr", data.getOrDefault("adr", 0.0)));
    }

    /* =========================================================
       MERGE LOGIC
    ========================================================= */

    private Map<String, Object> mergeStoredAndMMR(
            List<Map<String, AttributeValue>> stored,
            List<Map<String, AttributeValue>> mmr
    ) {
        Map<String, Map<String, AttributeValue>> mmrMap = mmr.stream()
                .collect(Collectors.toMap(
                        m -> getString(m, "matchId"),
                        m -> m,
                        (a, b) -> a
                ));

        List<Map<String, Object>> matches = stored.stream()
                .map(match -> {
                    String matchId = getString(match, "matchId");
                    Map<String, AttributeValue> mmrEntry = mmrMap.get(matchId);

                    int kills = getInt(match, "kills");
                    int deaths = getInt(match, "deaths");
                    int assists = getInt(match, "assists");
                    String kda = kills + "/" + deaths + "/" + assists;

                    int rr = mmrEntry != null ? getInt(mmrEntry, "rr") : 0;
                    String rank = mmrEntry != null ? getString(mmrEntry, "rank") : "Unranked";
                    int rankingInTier = mmrEntry != null ? getInt(mmrEntry, "ranking_in_tier") : 0;

                    int blueRoundsWon = getInt(match, "blueRoundsWon");
                    int redRoundsWon = getInt(match, "redRoundsWon");
                    String team = getString(match, "team").toLowerCase(Locale.ROOT);

                    int score = "red".equals(team) ? redRoundsWon : blueRoundsWon;
                    int enemyScore = "red".equals(team) ? blueRoundsWon : redRoundsWon;
                    int roundsPlayed = redRoundsWon + blueRoundsWon;

                    Map<String, Object> result = new HashMap<>();
                    result.put("id", matchId);
                    result.put("map", getString(match, "map"));
                    result.put("mapId", getString(match, "mapId"));
                    result.put("result", score > enemyScore ? "Victory" : "Defeat");
                    result.put("score", score);
                    result.put("enemy_score", enemyScore);
                    result.put("kda", kda);

                    String agentName = getString(match, "agentName");
                    String agentId = getString(match, "agentId");

                    result.put("agent", agentName);
                    result.put("agentIcon",
                            "https://media.valorant-api.com/agents/" + agentId + "/displayicon.png"
                    );

                    result.put("acs", roundsPlayed > 0 ? Math.round((float) getInt(match, "score") / roundsPlayed) : 0);
                    result.put("timestamp", getString(match, "timestamp"));
                    result.put("date_raw", getLong(match, "gameStart"));
                    result.put("rank", rank);
                    result.put("ranking_in_tier", rankingInTier);
                    result.put("rrChange", rr);
                    result.put("rounds_played", roundsPlayed);

                    Map<String, Object> teams = new HashMap<>();
                    Map<String, Object> red = new HashMap<>();
                    red.put("rounds_won", redRoundsWon);
                    Map<String, Object> blue = new HashMap<>();
                    blue.put("rounds_won", blueRoundsWon);
                    teams.put("red", red);
                    teams.put("blue", blue);
                    result.put("teams", teams);

                    result.put("hasDetails", false);
                    return result;
                })
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", matches);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeApiResponses(
            Map<String, Object> storedApi,
            Map<String, Object> mmrApi
    ) {
        List<Map<String, Object>> storedMatches =
                (List<Map<String, Object>>) storedApi.getOrDefault("data", List.of());

        List<Map<String, Object>> mmrList =
                (List<Map<String, Object>>) mmrApi.getOrDefault("data", List.of());

        Map<String, Map<String, Object>> mmrMap = mmrList.stream()
                .collect(Collectors.toMap(
                        m -> Objects.toString(m.get("match_id"), ""),
                        m -> m,
                        (a, b) -> a
                ));

        List<Map<String, Object>> matches = storedMatches.stream()
                .map(match -> {
                    Map<String, Object> meta = castMap(match.get("meta"));
                    Map<String, Object> stats = castMap(match.get("stats"));
                    Map<String, Object> mapObj = castMap(meta.get("map"));
                    Map<String, Object> character = castMap(stats.get("character"));

                    String matchId = str(meta.get("id"));
                    Map<String, Object> mmr = mmrMap.get(matchId);

                    int kills = num(stats.get("kills"));
                    int deaths = num(stats.get("deaths"));
                    int assists = num(stats.get("assists"));
                    String kda = kills + "/" + deaths + "/" + assists;

                    int rr = mmr != null ? num(mmr.get("mmr_change_to_last_game")) : 0;
                    String rank = mmr != null ? str(mmr.getOrDefault("currenttier_patched", "Unranked")) : "Unranked";
                    int rankingInTier = mmr != null ? num(mmr.get("currenttier")) : num(stats.get("tier"));

                    String userTeam = str(stats.getOrDefault("team", "blue")).toLowerCase(Locale.ROOT);
                    int redRounds = extractTeamRounds(match.get("teams"), "red");
                    int blueRounds = extractTeamRounds(match.get("teams"), "blue");
                    int score = "red".equals(userTeam) ? redRounds : blueRounds;
                    int enemyScore = "red".equals(userTeam) ? blueRounds : redRounds;
                    int roundsPlayed = redRounds + blueRounds;
                    int acs = roundsPlayed > 0 ? Math.round((float) num(stats.get("score")) / roundsPlayed) : 0;

                    String timestamp = str(
                            mmr != null && mmr.get("date") != null
                                    ? mmr.get("date")
                                    : meta.getOrDefault("started_at", "Unknown")
                    );

                    String mapId = str(mapObj.get("id"));
                    String agentId = str(character.get("id"));
                    String agentIcon = !agentId.isBlank()
                            ? "https://media.valorant-api.com/agents/" + agentId + "/displayicon.png"
                            : "";

                    Map<String, Object> result = new HashMap<>();
                    result.put("id", matchId);
                    result.put("map", str(mapObj.getOrDefault("name", "Unknown")));
                    result.put("mapId", mapId);
                    result.put("result", score > enemyScore ? "Victory" : "Defeat");
                    result.put("score", score);
                    result.put("enemy_score", enemyScore);
                    result.put("kda", kda);
                    result.put("agent", str(character.getOrDefault("name", "Unknown")));
                    result.put("agentIcon", agentIcon);
                    result.put("acs", acs);
                    result.put("timestamp", timestamp);
                    result.put("date_raw", mmr != null && mmr.get("date_raw") != null
                            ? longNum(mmr.get("date_raw"))
                            : parseDateRaw(meta.get("started_at")));
                    result.put("rank", rank);
                    result.put("ranking_in_tier", rankingInTier);
                    result.put("rrChange", rr);
                    result.put("rounds_played", roundsPlayed);

                    Map<String, Object> teams = new HashMap<>();
                    Map<String, Object> red = new HashMap<>();
                    red.put("rounds_won", redRounds);
                    red.put("has_won", redRounds > blueRounds);
                    Map<String, Object> blue = new HashMap<>();
                    blue.put("rounds_won", blueRounds);
                    blue.put("has_won", blueRounds > redRounds);
                    teams.put("red", red);
                    teams.put("blue", blue);
                    result.put("teams", teams);

                    result.put("puuid", str(stats.get("puuid")));
                    result.put("hasDetails", false);
                    return result;
                })
                .sorted((a, b) -> Long.compare(
                        longNum(b.get("date_raw")),
                        longNum(a.get("date_raw"))
                ))
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", matches);
        return response;
    }

    /* =========================================================
       HELPERS
    ========================================================= */

    private void processPlayerMatchesFromAPI(String region, String name, String tag, String puuid) {
        try {
            if (!playerCacheService.canFetchFromApi(puuid)) {
                LOG.debug("Skipping external fetch for {} due to cooldown", puuid);
                return;
            }

            Map<String, Object> storedApi = valorantApiClient.getStoredMatches(
                    region, name, tag, 20, 1, "competitive", AUTH_TOKEN
            );

            List<Map<String, Object>> storedMatches =
                    (List<Map<String, Object>>) storedApi.getOrDefault("data", List.of());

            int processed = 0;
            for (Map<String, Object> match : storedMatches) {
                try {
                    if (matchProcessor.processStoredMatchSummary(match, puuid)) {
                        processed++;
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to process stored match summary", e);
                }
            }

            playerCacheService.updateLastFetchTime(puuid, name, tag, region);
            LOG.info("Processed {} stored matches for {}", processed, puuid);
        } catch (Exception e) {
            LOG.error("Failed to process matches from API", e);
        }
    }

    private Map<String, Long> readStatsItem(Map<String, AttributeValue> item) {
        Map<String, Long> stats = new HashMap<>();
        String[] keys = {
                "matches_played", "total_kills", "total_deaths", "total_assists", "total_score",
                "total_headshots", "total_bodyshots", "total_legshots", "total_damage", "total_rounds"
        };
        for (String key : keys) {
            stats.put(key, item.containsKey(key) ? Long.parseLong(item.get(key).n()) : 0L);
        }
        return stats;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> aggregateStatsFromStoredMatchesApi(String region, String name, String tag, String seasonId) {
        Map<String, Long> out = new HashMap<>();
        out.put("matches_played", 0L);
        out.put("total_kills", 0L);
        out.put("total_deaths", 0L);
        out.put("total_assists", 0L);
        out.put("total_score", 0L);
        out.put("total_headshots", 0L);
        out.put("total_bodyshots", 0L);
        out.put("total_legshots", 0L);
        out.put("total_damage", 0L);
        out.put("total_rounds", 0L);

        Map<String, Object> storedApi = valorantApiClient.getStoredMatches(
                region, name, tag, 20, 1, "competitive", AUTH_TOKEN
        );

        List<Map<String, Object>> matches =
                (List<Map<String, Object>>) storedApi.getOrDefault("data", List.of());

        for (Map<String, Object> match : matches) {

            Map<String, Object> meta = castMap(match.get("meta"));
            Map<String, Object> season = castMap(meta.get("season"));

            String sid = str(season.get("id"));
            if (!(seasonId == null || seasonId.equalsIgnoreCase("all") || seasonId.equalsIgnoreCase(sid))) {
                continue;
            }

            Map<String, Object> stats = castMap(match.get("stats"));
            Map<String, Object> shots = castMap(stats.get("shots"));
            Map<String, Object> damage = castMap(stats.get("damage"));

            long kills = num(stats.get("kills"));
            long deaths = num(stats.get("deaths"));
            long assists = num(stats.get("assists"));
            long score = num(stats.get("score"));

            long head = num(shots.get("head"));
            long body = num(shots.get("body"));
            long leg = num(shots.get("leg"));

            // ✅ FIX: robust damage extraction
            long damageMade =
                    damage.containsKey("made") ? num(damage.get("made")) :
                            damage.containsKey("dealt") ? num(damage.get("dealt")) :
                                    num(stats.get("damage_made"));

            // ✅ FIX: robust rounds extraction
            int redRounds = extractTeamRounds(match.get("teams"), "red");
            int blueRounds = extractTeamRounds(match.get("teams"), "blue");

            // fallback ONLY if teams missing (NOT fake 20 rounds)
            if (redRounds == 0 && blueRounds == 0) {
                redRounds = num(match.get("red_score"));
                blueRounds = num(match.get("blue_score"));
            }

            long rounds = redRounds + blueRounds;

            // accumulate
            out.put("matches_played", out.get("matches_played") + 1);
            out.put("total_kills", out.get("total_kills") + kills);
            out.put("total_deaths", out.get("total_deaths") + deaths);
            out.put("total_assists", out.get("total_assists") + assists);
            out.put("total_score", out.get("total_score") + score);
            out.put("total_headshots", out.get("total_headshots") + head);
            out.put("total_bodyshots", out.get("total_bodyshots") + body);
            out.put("total_legshots", out.get("total_legshots") + leg);
            out.put("total_damage", out.get("total_damage") + damageMade);
            out.put("total_rounds", out.get("total_rounds") + rounds);
        }

        return out;
    }

    private Match parseMatchFromApi(Map<String, Object> matchData) {
        try {
            // Use ObjectMapper to convert Map to Match object
            return objectMapper.convertValue(matchData, Match.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse match data", e);
            return null;
        }
    }

    private int getInt(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? Integer.parseInt(map.get(key).n()) : 0;
    }

    private long getLong(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? Long.parseLong(map.get(key).n()) : 0L;
    }

    private String getString(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? map.get(key).s() : "";
    }

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    private Map<String, Object> errorResponse(String msg) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 404);
        response.put("error", msg);
        return response;
    }

    @SuppressWarnings("unchecked")
    private String resolvePuuid(String name, String tag) {
        Optional<String> cached = playerCacheService.getPuuidByNameTag(name, tag);
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            Map<String, Object> account = valorantApiClient.getAccount(name, tag, AUTH_TOKEN);
            Map<String, Object> data = castMap(account.get("data"));

            if (data.get("puuid") != null) {
                String puuid = str(data.get("puuid"));
                String region = str(data.getOrDefault("region", "na"));
                playerCacheService.storePlayerProfile(puuid, name, tag, region.isBlank() ? "na" : region);
                return puuid;
            }
        } catch (Exception e) {
            LOG.error("Failed to resolve puuid for {}#{}", name, tag, e);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private void storeMMRHistoryFromResponse(Map<String, Object> apiResponse, String name, String tag) {
        String puuid = resolvePuuid(name, tag);
        if (puuid == null) {
            return;
        }

        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) apiResponse.getOrDefault("data", List.of());

        for (Map<String, Object> entry : entries) {
            String matchId = str(entry.get("match_id"));
            if (matchId.isBlank()) {
                continue;
            }

            dynamoDbService.storeMMREntry(
                    puuid,
                    matchId,
                    num(entry.get("mmr_change_to_last_game")),
                    num(entry.get("elo")),
                    str(entry.getOrDefault("currenttier_patched", "Unknown")),
                    longNum(entry.getOrDefault("date_raw", System.currentTimeMillis() / 1000))
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new HashMap<>();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int num(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private long longNum(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private int extractTeamRounds(Object teamsObj, String teamName) {
        if (!(teamsObj instanceof Map<?, ?> teams)) {
            return 0;
        }

        Object teamObj = teams.get(teamName);
        if (teamObj instanceof Number n) {
            return n.intValue();
        }

        if (teamObj instanceof Map<?, ?> teamMap) {
            Object roundsWon = teamMap.get("rounds_won");
            if (roundsWon instanceof Number n) {
                return n.intValue();
            }
        }

        return 0;
    }

    private long parseDateRaw(Object startedAt) {
        if (startedAt == null) {
            return 0L;
        }

        try {
            return java.time.Instant.parse(String.valueOf(startedAt)).getEpochSecond();
        } catch (Exception ignored) {
            return 0L;
        }
    }
}

