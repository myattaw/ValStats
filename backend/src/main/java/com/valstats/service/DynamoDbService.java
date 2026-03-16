package com.valstats.service;

import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;

@Singleton
public class DynamoDbService {

    private static final Logger LOG = LoggerFactory.getLogger(DynamoDbService.class);

    private final DynamoDbClient dbClient;
    private final String tableName = "valstats";

    public DynamoDbService(DynamoDbClient dbClient) {
        this.dbClient = dbClient;
    }

    /**
     * Runs once when the application starts.
     * Verifies DynamoDB connectivity, region, IAM permissions, and table existence.
     */
    @EventListener
    void onStartup(StartupEvent event) {
        try {
            dbClient.describeTable(
                    DescribeTableRequest.builder()
                            .tableName(tableName)
                            .build()
            );

            LOG.info("Successfully connected to DynamoDB table: {}", tableName);

        } catch (ResourceNotFoundException e) {
            LOG.error("DynamoDB table '{}' does not exist", tableName, e);
        } catch (DynamoDbException e) {
            LOG.error("Failed to connect to DynamoDB", e);
        }
    }

    public void putItem(Map<String, AttributeValue> item) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();

        dbClient.putItem(request);
    }

    public QueryResponse queryByPk(String pk) {
        return dbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(pk)
                ))
                .build());
    }

    /**
     * Get stored matches for a player with pagination.
     * Returns matches ordered by game start time (newest first).
     */
    public List<Map<String, AttributeValue>> getStoredMatchesForPlayer(String puuid, int size, int page) {
        // Query player match markers - these have PK = "PLAYER#<puuid>" and SK starts with "MATCH#"
        // But the current structure uses PK = "PLAYER#<puuid>#MATCH#<matchId>" which doesn't work for queries

        // Instead, query from the player's processed matches using the marker structure
        // Markers are stored with: PK = "PLAYER#<puuid>#MATCH#<matchId>", SK = "MARKER"
        // We need to use a different approach - query matches from MATCH# entries

        // For now, let's query the player's season aggregates to get match IDs
        // Or use a scan with filter (temporary solution)

        try {
            // Use scan with filter for player match markers (not efficient but works)
            // TODO: Create a GSI for efficient player-match lookups
            ScanResponse response = dbClient.scan(ScanRequest.builder()
                    .tableName(tableName)
                    .filterExpression("begins_with(PK, :prefix) AND SK = :marker")
                    .expressionAttributeValues(Map.of(
                            ":prefix", AttributeValue.fromS("PLAYER#" + puuid + "#MATCH#"),
                            ":marker", AttributeValue.fromS("MARKER")
                    ))
                    .build());

            List<Map<String, AttributeValue>> allMatches = new java.util.ArrayList<>(response.items());

            // Sort by gameStart descending (newest first)
            allMatches.sort((a, b) -> {
                long aStart = a.containsKey("gameStart") ? Long.parseLong(a.get("gameStart").n()) : 0;
                long bStart = b.containsKey("gameStart") ? Long.parseLong(b.get("gameStart").n()) : 0;
                return Long.compare(bStart, aStart);
            });

            // Apply pagination
            int startIndex = (page - 1) * size;
            int endIndex = Math.min(startIndex + size, allMatches.size());

            if (startIndex >= allMatches.size()) {
                return Collections.emptyList();
            }

            return allMatches.subList(startIndex, endIndex);
        } catch (DynamoDbException e) {
            LOG.error("Error getting stored matches for player: {}", puuid, e);
            return Collections.emptyList();
        }
    }

    /**
     * Get full match data by match ID.
     */
    public Optional<Map<String, AttributeValue>> getMatchById(String matchId) {
        try {
            GetItemResponse response = dbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("MATCH#" + matchId),
                            "SK", AttributeValue.fromS("METADATA")
                    ))
                    .build());

            if (response.hasItem() && !response.item().isEmpty()) {
                return Optional.of(response.item());
            }
        } catch (DynamoDbException e) {
            LOG.error("Error getting match: {}", matchId, e);
        }
        return Optional.empty();
    }

    /**
     * Get all players in a match.
     */
    public List<Map<String, AttributeValue>> getMatchPlayers(String matchId) {
        QueryResponse response = dbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :playerPrefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("MATCH#" + matchId),
                        ":playerPrefix", AttributeValue.fromS("PLAYER#")
                ))
                .build());

        return response.items();
    }

    /**
     * Store MMR history entry for a player.
     */
    public void storeMMREntry(String puuid, String matchId, int rr, int mmr, String rank, long timestamp) {
        String now = Instant.now().toString();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS("PLAYER#" + puuid));
        item.put("SK", AttributeValue.fromS("MMR#" + timestamp + "#" + matchId));
        item.put("matchId", AttributeValue.fromS(matchId));
        item.put("rr", AttributeValue.fromN(String.valueOf(rr)));
        item.put("mmr", AttributeValue.fromN(String.valueOf(mmr)));
        item.put("rank", AttributeValue.fromS(rank));
        item.put("timestamp", AttributeValue.fromN(String.valueOf(timestamp)));
        item.put("storedAt", AttributeValue.fromS(now));

        try {
            dbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                    .build());

        } catch (ConditionalCheckFailedException e) {
            // Already exists
            LOG.debug("MMR entry already exists for match {} player {}", matchId, puuid);
        } catch (DynamoDbException e) {
            LOG.error("Failed to store MMR entry", e);
        }
    }

    /**
     * Get MMR history for a player.
     */
    public List<Map<String, AttributeValue>> getMMRHistory(String puuid) {
        QueryResponse response = dbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :mmrPrefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("PLAYER#" + puuid),
                        ":mmrPrefix", AttributeValue.fromS("MMR#")
                ))
                .scanIndexForward(false) // Newest first
                .build());

        return response.items();
    }

    /**
     * Get player season aggregates.
     */
    public List<Map<String, AttributeValue>> getPlayerSeasonStats(String puuid) {
        QueryResponse response = dbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :seasonPrefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("PLAYER#" + puuid),
                        ":seasonPrefix", AttributeValue.fromS("SEASON#")
                ))
                .build());

        return response.items();
    }

    /**
     * Get player stats for a specific season.
     */
    public Optional<Map<String, AttributeValue>> getPlayerSeasonStats(String puuid, String seasonId) {
        try {
            GetItemResponse response = dbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("PLAYER#" + puuid),
                            "SK", AttributeValue.fromS("SEASON#" + seasonId)
                    ))
                    .build());

            if (response.hasItem() && !response.item().isEmpty()) {
                return Optional.of(response.item());
            }
        } catch (DynamoDbException e) {
            LOG.error("Error getting season stats for player {} season {}", puuid, seasonId, e);
        }
        return Optional.empty();
    }

    /**
     * Get aggregated stats across all seasons for a player.
     */
    public Map<String, Long> getPlayerTotalStats(String puuid) {
        List<Map<String, AttributeValue>> seasonStats = getPlayerSeasonStats(puuid);

        Map<String, Long> totals = new HashMap<>();
        totals.put("matches_played", 0L);
        totals.put("total_kills", 0L);
        totals.put("total_deaths", 0L);
        totals.put("total_assists", 0L);
        totals.put("total_score", 0L);
        totals.put("total_headshots", 0L);
        totals.put("total_bodyshots", 0L);
        totals.put("total_legshots", 0L);
        totals.put("total_damage", 0L);

        for (Map<String, AttributeValue> season : seasonStats) {
            for (String key : totals.keySet()) {
                if (season.containsKey(key)) {
                    totals.put(key, totals.get(key) + Long.parseLong(season.get(key).n()));
                }
            }
        }

        return totals;
    }
}
