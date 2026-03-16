package com.valstats.service;

import com.valstats.client.ValorantApiClient;
import com.valstats.model.Match;
import com.valstats.model.MatchResponse;
import com.valstats.model.assets.Assets;
import com.valstats.model.player.Player;
import com.valstats.model.player.Players;
import com.valstats.model.player.Stats;
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
    private final MatchProcessor matchProcessor;
    private final DynamoDbService dynamoDbService;
    private static final String AUTH_TOKEN = System.getenv("HDEV_KEY");

    public ValorantService(
            ValorantApiClient valorantApiClient,
            PlayerCacheService playerCacheService,
            MatchProcessor matchProcessor,
            DynamoDbService dynamoDbService
    ) {
        this.valorantApiClient = valorantApiClient;
        this.playerCacheService = playerCacheService;
        this.matchProcessor = matchProcessor;
        this.dynamoDbService = dynamoDbService;
    }

    public MatchResponse getRecentMatches(String region, String playerName, String playerTag, int size, int page) {
        // First, get or lookup the player's puuid
        String puuid = resolvePuuid(playerName, playerTag);

        if (puuid == null) {
            LOG.warn("Could not resolve puuid for {}#{}", playerName, playerTag);
            return new MatchResponse(404, Collections.emptyList());
        }

        // Check if we can fetch from the external API
        if (!playerCacheService.canFetchFromApi(puuid)) {
            long waitTime = playerCacheService.getSecondsUntilNextFetch(puuid);
            LOG.info("API cooldown active for recent-matches. {} seconds remaining.", waitTime);
            return getCachedMatches(puuid, size, page);
        }

        try {
            MatchResponse rawResponse = valorantApiClient.getRecentMatches(region, playerName, playerTag, size, page, AUTH_TOKEN);
            MatchResponse filteredResponse = filterMatchesResponse(rawResponse);

            // Store new matches in DynamoDB
            int newMatchCount = 0;
            for (Match match : filteredResponse.data()) {
                if (matchProcessor.processMatch(match, puuid)) {
                    newMatchCount++;
                }
            }
            LOG.info("Stored {} new matches for player {}#{}", newMatchCount, playerName, playerTag);

            // Update the last fetch time
            playerCacheService.updateLastFetchTime(puuid, playerName, playerTag, region);

            return filteredResponse;
        } catch (Exception e) {
            LOG.error("Failed to fetch from external API, falling back to database", e);
            return getCachedMatches(puuid, size, page);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getStoredMatches(String region, String playerName, String playerTag, int size, int page) {
        // Get or lookup the player's puuid
        String puuid = resolvePuuid(playerName, playerTag);

        if (puuid == null) {
            // Player doesn't exist in our database, fetch from API
            LOG.info("Player {}#{} not found in database, fetching from API", playerName, playerTag);

            try {
                Map<String, Object> apiResponse = valorantApiClient.getStoredMatches(region, playerName, playerTag, size, page, "competitive", AUTH_TOKEN);

                // Extract puuid from account endpoint and store
                Map<String, Object> accountData = getAccountDetails(playerName, playerTag);
                if (accountData.containsKey("data")) {
                    Map<String, Object> data = (Map<String, Object>) accountData.get("data");
                    String newPuuid = (String) data.get("puuid");
                    if (newPuuid != null) {
                        playerCacheService.storePlayerProfile(newPuuid, playerName, playerTag, region);
                        playerCacheService.updateLastFetchTime(newPuuid, playerName, playerTag, region);
                    }
                }

                return apiResponse;
            } catch (Exception e) {
                LOG.error("Failed to fetch stored matches for new player", e);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", 500);
                errorResponse.put("error", "Failed to fetch matches");
                return errorResponse;
            }
        }

        // Player exists, check cooldown
        if (playerCacheService.canFetchFromApi(puuid)) {
            try {
                Map<String, Object> apiResponse = valorantApiClient.getStoredMatches(region, playerName, playerTag, size, page, "competitive", AUTH_TOKEN);
                playerCacheService.updateLastFetchTime(puuid, playerName, playerTag, region);
                return apiResponse;
            } catch (Exception e) {
                LOG.error("Failed to fetch stored matches from API", e);
            }
        } else {
            long waitTime = playerCacheService.getSecondsUntilNextFetch(puuid);
            LOG.debug("API cooldown active for stored-matches. {} seconds remaining.", waitTime);
        }

        // Return from database
        List<Map<String, AttributeValue>> storedMatches = dynamoDbService.getStoredMatchesForPlayer(puuid, size, page);

        List<Map<String, Object>> matchData = storedMatches.stream()
                .map(this::convertMatchToResponseFormat)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", matchData);
        response.put("cached", true);

        return response;
    }

    public Map<String, Object> getMMRHistory(String region, String playerName, String playerTag) {
        String puuid = resolvePuuid(playerName, playerTag);

        if (puuid == null) {
            // Fetch from API and store
            try {
                Map<String, Object> apiResponse = valorantApiClient.getMMRHistory(region, playerName, playerTag, AUTH_TOKEN);
                storeMMRHistoryFromResponse(apiResponse, playerName, playerTag);
                return apiResponse;
            } catch (Exception e) {
                LOG.error("Failed to fetch MMR history for new player", e);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", 500);
                errorResponse.put("error", "Failed to fetch MMR history");
                return errorResponse;
            }
        }

        // Check cooldown
        if (playerCacheService.canFetchFromApi(puuid)) {
            try {
                Map<String, Object> apiResponse = valorantApiClient.getMMRHistory(region, playerName, playerTag, AUTH_TOKEN);
                storeMMRHistoryFromResponse(apiResponse, playerName, playerTag);
                // Don't update fetch time here - let recent-matches handle it
                return apiResponse;
            } catch (Exception e) {
                LOG.error("Failed to fetch MMR history from API", e);
            }
        } else {
            LOG.debug("API cooldown active for mmr-history. Returning cached data.");
        }

        // Return cached MMR history
        List<Map<String, AttributeValue>> mmrHistory = dynamoDbService.getMMRHistory(puuid);

        List<Map<String, Object>> historyData = mmrHistory.stream()
                .map(this::convertMMREntryToResponseFormat)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", historyData);
        response.put("cached", true);

        return response;
    }

    @SuppressWarnings("unchecked")
    private void storeMMRHistoryFromResponse(Map<String, Object> apiResponse, String playerName, String playerTag) {
        String puuid = resolvePuuid(playerName, playerTag);
        if (puuid == null || !apiResponse.containsKey("data")) {
            return;
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) apiResponse.get("data");
        for (Map<String, Object> entry : entries) {
            String matchId = (String) entry.get("match_id");
            Number rr = (Number) entry.getOrDefault("mmr_change_to_last_game", 0);
            Number mmr = (Number) entry.getOrDefault("elo", 0);
            String rank = (String) entry.getOrDefault("currenttierpatched", "Unknown");
            Number timestamp = (Number) entry.getOrDefault("date_raw", System.currentTimeMillis() / 1000);

            if (matchId != null) {
                dynamoDbService.storeMMREntry(
                        puuid,
                        matchId,
                        rr.intValue(),
                        mmr.intValue(),
                        rank,
                        timestamp.longValue()
                );
            }
        }
    }

    private String resolvePuuid(String playerName, String playerTag) {
        // Try to get from cache first
        Optional<String> cachedPuuid = playerCacheService.getPuuidByNameTag(playerName, playerTag);
        if (cachedPuuid.isPresent()) {
            return cachedPuuid.get();
        }

        // Fetch from API
        try {
            Map<String, Object> accountData = getAccountDetails(playerName, playerTag);
            if (accountData.containsKey("data")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) accountData.get("data");
                String puuid = (String) data.get("puuid");
                String region = (String) data.getOrDefault("region", "na");

                if (puuid != null) {
                    // Store the profile for future lookups
                    playerCacheService.storePlayerProfile(puuid, playerName, playerTag, region);
                    return puuid;
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to resolve puuid for {}#{}", playerName, playerTag, e);
        }

        return null;
    }

    private MatchResponse getCachedMatches(String puuid, int size, int page) {
        List<Map<String, AttributeValue>> storedMatches = dynamoDbService.getStoredMatchesForPlayer(puuid, size, page);

        // For now, return empty if no cached data (full match reconstruction would be complex)
        // In production, you'd reconstruct Match objects from stored data
        if (storedMatches.isEmpty()) {
            return new MatchResponse(200, Collections.emptyList());
        }

        // This is a simplified response - full implementation would reconstruct Match objects
        return new MatchResponse(200, Collections.emptyList());
    }

    private Map<String, Object> convertMatchToResponseFormat(Map<String, AttributeValue> item) {
        Map<String, Object> match = new HashMap<>();

        if (item.containsKey("matchId")) {
            match.put("match_id", item.get("matchId").s());
        }
        if (item.containsKey("gameStart")) {
            match.put("game_start", Long.parseLong(item.get("gameStart").n()));
        }

        return match;
    }

    private Map<String, Object> convertMMREntryToResponseFormat(Map<String, AttributeValue> item) {
        Map<String, Object> entry = new HashMap<>();

        if (item.containsKey("matchId")) {
            entry.put("match_id", item.get("matchId").s());
        }
        if (item.containsKey("rr")) {
            entry.put("mmr_change_to_last_game", Integer.parseInt(item.get("rr").n()));
        }
        if (item.containsKey("mmr")) {
            entry.put("elo", Integer.parseInt(item.get("mmr").n()));
        }
        if (item.containsKey("rank")) {
            entry.put("currenttierpatched", item.get("rank").s());
        }
        if (item.containsKey("timestamp")) {
            entry.put("date_raw", Long.parseLong(item.get("timestamp").n()));
        }

        return entry;
    }

    private MatchResponse filterMatchesResponse(MatchResponse rawResponse) {
        List<Match> filteredData = rawResponse.data().stream()
                .map(match -> {
                    List<Player> filteredPlayers = match.players().all_players().stream()
                            .map(player -> new Player(
                                    player.puuid(),
                                    player.name(),
                                    player.tag(),
                                    player.team(),
                                    player.character(),
                                    player.currenttier(),
                                    player.currenttier_patched(),
                                    new Stats(
                                            player.stats().score(),
                                            player.stats().kills(),
                                            player.stats().deaths(),
                                            player.stats().assists(),
                                            player.stats().bodyshots(),
                                            player.stats().headshots(),
                                            player.stats().legshots()
                                    ),
                                    player.assets() == null ? null : new Assets(player.assets().card() == null ? null :
                                            new Assets.Card(player.assets().card().small()),
                                            player.assets().agent() == null ? null : new Assets.Agent(player.assets().agent().small())
                                    ), player.damage_made()
                            ))
                            .collect(Collectors.toList());
                    // Pass through metadata and teams as well
                    return new Match(match.metadata(), new Players(filteredPlayers), match.teams());
                })
                .collect(Collectors.toList());
        return new MatchResponse(rawResponse.status(), filteredData);
    }

    public Map<String, Object> getAccountDetails(String name, String tag) {
        return valorantApiClient.getAccount(name, tag, AUTH_TOKEN);
    }

    public Map<String, Object> getMatchById(String matchid) {
        // Check database first
        Optional<Map<String, AttributeValue>> cachedMatch = dynamoDbService.getMatchById(matchid);

        if (cachedMatch.isPresent()) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", 200);
            response.put("cached", true);

            Map<String, Object> matchData = new HashMap<>();
            Map<String, AttributeValue> item = cachedMatch.get();

            // Build metadata
            Map<String, Object> metadata = new HashMap<>();
            if (item.containsKey("matchId")) metadata.put("matchid", item.get("matchId").s());
            if (item.containsKey("map")) metadata.put("map", item.get("map").s());
            if (item.containsKey("mode")) metadata.put("mode", item.get("mode").s());
            if (item.containsKey("gameStart")) metadata.put("game_start", Long.parseLong(item.get("gameStart").n()));
            if (item.containsKey("gameLength"))
                metadata.put("game_length", Integer.parseInt(item.get("gameLength").n()));

            matchData.put("metadata", metadata);

            // Get players
            List<Map<String, AttributeValue>> players = dynamoDbService.getMatchPlayers(matchid);
            List<Map<String, Object>> allPlayers = players.stream()
                    .map(this::convertPlayerToResponseFormat)
                    .collect(Collectors.toList());

            Map<String, Object> playersMap = new HashMap<>();
            playersMap.put("all_players", allPlayers);
            matchData.put("players", playersMap);

            response.put("data", matchData);
            return response;
        }

        // Fetch from API
        return valorantApiClient.getMatchById(matchid, AUTH_TOKEN);
    }

    private Map<String, Object> convertPlayerToResponseFormat(Map<String, AttributeValue> item) {
        Map<String, Object> player = new HashMap<>();

        if (item.containsKey("puuid")) player.put("puuid", item.get("puuid").s());
        if (item.containsKey("name")) player.put("name", item.get("name").s());
        if (item.containsKey("tag")) player.put("tag", item.get("tag").s());
        if (item.containsKey("team")) player.put("team", item.get("team").s());
        if (item.containsKey("character")) player.put("character", item.get("character").s());
        if (item.containsKey("currenttier")) player.put("currenttier", Integer.parseInt(item.get("currenttier").n()));

        Map<String, Object> stats = new HashMap<>();
        if (item.containsKey("kills")) stats.put("kills", Integer.parseInt(item.get("kills").n()));
        if (item.containsKey("deaths")) stats.put("deaths", Integer.parseInt(item.get("deaths").n()));
        if (item.containsKey("assists")) stats.put("assists", Integer.parseInt(item.get("assists").n()));
        if (item.containsKey("score")) stats.put("score", Integer.parseInt(item.get("score").n()));
        if (item.containsKey("headshots")) stats.put("headshots", Integer.parseInt(item.get("headshots").n()));
        if (item.containsKey("bodyshots")) stats.put("bodyshots", Integer.parseInt(item.get("bodyshots").n()));
        if (item.containsKey("legshots")) stats.put("legshots", Integer.parseInt(item.get("legshots").n()));

        player.put("stats", stats);

        if (item.containsKey("damage_made")) player.put("damage_made", Integer.parseInt(item.get("damage_made").n()));

        return player;
    }

    /**
     * Get kill/death ratio for a player.
     */
    public Map<String, Object> getKillRatio(String region, String playerName, String playerTag, String seasonId) {
        String puuid = resolvePuuid(playerName, playerTag);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        Map<String, Long> stats = getStatsForSeason(puuid, seasonId);
        if (stats == null) {
            return errorResponse("No stats found for player");
        }

        long kills = stats.getOrDefault("total_kills", 0L);
        long deaths = stats.getOrDefault("total_deaths", 0L);
        double kdRatio = deaths > 0 ? (double) kills / deaths : kills;

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", Map.of(
                "player", playerName + "#" + playerTag,
                "season", seasonId,
                "kills", kills,
                "deaths", deaths,
                "kd_ratio", Math.round(kdRatio * 100.0) / 100.0,
                "matches_played", stats.getOrDefault("matches_played", 0L)
        ));
        return response;
    }

    /**
     * Get headshot percentage for a player.
     */
    public Map<String, Object> getHeadshotPercent(String region, String playerName, String playerTag, String seasonId) {
        String puuid = resolvePuuid(playerName, playerTag);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        Map<String, Long> stats = getStatsForSeason(puuid, seasonId);
        if (stats == null) {
            return errorResponse("No stats found for player");
        }

        long headshots = stats.getOrDefault("total_headshots", 0L);
        long bodyshots = stats.getOrDefault("total_bodyshots", 0L);
        long legshots = stats.getOrDefault("total_legshots", 0L);
        long totalShots = headshots + bodyshots + legshots;

        double hsPercent = totalShots > 0 ? (double) headshots / totalShots * 100 : 0;

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", Map.of(
                "player", playerName + "#" + playerTag,
                "season", seasonId,
                "headshots", headshots,
                "bodyshots", bodyshots,
                "legshots", legshots,
                "total_shots", totalShots,
                "headshot_percent", Math.round(hsPercent * 100.0) / 100.0,
                "matches_played", stats.getOrDefault("matches_played", 0L)
        ));
        return response;
    }

    /**
     * Get average combat score for a player.
     */
    public Map<String, Object> getAvgCombatScore(String region, String playerName, String playerTag, String seasonId) {
        String puuid = resolvePuuid(playerName, playerTag);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        Map<String, Long> stats = getStatsForSeason(puuid, seasonId);
        if (stats == null) {
            return errorResponse("No stats found for player");
        }

        long totalScore = stats.getOrDefault("total_score", 0L);
        long matchesPlayed = stats.getOrDefault("matches_played", 0L);

        double avgScore = matchesPlayed > 0 ? (double) totalScore / matchesPlayed : 0;

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", Map.of(
                "player", playerName + "#" + playerTag,
                "season", seasonId,
                "total_score", totalScore,
                "matches_played", matchesPlayed,
                "avg_combat_score", Math.round(avgScore * 100.0) / 100.0
        ));
        return response;
    }

    /**
     * Get kills per round for a player.
     * Note: This requires tracking total rounds played, which we'll estimate from matches.
     */
    public Map<String, Object> getKillsPerRound(String region, String playerName, String playerTag, String seasonId) {
        String puuid = resolvePuuid(playerName, playerTag);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        Map<String, Long> stats = getStatsForSeason(puuid, seasonId);
        if (stats == null) {
            return errorResponse("No stats found for player");
        }

        long kills = stats.getOrDefault("total_kills", 0L);
        long matchesPlayed = stats.getOrDefault("matches_played", 0L);

        // Estimate rounds: average competitive match is ~20 rounds
        // For accurate data, we'd need to track total_rounds in the aggregate
        long estimatedRounds = matchesPlayed * 20;

        double killsPerRound = estimatedRounds > 0 ? (double) kills / estimatedRounds : 0;

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", Map.of(
                "player", playerName + "#" + playerTag,
                "season", seasonId,
                "kills", kills,
                "matches_played", matchesPlayed,
                "estimated_rounds", estimatedRounds,
                "kills_per_round", Math.round(killsPerRound * 1000.0) / 1000.0,
                "note", "Rounds estimated at 20 per match"
        ));
        return response;
    }

    /**
     * Get comprehensive player stats.
     */
    public Map<String, Object> getPlayerStats(String region, String playerName, String playerTag, String seasonId) {
        String puuid = resolvePuuid(playerName, playerTag);
        if (puuid == null) {
            return errorResponse("Player not found");
        }

        Map<String, Long> stats = getStatsForSeason(puuid, seasonId);
        if (stats == null) {
            return errorResponse("No stats found for player");
        }

        long kills = stats.getOrDefault("total_kills", 0L);
        long deaths = stats.getOrDefault("total_deaths", 0L);
        long assists = stats.getOrDefault("total_assists", 0L);
        long headshots = stats.getOrDefault("total_headshots", 0L);
        long bodyshots = stats.getOrDefault("total_bodyshots", 0L);
        long legshots = stats.getOrDefault("total_legshots", 0L);
        long totalScore = stats.getOrDefault("total_score", 0L);
        long totalDamage = stats.getOrDefault("total_damage", 0L);
        long matchesPlayed = stats.getOrDefault("matches_played", 0L);

        long totalShots = headshots + bodyshots + legshots;
        double kdRatio = deaths > 0 ? (double) kills / deaths : kills;
        double hsPercent = totalShots > 0 ? (double) headshots / totalShots * 100 : 0;
        double avgScore = matchesPlayed > 0 ? (double) totalScore / matchesPlayed : 0;
        double avgDamage = matchesPlayed > 0 ? (double) totalDamage / matchesPlayed : 0;
        long estimatedRounds = matchesPlayed * 20;
        double killsPerRound = estimatedRounds > 0 ? (double) kills / estimatedRounds : 0;

        Map<String, Object> data = new HashMap<>();
        data.put("player", playerName + "#" + playerTag);
        data.put("season", seasonId);
        data.put("matches_played", matchesPlayed);
        data.put("kills", kills);
        data.put("deaths", deaths);
        data.put("assists", assists);
        data.put("kd_ratio", Math.round(kdRatio * 100.0) / 100.0);
        data.put("headshots", headshots);
        data.put("bodyshots", bodyshots);
        data.put("legshots", legshots);
        data.put("headshot_percent", Math.round(hsPercent * 100.0) / 100.0);
        data.put("total_score", totalScore);
        data.put("avg_combat_score", Math.round(avgScore * 100.0) / 100.0);
        data.put("total_damage", totalDamage);
        data.put("avg_damage", Math.round(avgDamage * 100.0) / 100.0);
        data.put("kills_per_round", Math.round(killsPerRound * 1000.0) / 1000.0);

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put("data", data);
        return response;
    }

    /**
     * Helper to get stats for a specific season or all seasons.
     */
    private Map<String, Long> getStatsForSeason(String puuid, String seasonId) {
        if (seasonId == null || seasonId.equalsIgnoreCase("all")) {
            return dynamoDbService.getPlayerTotalStats(puuid);
        }

        Optional<Map<String, AttributeValue>> seasonStats = dynamoDbService.getPlayerSeasonStats(puuid, seasonId);
        if (seasonStats.isEmpty()) {
            return null;
        }

        Map<String, Long> stats = new HashMap<>();
        Map<String, AttributeValue> item = seasonStats.get();

        String[] keys = {"matches_played", "total_kills", "total_deaths", "total_assists",
                         "total_score", "total_headshots", "total_bodyshots", "total_legshots", "total_damage"};

        for (String key : keys) {
            if (item.containsKey(key)) {
                stats.put(key, Long.parseLong(item.get(key).n()));
            } else {
                stats.put(key, 0L);
            }
        }

        return stats;
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 404);
        response.put("error", message);
        return response;
    }
}
