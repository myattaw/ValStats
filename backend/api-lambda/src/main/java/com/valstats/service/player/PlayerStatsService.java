package com.valstats.service.player;

import com.valstats.client.ValorantApiClient;
import com.valstats.model.stored.StoredMatchesResponse;
import com.valstats.service.DynamoDbService;
import com.valstats.service.match.MatchProcessor;
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

    private final DynamoDbService dynamoDbService;
    private final ValorantApiClient apiClient;
    private final MatchProcessor matchProcessor;
    private final String apiKey;

    public PlayerStatsService(
            DynamoDbService dynamoDbService,
            ValorantApiClient apiClient,
            MatchProcessor matchProcessor
    ) {
        this.dynamoDbService = dynamoDbService;
        this.apiClient = apiClient;
        this.matchProcessor = matchProcessor;
        this.apiKey = System.getenv("HDEV_KEY");
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
            String seasonId
    ) {
        Map<String, Long> stats;

        if (seasonId == null || seasonId.equalsIgnoreCase("all")) {
            stats = dynamoDbService.getPlayerTotalStats(puuid);

            // Try to load from cache, fall back to API
            if (stats.isEmpty() || stats.getOrDefault("matches_played", 0L) == 0) {
                LOG.info("No cached stats for player {}. Loading from API...", puuid);
                loadPlayerMatchesFromAPI(region, name, tag, puuid);
                stats = dynamoDbService.getPlayerTotalStats(puuid);
            }
        } else {
            Optional<Map<String, AttributeValue>> seasonStats = dynamoDbService.getPlayerSeasonStats(puuid, seasonId);

            if (seasonStats.isEmpty()) {
                LOG.info("No cached stats for season {}. Loading from API...", seasonId);
                loadPlayerMatchesFromAPI(region, name, tag, puuid);
                seasonStats = dynamoDbService.getPlayerSeasonStats(puuid, seasonId);
            }

            stats = seasonStats.isPresent() ? readStatsItem(seasonStats.get()) : new HashMap<>();
        }

        return formatStats(stats);
    }

    /**
     * Load player matches from API and process them into stats
     */
    private void loadPlayerMatchesFromAPI(String region, String name, String tag, String puuid) {
        try {
            StoredMatchesResponse storedApi = apiClient.getStoredMatches(
                    region, name, tag, 20, 1, "competitive"
            );

            List<StoredMatchesResponse.StoredMatch> storedMatches =
                    storedApi != null && storedApi.data() != null ? storedApi.data() : List.of();

            int processed = 0;
            for (StoredMatchesResponse.StoredMatch match : storedMatches) {
                try {
                    if (matchProcessor.processStoredMatchSummary(match, puuid)) {
                        processed++;
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to process match summary", e);
                }
            }

            LOG.info("Processed {} matches for player {}", processed, puuid);
        } catch (Exception e) {
            LOG.error("Failed to load matches from API", e);
        }
    }

    /**
     * Format aggregated stats into response
     */
    private Map<String, Object> formatStats(Map<String, Long> stats) {
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

        double acs = totalRounds > 0 ? (double) score / totalRounds : 0;
        double kpr = totalRounds > 0 ? (double) kills / totalRounds : 0;
        double adr = totalRounds > 0 ? (double) damage / totalRounds : 0;

        Map<String, Object> data = new HashMap<>();
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
            String seasonId
    ) {
        Map<String, Object> stats = getPlayerStats(puuid, region, name, tag, seasonId);
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

