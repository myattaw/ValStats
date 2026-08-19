package com.valstats.service.player;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;

@Singleton
public class PlayerCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerCacheService.class);
    private static final long FETCH_COOLDOWN_SECONDS = 180; // 3 minutes
    private static final long NAME_HISTORY_BACKFILL_SECONDS = 86400; // once per day
    private static final String NAME_HISTORY_META_SK = "NAME_HISTORY_META_V3";
    private static final String NAME_HISTORY_CHECKPOINT_PREFIX = "NAME_HISTORY_CHECKPOINT_V3#";

    private final DynamoDbClient ddb;
    @Value("${dynamodb.table-name:valstats}")
    private String tableName = "valstats";

    public PlayerCacheService(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    /**
     * Get the last time we fetched data for this player from the external API.
     */
    public Optional<Instant> getLastFetchTime(String puuid) {
        try {
            GetItemResponse response = ddb.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("PLAYER#" + puuid),
                            "SK", AttributeValue.fromS("PROFILE")
                    ))
                    .projectionExpression("lastFetchTime")
                    .build());

            if (response.hasItem() && response.item().containsKey("lastFetchTime")) {
                return Optional.of(Instant.parse(response.item().get("lastFetchTime").s()));
            }
        } catch (Exception e) {
            LOG.error("Error getting last fetch time for player: {}", puuid, e);
        }
        return Optional.empty();
    }

    /**
     * Check if we can fetch from the external API (cooldown has passed).
     */
    public boolean canFetchFromApi(String puuid) {
        Optional<Instant> lastFetch = getLastFetchTime(puuid);
        if (lastFetch.isEmpty()) {
            return true; // Never fetched before
        }

        long secondsSinceLastFetch = Instant.now().getEpochSecond() - lastFetch.get().getEpochSecond();
        return secondsSinceLastFetch >= FETCH_COOLDOWN_SECONDS;
    }

    /**
     * Get seconds remaining until next fetch is allowed.
     */
    public long getSecondsUntilNextFetch(String puuid) {
        Optional<Instant> lastFetch = getLastFetchTime(puuid);
        if (lastFetch.isEmpty()) {
            return 0;
        }

        long secondsSinceLastFetch = Instant.now().getEpochSecond() - lastFetch.get().getEpochSecond();
        return Math.max(0, FETCH_COOLDOWN_SECONDS - secondsSinceLastFetch);
    }

    /**
     * Update the last fetch time for a player.
     */
    public void updateLastFetchTime(String puuid, String name, String tag, String region) {
        String now = Instant.now().toString();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS("PLAYER#" + puuid));
        item.put("SK", AttributeValue.fromS("PROFILE"));
        item.put("puuid", AttributeValue.fromS(puuid));
        item.put("name", AttributeValue.fromS(name));
        item.put("tag", AttributeValue.fromS(tag));
        item.put("region", AttributeValue.fromS(region));
        item.put("lastFetchTime", AttributeValue.fromS(now));
        item.put("updatedAt", AttributeValue.fromS(now));

        try {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());
            LOG.debug("Updated last fetch time for player: {}", puuid);
        } catch (DynamoDbException e) {
            LOG.error("Failed to update last fetch time for player: {}", puuid, e);
        }
    }

    /**
     * Store or update player profile with puuid lookup by name#tag.
     */
    public void storePlayerProfile(String puuid, String name, String tag, String region) {
        String now = Instant.now().toString();

        // Store name#tag -> puuid lookup
        String nameTag = (name + "#" + tag).toLowerCase();
        Map<String, AttributeValue> lookupItem = new HashMap<>();
        lookupItem.put("PK", AttributeValue.fromS("LOOKUP#" + nameTag));
        lookupItem.put("SK", AttributeValue.fromS("PUUID"));
        lookupItem.put("puuid", AttributeValue.fromS(puuid));
        lookupItem.put("region", AttributeValue.fromS(region));
        lookupItem.put("updatedAt", AttributeValue.fromS(now));

        try {
            // Update instead of replacing so concurrent identity resolution cannot
            // erase enriched account fields such as card images and account level.
            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("PLAYER#" + puuid),
                            "SK", AttributeValue.fromS("PROFILE")))
                    .updateExpression("SET puuid = :puuid, #n = :name, #t = :tag, #r = :region, updatedAt = :now")
                    .expressionAttributeNames(Map.of("#n", "name", "#t", "tag", "#r", "region"))
                    .expressionAttributeValues(Map.of(
                            ":puuid", AttributeValue.fromS(puuid),
                            ":name", AttributeValue.fromS(name),
                            ":tag", AttributeValue.fromS(tag),
                            ":region", AttributeValue.fromS(region),
                            ":now", AttributeValue.fromS(now)))
                    .build());
            ddb.putItem(PutItemRequest.builder().tableName(tableName).item(lookupItem).build());
            recordPlayerName(puuid, name, tag, Instant.now().getEpochSecond());
        } catch (DynamoDbException e) {
            LOG.error("Failed to store player profile: {}#{}", name, tag, e);
        }
    }

    public void recordPlayerName(String puuid, String name, String tag, long observedAt) {
        if (puuid == null || puuid.isBlank()
                || name == null || name.isBlank() || "null".equalsIgnoreCase(name)
                || tag == null || tag.isBlank() || "null".equalsIgnoreCase(tag)) return;

        String normalized = (name + "#" + tag).toLowerCase(java.util.Locale.ROOT);
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS("PLAYER#" + puuid),
                "SK", AttributeValue.fromS("NAME#" + normalized)
        );
        Map<String, AttributeValue> values = Map.of(
                ":name", AttributeValue.fromS(name),
                ":tag", AttributeValue.fromS(tag),
                ":seen", AttributeValue.fromN(String.valueOf(observedAt)),
                ":now", AttributeValue.fromS(Instant.now().toString()),
                ":one", AttributeValue.fromN("1")
        );

        try {
            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName).key(key)
                    .updateExpression("SET #n = :name, #t = :tag, firstSeen = if_not_exists(firstSeen, :seen), lastSeen = if_not_exists(lastSeen, :seen), updatedAt = :now ADD observations :one")
                    .expressionAttributeNames(Map.of("#n", "name", "#t", "tag"))
                    .expressionAttributeValues(values).build());
            updateObservedBoundary(key, "firstSeen", observedAt, "firstSeen > :seen");
            updateObservedBoundary(key, "lastSeen", observedAt, "lastSeen < :seen");
        } catch (DynamoDbException e) {
            LOG.error("Failed to record name history for player {}", puuid, e);
        }
    }

    private void updateObservedBoundary(Map<String, AttributeValue> key, String attribute, long observedAt, String condition) {
        try {
            ddb.updateItem(UpdateItemRequest.builder().tableName(tableName).key(key)
                    .updateExpression("SET " + attribute + " = :seen").conditionExpression(condition)
                    .expressionAttributeValues(Map.of(":seen", AttributeValue.fromN(String.valueOf(observedAt)))).build());
        } catch (ConditionalCheckFailedException ignored) {
            // The existing boundary is already correct.
        }
    }

    public List<Map<String, Object>> getPlayerNameHistory(String puuid) {
        QueryResponse response = ddb.query(QueryRequest.builder().tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("PLAYER#" + puuid),
                        ":prefix", AttributeValue.fromS("NAME#"))).build());

        String currentNameTag = getCurrentNameTag(puuid).orElse("");
        return response.items().stream().map(item -> {
                    String name = item.getOrDefault("name", AttributeValue.fromS("")).s();
                    String tag = item.getOrDefault("tag", AttributeValue.fromS("")).s();
                    Map<String, Object> result = new HashMap<>();
                    result.put("name", name);
                    result.put("tag", tag);
                    result.put("firstSeen", Long.parseLong(item.getOrDefault("firstSeen", AttributeValue.fromN("0")).n()));
                    result.put("lastSeen", Long.parseLong(item.getOrDefault("lastSeen", AttributeValue.fromN("0")).n()));
                    result.put("observations", Long.parseLong(item.getOrDefault("observations", AttributeValue.fromN("0")).n()));
                    result.put("current", (name + "#" + tag).equalsIgnoreCase(currentNameTag));
                    return result;
                }).filter(item -> !"null".equalsIgnoreCase((String) item.get("name"))
                        && !"null".equalsIgnoreCase((String) item.get("tag")))
                .sorted(Comparator.comparingLong(item -> -((Number) item.get("lastSeen")).longValue())).toList();
    }

    private Optional<String> getCurrentNameTag(String puuid) {
        GetItemResponse response = ddb.getItem(GetItemRequest.builder().tableName(tableName)
                .key(Map.of("PK", AttributeValue.fromS("PLAYER#" + puuid), "SK", AttributeValue.fromS("PROFILE")))
                .projectionExpression("#n, #t").expressionAttributeNames(Map.of("#n", "name", "#t", "tag")).build());
        if (!response.hasItem() || !response.item().containsKey("name") || !response.item().containsKey("tag")) return Optional.empty();
        return Optional.of(response.item().get("name").s() + "#" + response.item().get("tag").s());
    }

    public Optional<Map<String, String>> getCurrentIdentity(String puuid) {
        GetItemResponse response = ddb.getItem(GetItemRequest.builder().tableName(tableName)
                .key(Map.of("PK", AttributeValue.fromS("PLAYER#" + puuid), "SK", AttributeValue.fromS("PROFILE"))).build());
        if (!response.hasItem()) return Optional.empty();
        Map<String, AttributeValue> item = response.item();
        if (!item.containsKey("accountLevel") || !item.containsKey("cardSmall")) {
            return Optional.empty();
        }
        return Optional.of(Map.of(
                "name", item.getOrDefault("name", AttributeValue.fromS("")).s(),
                "tag", item.getOrDefault("tag", AttributeValue.fromS("")).s(),
                "region", item.getOrDefault("region", AttributeValue.fromS("na")).s()
        ));
    }

    public Optional<Map<String, Object>> getCachedAccount(String name, String tag) {
        Optional<String> puuid = getPuuidByNameTag(name, tag);
        if (puuid.isEmpty()) return Optional.empty();
        GetItemResponse response = ddb.getItem(GetItemRequest.builder().tableName(tableName)
                .key(Map.of("PK", AttributeValue.fromS("PLAYER#" + puuid.get()), "SK", AttributeValue.fromS("PROFILE")))
                .build());
        if (!response.hasItem()) return Optional.empty();
        Map<String, AttributeValue> item = response.item();
        Map<String, Object> data = new HashMap<>();
        data.put("puuid", puuid.get());
        data.put("name", item.getOrDefault("name", AttributeValue.fromS(name)).s());
        data.put("tag", item.getOrDefault("tag", AttributeValue.fromS(tag)).s());
        data.put("region", item.getOrDefault("region", AttributeValue.fromS("na")).s());
        if (item.containsKey("accountLevel")) data.put("account_level", Long.parseLong(item.get("accountLevel").n()));
        Map<String, String> card = new HashMap<>();
        for (String field : List.of("id", "small", "large", "wide")) {
            String attribute = "card" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            if (item.containsKey(attribute)) card.put(field, item.get(attribute).s());
        }
        if (!card.isEmpty()) data.put("card", card);
        return Optional.of(data);
    }

    public void storeAccountProfile(Map<?, ?> data, String fallbackName, String fallbackTag, String fallbackRegion) {
        String puuid = java.util.Objects.toString(data.get("puuid"), "");
        if (puuid.isBlank()) return;
        String name = java.util.Objects.toString(data.get("name"), fallbackName);
        String tag = java.util.Objects.toString(data.get("tag"), fallbackTag);
        String region = java.util.Objects.toString(data.get("region"), fallbackRegion);
        storePlayerProfile(puuid, name, tag, region);

        Map<String, AttributeValue> values = new HashMap<>();
        StringBuilder update = new StringBuilder("SET updatedAt = :now");
        values.put(":now", AttributeValue.fromS(Instant.now().toString()));
        if (data.get("account_level") instanceof Number level) {
            update.append(", accountLevel = :level");
            values.put(":level", AttributeValue.fromN(String.valueOf(level.longValue())));
        }
        if (data.get("card") instanceof Map<?, ?> card) {
            for (String field : List.of("id", "small", "large", "wide")) {
                String value = java.util.Objects.toString(card.get(field), "");
                if (value.isBlank()) continue;
                String token = ":card" + field;
                update.append(", card").append(Character.toUpperCase(field.charAt(0)))
                        .append(field.substring(1)).append(" = ").append(token);
                values.put(token, AttributeValue.fromS(value));
            }
        }
        ddb.updateItem(UpdateItemRequest.builder().tableName(tableName)
                .key(Map.of("PK", AttributeValue.fromS("PLAYER#" + puuid), "SK", AttributeValue.fromS("PROFILE")))
                .updateExpression(update.toString()).expressionAttributeValues(values).build());
    }

    public boolean shouldBackfillNameHistory(String puuid) {
        GetItemResponse response = ddb.getItem(GetItemRequest.builder().tableName(tableName)
                .key(Map.of("PK", AttributeValue.fromS("PLAYER#" + puuid), "SK", AttributeValue.fromS(NAME_HISTORY_META_SK))).build());
        if (!response.hasItem() || !response.item().containsKey("lastBackfill")) return true;
        long lastBackfill = Long.parseLong(response.item().get("lastBackfill").n());
        return Instant.now().getEpochSecond() - lastBackfill >= NAME_HISTORY_BACKFILL_SECONDS;
    }

    public void markNameHistoryBackfilled(String puuid) {
        ddb.putItem(PutItemRequest.builder().tableName(tableName).item(Map.of(
                "PK", AttributeValue.fromS("PLAYER#" + puuid),
                "SK", AttributeValue.fromS(NAME_HISTORY_META_SK),
                "lastBackfill", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond()))
                )).build());
    }

    public boolean isNameHistoryCheckpointProcessed(String puuid, String matchId) {
        GetItemResponse response = ddb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.fromS("PLAYER#" + puuid),
                        "SK", AttributeValue.fromS(NAME_HISTORY_CHECKPOINT_PREFIX + matchId)
                ))
                .projectionExpression("SK")
                .build());
        return response.hasItem();
    }

    public void markNameHistoryCheckpointProcessed(String puuid, String matchId) {
        ddb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "PK", AttributeValue.fromS("PLAYER#" + puuid),
                        "SK", AttributeValue.fromS(NAME_HISTORY_CHECKPOINT_PREFIX + matchId),
                        "processedAt", AttributeValue.fromS(Instant.now().toString())
                ))
                .build());
    }

    /**
     * Get puuid by name and tag.
     */
    public Optional<String> getPuuidByNameTag(String name, String tag) {
        String nameTag = (name + "#" + tag).toLowerCase();

        try {
            GetItemResponse response = ddb.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("LOOKUP#" + nameTag),
                            "SK", AttributeValue.fromS("PUUID")
                    ))
                    .build());

            if (response.hasItem() && response.item().containsKey("puuid")) {
                return Optional.of(response.item().get("puuid").s());
            }
        } catch (DynamoDbException e) {
            LOG.error("Error looking up puuid for {}#{}", name, tag, e);
        }
        return Optional.empty();
    }
}
