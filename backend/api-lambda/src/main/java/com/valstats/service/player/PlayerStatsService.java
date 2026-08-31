package com.valstats.service.player;

import com.valstats.service.DynamoDbService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.*;

/**
 * Handles player statistics calculations and retrieval.
 * Aggregates stats from cached data or calculates on-demand from API.
 */
@Singleton
public class PlayerStatsService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerStatsService.class);
    private static final int MAX_MATCHES = 50_000;

    private final DynamoDbService dynamoDbService;
    public PlayerStatsService(DynamoDbService dynamoDbService) {
        this.dynamoDbService = dynamoDbService;
    }

    /**
     * Get player stats for all time or a specific season.
     * Returns: K/D, Headshot %, ACS, K/R, ADR
     */
    public Map<String, Object> getPlayerStats(
            String puuid,
            String region,
            String name,
            String tag,
            String seasonId,
            String mode
    ) {
        if ((seasonId == null || seasonId.isBlank() || "all".equalsIgnoreCase(seasonId))
                && (mode == null || mode.isBlank() || "all".equalsIgnoreCase(mode))) {
            return getOverallStats(puuid);
        }
        String normalizedMode = mode == null ? "all" : mode.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        Map<String, Long> stats = new HashMap<>();
        for (Map<String, AttributeValue> item : dynamoDbService.getStoredMatchesForPlayer(puuid, MAX_MATCHES, 1)) {
            if (!"all".equalsIgnoreCase(seasonId)
                    && !seasonId.equals(stringValue(item, "seasonId"))) continue;
            if (!"all".equals(normalizedMode)
                    && !normalizedMode.equals(stringValue(item, "mode"))) continue;
            add(stats, "matches_played", 1);
            String team = stringValue(item, "team").toLowerCase(Locale.ROOT);
            long redRounds = numberValue(item, "redRoundsWon");
            long blueRounds = numberValue(item, "blueRoundsWon");
            boolean hasRoundResult = item.containsKey("redRoundsWon") && item.containsKey("blueRoundsWon");
            if (hasRoundResult && ("red".equals(team) || "blue".equals(team)) && redRounds != blueRounds) {
                boolean won = "red".equals(team) ? redRounds > blueRounds : blueRounds > redRounds;
                add(stats, won ? "wins" : "losses", 1);
            } else if (hasRoundResult && ("red".equals(team) || "blue".equals(team)) && redRounds == blueRounds) {
                add(stats, "draws", 1);
            }
            add(stats, "total_kills", numberValue(item, "kills"));
            add(stats, "total_deaths", numberValue(item, "deaths"));
            add(stats, "total_assists", numberValue(item, "assists"));
            add(stats, "total_score", numberValue(item, "score"));
            add(stats, "total_headshots", numberValue(item, "headshots"));
            add(stats, "total_bodyshots", numberValue(item, "bodyshots"));
            add(stats, "total_legshots", numberValue(item, "legshots"));
            add(stats, "total_damage", numberValue(item, "damage_made"));
            add(stats, "total_rounds", numberValue(item, "rounds_played"));
        }
        return formatStats(stats);
    }

    /**
     * Constant-time all-mode totals used by lightweight profile and lobby views.
     * The TOTAL row is maintained when new match summaries are persisted.
     */
    public Map<String, Object> getOverallStats(String puuid) {
        return formatStats(dynamoDbService.getPlayerTotalStats(puuid));
    }

    private void add(Map<String, Long> stats, String key, long value) {
        stats.merge(key, value, Long::sum);
    }

    private long numberValue(Map<String, AttributeValue> item, String key) {
        try { return item.containsKey(key) ? Long.parseLong(item.get(key).n()) : 0L; }
        catch (Exception ignored) { return 0L; }
    }

    private String stringValue(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) && item.get(key).s() != null ? item.get(key).s() : "";
    }

    /**
     * Format aggregated stats into response
     */
    private Map<String, Object> formatStats(Map<String, Long> stats) {
        long kills = stats.getOrDefault("total_kills", 0L);
        long deaths = stats.getOrDefault("total_deaths", 0L);
        long matches = stats.getOrDefault("matches_played", 0L);
        long wins = stats.getOrDefault("wins", 0L);
        long losses = stats.getOrDefault("losses", 0L);
        long draws = stats.getOrDefault("draws", 0L);
        long score = stats.getOrDefault("total_score", 0L);
        long damage = stats.getOrDefault("total_damage", 0L);
        long totalRounds = stats.getOrDefault("total_rounds", 0L);

        long head = stats.getOrDefault("total_headshots", 0L);
        long body = stats.getOrDefault("total_bodyshots", 0L);
        long leg = stats.getOrDefault("total_legshots", 0L);
        long totalShots = head + body + leg;

        double kd = deaths > 0 ? (double) kills / deaths : kills;
        double hs = totalShots > 0 ? (double) head / totalShots * 100 : 0;

        double acs = totalRounds > 0 ? (double) score / totalRounds : 0;
        double kpr = totalRounds > 0 ? (double) kills / totalRounds : 0;
        double adr = totalRounds > 0 ? (double) damage / totalRounds : 0;
        double winRate = wins + losses > 0 ? (double) wins / (wins + losses) * 100 : 0;

        Map<String, Object> data = new HashMap<>();
        data.put("matches_played", matches);
        data.put("wins", wins);
        data.put("losses", losses);
        data.put("draws", draws);
        data.put("win_rate", round(winRate));
        data.put("kd_ratio", round(kd));
        data.put("headshot_percent", round(hs));
        data.put("avg_combat_score", round(acs));
        data.put("kills_per_round", Math.round(kpr * 1000.0) / 1000.0);
        data.put("adr", Math.round(adr * 100.0) / 100.0);

        return Map.of("status", 200, "data", data);
    }

    /**
     * Get only ADR for a player
     */
    public Map<String, Object> getPlayerAdr(
            String puuid,
            String region,
            String name,
            String tag,
            String seasonId,
            String mode
    ) {
        Map<String, Object> stats = getPlayerStats(puuid, region, name, tag, seasonId, mode);
        if (!Objects.equals(stats.get("status"), 200)) {
            return stats;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) stats.get("data");
        return Map.of("status", 200, "data", Map.of("adr", data.getOrDefault("adr", 0.0)));
    }

    /**
     * Read stats from a DynamoDB item
     */
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

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}

