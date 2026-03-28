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
        try {
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();
            Map<String, AttributeValue> lastEvaluatedKey = null;

            do {
                QueryRequest.Builder builder = QueryRequest.builder()
                        .tableName(tableName)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :seasonPrefix)")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.fromS("PLAYER#" + puuid),
                                ":seasonPrefix", AttributeValue.fromS("SEASON#")
                        ))
                        .scanIndexForward(false); // newest first

                if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
                    builder.exclusiveStartKey(lastEvaluatedKey);
                }

                QueryResponse response = dbClient.query(builder.build());
                allItems.addAll(response.items());
                lastEvaluatedKey = response.lastEvaluatedKey();

            } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

            // ONLY KEEP MATCH ROWS
            allItems = allItems.stream()
                    .filter(item -> item.get("SK").s().contains("#MATCH#"))
                    .toList();


            // PAGINATION AFTER FILTERING
            if (!allItems.isEmpty()) {
                int startIndex = Math.max(0, (page - 1) * size);
                int endIndex = Math.min(startIndex + size, allItems.size());

                return startIndex >= allItems.size()
                        ? Collections.emptyList()
                        : allItems.subList(startIndex, endIndex);
            }

            // =========================
            // FALLBACK (OLD SCHEMA)
            // =========================
            List<Map<String, AttributeValue>> fallbackItems = new ArrayList<>();
            Map<String, AttributeValue> scanLastKey = null;

            do {
                ScanRequest.Builder scanBuilder = ScanRequest.builder()
                        .tableName(tableName)
                        .filterExpression("begins_with(PK, :prefix) AND SK = :marker")
                        .expressionAttributeValues(Map.of(
                                ":prefix", AttributeValue.fromS("PLAYER#" + puuid + "#MATCH#"),
                                ":marker", AttributeValue.fromS("MARKER")
                        ));

                if (scanLastKey != null && !scanLastKey.isEmpty()) {
                    scanBuilder.exclusiveStartKey(scanLastKey);
                }

                ScanResponse fallback = dbClient.scan(scanBuilder.build());
                fallbackItems.addAll(fallback.items());
                scanLastKey = fallback.lastEvaluatedKey();

            } while (scanLastKey != null && !scanLastKey.isEmpty());

            // sort fallback
            fallbackItems.sort((a, b) -> Long.compare(
                    b.containsKey("gameStart") ? Long.parseLong(b.get("gameStart").n()) : 0L,
                    a.containsKey("gameStart") ? Long.parseLong(a.get("gameStart").n()) : 0L
            ));

            int startIndex = Math.max(0, (page - 1) * size);
            int endIndex = Math.min(startIndex + size, fallbackItems.size());

            return startIndex >= fallbackItems.size()
                    ? Collections.emptyList()
                    : fallbackItems.subList(startIndex, endIndex);

        } catch (DynamoDbException e) {
            LOG.error("Error getting stored matches for player: {}", puuid, e);
            return Collections.emptyList();
        }
    }

    public QueryResponse getMatchesFromGSI(
            String puuid,
            int size,
            Map<String, AttributeValue> lastKey
    ) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .indexName("GSI1") // 🔥 THIS FIXES EVERYTHING
                .keyConditionExpression("GSI1PK = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("PLAYER#" + puuid)
                ))
                .scanIndexForward(false) // newest first
                .limit(size);

        if (lastKey != null && !lastKey.isEmpty()) {
            builder.exclusiveStartKey(lastKey);
        }

        return dbClient.query(builder.build());
    }

    public QueryResponse getMatchesBySeasonPaginated(
            String puuid,
            String act,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :seasonPrefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("PLAYER#" + puuid),
                        ":seasonPrefix", AttributeValue.fromS("SEASON#" + act)
                ))
                .limit(limit)
                .scanIndexForward(false); // newest first

        if (exclusiveStartKey != null) {
            builder.exclusiveStartKey(exclusiveStartKey);
        }

        return dbClient.query(builder.build());
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
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> lastEvaluatedKey = null;

        do {
            QueryRequest.Builder builder = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("PK = :pk AND begins_with(SK, :playerPrefix)")
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.fromS("MATCH#" + matchId),
                            ":playerPrefix", AttributeValue.fromS("PLAYER#")
                    ));

            if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
                builder.exclusiveStartKey(lastEvaluatedKey);
            }

            QueryResponse response = dbClient.query(builder.build());
            items.addAll(response.items());
            lastEvaluatedKey = response.lastEvaluatedKey();

        } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

        return items;
    }

    /**
     * Store MMR history entry for a player.
     */
    public void storeMMREntry(
            String puuid,
            String matchId,
            int rr,
            int mmr,
            int rankingInTier,
            int currentTier,
            String rank,
            long timestamp
    ){
        String now = Instant.now().toString();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS("PLAYER#" + puuid));
        item.put("SK", AttributeValue.fromS("MMR#" + timestamp + "#" + matchId));
        item.put("matchId", AttributeValue.fromS(matchId));
        item.put("rr", AttributeValue.fromN(String.valueOf(rr)));
        item.put("mmr", AttributeValue.fromN(String.valueOf(mmr)));
        item.put("ranking_in_tier", AttributeValue.fromN(String.valueOf(rankingInTier)));
        item.put("currenttier", AttributeValue.fromN(String.valueOf(currentTier)));
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
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> lastEvaluatedKey = null;

        do {
            QueryRequest.Builder builder = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("PK = :pk AND begins_with(SK, :mmrPrefix)")
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.fromS("PLAYER#" + puuid),
                            ":mmrPrefix", AttributeValue.fromS("MMR#")
                    ))
                    .scanIndexForward(false);

            if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
                builder.exclusiveStartKey(lastEvaluatedKey);
            }

            QueryResponse response = dbClient.query(builder.build());
            items.addAll(response.items());
            lastEvaluatedKey = response.lastEvaluatedKey();

        } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

        return items;
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

    public List<Map<String, AttributeValue>> getMatchesBySeason(
            String puuid,
            String seasonId,
            int size,
            int page
    ) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;

        do {
            QueryRequest.Builder builder = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("PK = :pk AND begins_with(SK, :seasonPrefix)")
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.fromS("PLAYER#" + puuid),
                            ":seasonPrefix", AttributeValue.fromS("SEASON#" + seasonId)
                    ))
                    .scanIndexForward(false);

            if (lastKey != null && !lastKey.isEmpty()) {
                builder.exclusiveStartKey(lastKey);
            }

            QueryResponse response = dbClient.query(builder.build());
            items.addAll(response.items());
            lastKey = response.lastEvaluatedKey();

        } while (lastKey != null && !lastKey.isEmpty());

        items = items.stream()
                .filter(item -> item.get("SK").s().contains("#MATCH#"))
                .toList();

        // pagination
        int startIndex = Math.max(0, (page - 1) * size);
        int endIndex = Math.min(startIndex + size, items.size());

        return startIndex >= items.size()
                ? Collections.emptyList()
                : items.subList(startIndex, endIndex);
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
        try {
            GetItemResponse response = dbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("PLAYER#" + puuid),
                            "SK", AttributeValue.fromS("TOTAL")
                    ))
                    .build());

            if (!response.hasItem() || response.item().isEmpty()) {
                return new HashMap<>();
            }

            Map<String, AttributeValue> item = response.item();
            Map<String, Long> stats = new HashMap<>();

            stats.put("matches_played", getLong(item, "matches_played"));
            stats.put("total_kills", getLong(item, "total_kills"));
            stats.put("total_deaths", getLong(item, "total_deaths"));
            stats.put("total_assists", getLong(item, "total_assists"));
            stats.put("total_score", getLong(item, "total_score"));
            stats.put("total_headshots", getLong(item, "total_headshots"));
            stats.put("total_bodyshots", getLong(item, "total_bodyshots"));
            stats.put("total_legshots", getLong(item, "total_legshots"));
            stats.put("total_damage", getLong(item, "total_damage"));
            stats.put("total_rounds", getLong(item, "total_rounds"));

            return stats;

        } catch (Exception e) {
            LOG.error("Failed to get totals", e);
            return new HashMap<>();
        }
    }

    // ADD THIS METHOD
    public void updatePlayerTotals(
            String puuid,
            int kills,
            int deaths,
            int assists,
            int score,
            int headshots,
            int bodyshots,
            int legshots
    ) {
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS("PLAYER#" + puuid),
                "SK", AttributeValue.fromS("TOTAL")
        );

        Map<String, String> names = Map.of(
                "#mp", "matches_played"
        );

        Map<String, AttributeValue> values = Map.of(
                ":one", AttributeValue.fromN("1"),
                ":kills", AttributeValue.fromN(String.valueOf(kills)),
                ":deaths", AttributeValue.fromN(String.valueOf(deaths)),
                ":assists", AttributeValue.fromN(String.valueOf(assists)),
                ":score", AttributeValue.fromN(String.valueOf(score)),
                ":hs", AttributeValue.fromN(String.valueOf(headshots)),
                ":bs", AttributeValue.fromN(String.valueOf(bodyshots)),
                ":ls", AttributeValue.fromN(String.valueOf(legshots))
        );

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("""
                ADD #mp :one,
                    total_kills :kills,
                    total_deaths :deaths,
                    total_assists :assists,
                    total_score :score,
                    total_headshots :hs,
                    total_bodyshots :bs,
                    total_legshots :ls
            """)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build();

        dbClient.updateItem(request);
    }

    /**
     * Get the last time a player's recently played matches were updated.
     * Used to enforce the 5-minute cooldown for the matches endpoint.
     */
    public Optional<Long> getPlayerLastRecentMatchUpdate(String region, String name, String tag) {
        try {
            String pk = String.format("PLAYER_UPDATE#%s#%s#%s", region, name, tag);
            GetItemResponse response = dbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS(pk),
                            "SK", AttributeValue.fromS("RECENT_MATCHES")
                    ))
                    .projectionExpression("updatedAt")
                    .build());

            if (response.hasItem() && response.item().containsKey("updatedAt")) {
                return Optional.of(Long.parseLong(response.item().get("updatedAt").n()));
            }
        } catch (Exception e) {
            LOG.debug("Error getting last recent match update time", e);
        }
        return Optional.empty();
    }

    /**
     * Update the timestamp for when a player's recently played matches were last updated.
     */
    public void updatePlayerLastRecentMatchUpdate(String region, String name, String tag) {
        try {
            String pk = String.format("PLAYER_UPDATE#%s#%s#%s", region, name, tag);
            long now = System.currentTimeMillis() / 1000;

            dbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS(pk),
                            "SK", AttributeValue.fromS("RECENT_MATCHES")
                    ))
                    .updateExpression("SET updatedAt = :now")
                    .expressionAttributeValues(Map.of(
                            ":now", AttributeValue.fromN(String.valueOf(now))
                    ))
                    .build());

            LOG.debug("Updated recent match timestamp for {}#{} in {}", name, tag, region);
        } catch (DynamoDbException e) {
            LOG.error("Failed to update recent match timestamp", e);
        }
    }

    private long getLong(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? Long.parseLong(map.get(key).n()) : 0L;
    }

}

