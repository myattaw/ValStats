package com.valstats.service.match;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles formatting of match responses for the API.
 * Separates the formatting logic from the data retrieval logic.
 */
@Singleton
public class MatchResponseFormatter {
    private static final Logger LOG = LoggerFactory.getLogger(MatchResponseFormatter.class);

    /**
     * Format cached match data from DynamoDB
     */
    public Map<String, Object> formatCachedMatches(
            List<Map<String, AttributeValue>> cachedMatches,
            List<Map<String, AttributeValue>> cachedMMR
    ) {
        // Create a map of MMR entries indexed by matchId for quick lookup
        Map<String, Map<String, AttributeValue>> mmrMap = cachedMMR.stream()
                .collect(Collectors.toMap(
                        m -> getString(m, "matchId"),
                        m -> m,
                        (a, b) -> a
                ));

        List<Map<String, Object>> formattedMatches = cachedMatches.stream()
                .map(match -> formatMatchRow(match, mmrMap.get(getString(match, "matchId"))))
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("cached", true);
        response.put("data", formattedMatches);
        return response;
    }

    /**
     * Format match data from API response
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> formatApiMatches(
            Map<String, Object> storedMatchesApi,
            Map<String, Object> mmrApi
    ) {
        List<Map<String, Object>> storedMatches =
                (List<Map<String, Object>>) storedMatchesApi.getOrDefault("data", List.of());
        List<Map<String, Object>> mmrList =
                (List<Map<String, Object>>) mmrApi.getOrDefault("data", List.of());

        // Create a map of MMR entries indexed by matchId
        Map<String, Map<String, Object>> mmrMap = mmrList.stream()
                .collect(Collectors.toMap(
                        m -> Objects.toString(m.getOrDefault("match_id", ""), ""),
                        m -> m,
                        (a, b) -> a
                ));

        List<Map<String, Object>> formattedMatches = storedMatches.stream()
                .map(match -> formatMatchRowFromApi(match, mmrMap.get(getMatchId(match))))
                .sorted((a, b) -> Long.compare(
                        longNum(b.get("date_raw")),
                        longNum(a.get("date_raw"))
                ))
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("cached", false);
        response.put("data", formattedMatches);
        return response;
    }

    /**
     * Format full cached match details
     */
    public Map<String, Object> formatCachedMatchDetails(
            Map<String, AttributeValue> matchMetadata,
            List<Map<String, AttributeValue>> players
    ) {
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
                    player.put("character", getString(p, "agentName"));
                    player.put("stats", stats);
                    player.put("damage_made", getInt(p, "damage_made"));
                    return player;
                })
                .toList();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("matchid", getString(matchMetadata, "matchId"));
        metadata.put("map", getString(matchMetadata, "map"));
        metadata.put("game_start", getLong(matchMetadata, "gameStart"));
        metadata.put("rounds_played",
                getInt(matchMetadata, "redRoundsWon") + getInt(matchMetadata, "blueRoundsWon"));

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

    /**
     * Format a single cached match row
     */
    private Map<String, Object> formatMatchRow(
            Map<String, AttributeValue> match,
            Map<String, AttributeValue> mmr
    ) {
        String matchId = getString(match, "matchId");
        int kills = getInt(match, "kills");
        int deaths = getInt(match, "deaths");
        int assists = getInt(match, "assists");
        String kda = kills + "/" + deaths + "/" + assists;

        int rr = mmr != null ? getInt(mmr, "rr") : 0;


        int rankingInTier = mmr != null ? getInt(mmr, "ranking_in_tier") : 0;

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
        result.put("date_raw", getLong(match, "gameStart"));
        long ts = getLong(match, "gameStart");
        result.put("timestamp", ts > 0
                ? Instant.ofEpochSecond(ts).toString()
                : "");

        int rankTier = 0;

        // 1. Prefer match tier (most reliable)
        if (match.containsKey("tier")) {
            rankTier = getInt(match, "tier");
        }

        // 2. fallback to MMR if needed
        else if (mmr != null && mmr.containsKey("currenttier")) {
            rankTier = getInt(mmr, "currenttier");
        }

        // 3. last fallback
        else if (mmr != null && mmr.containsKey("mmr")) {
            rankTier = getInt(mmr, "mmr") / 100;
        }

        String rank = rankTier > 0 ? getRankName(rankTier) : "Unranked";

        result.put("rank", rank);
        result.put("rank_tier", rankTier);
        result.put("ranking_in_tier", rankingInTier);
        result.put("rrChange", rr);
        result.put("rounds_played", roundsPlayed);
        result.put("adr", Math.round(getDouble(match, "adr")));

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
    }

    /**
     * Format a single API match row
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> formatMatchRowFromApi(
            Map<String, Object> match,
            Map<String, Object> mmr
    ) {
        Map<String, Object> meta = asMap(match.get("meta"));
        Map<String, Object> stats = asMap(match.get("stats"));
        Map<String, Object> mapObj = asMap(meta.get("map"));
        Map<String, Object> character = asMap(stats.get("character"));

        String matchId = str(meta.get("id"));

        int kills = num(stats.get("kills"));
        int deaths = num(stats.get("deaths"));
        int assists = num(stats.get("assists"));
        String kda = kills + "/" + deaths + "/" + assists;

        int rr = mmr != null ? num(mmr.get("mmr_change_to_last_game")) : 0;

        int rankTier = mmr != null
                ? num(mmr.get("currenttier"))
                : num(stats.get("tier"));

        String rank = rankTier > 0 ? getRankName(rankTier) : "Unranked";

        int rankingInTier = mmr != null
                ? num(mmr.get("ranking_in_tier"))
                : 0;

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

        result.put("rank", rank); // "Gold 1"
        result.put("rank_tier", rankTier); // 12
        result.put("ranking_in_tier", rankingInTier); // 0–100 RR

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
    }

    // ====== Helper Methods ======

    private String getMatchId(Map<String, Object> match) {
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) match.getOrDefault("meta", new HashMap<>());
        return Objects.toString(meta.getOrDefault("id", ""), "");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
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

    private int getInt(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? Integer.parseInt(map.get(key).n()) : 0;
    }

    private double getDouble(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key)
                ? Double.parseDouble(map.get(key).n())
                : 0.0;
    }

    private long getLong(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? Long.parseLong(map.get(key).n()) : 0L;
    }

    private String getString(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? map.get(key).s() : "";
    }

    private String getRankName(int tier) {
        String[] ranks = {
                "Iron 1","Iron 2","Iron 3",
                "Bronze 1","Bronze 2","Bronze 3",
                "Silver 1","Silver 2","Silver 3",
                "Gold 1","Gold 2","Gold 3",
                "Platinum 1","Platinum 2","Platinum 3",
                "Diamond 1","Diamond 2","Diamond 3",
                "Ascendant 1","Ascendant 2","Ascendant 3",
                "Immortal 1","Immortal 2","Immortal 3",
                "Radiant"
        };

        if (tier == 0) return "Unranked";

        int index = tier - 3;
        return (index >= 0 && index < ranks.length) ? ranks[index] : "Unknown";
    }

}

