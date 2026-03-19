package com.valstats.service.player;

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

@Singleton
public class PlayerCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerCacheService.class);
    private static final long FETCH_COOLDOWN_SECONDS = 180; // 3 minutes

    private final DynamoDbClient ddb;
    private final String tableName = "valstats";

    public PlayerCacheService(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    /**
     * Check if a player exists in the database.
     */
    public boolean playerExists(String puuid) {
        try {
            GetItemResponse response = ddb.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("PLAYER#" + puuid),
                            "SK", AttributeValue.fromS("PROFILE")
                    ))
                    .build());

            return response.hasItem() && !response.item().isEmpty();
        } catch (DynamoDbException e) {
            LOG.error("Error checking if player exists: {}", puuid, e);
            return false;
        }
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

        // Store main profile
        Map<String, AttributeValue> profileItem = new HashMap<>();
        profileItem.put("PK", AttributeValue.fromS("PLAYER#" + puuid));
        profileItem.put("SK", AttributeValue.fromS("PROFILE"));
        profileItem.put("puuid", AttributeValue.fromS(puuid));
        profileItem.put("name", AttributeValue.fromS(name));
        profileItem.put("tag", AttributeValue.fromS(tag));
        profileItem.put("region", AttributeValue.fromS(region));
        profileItem.put("updatedAt", AttributeValue.fromS(now));

        // Store name#tag -> puuid lookup
        String nameTag = (name + "#" + tag).toLowerCase();
        Map<String, AttributeValue> lookupItem = new HashMap<>();
        lookupItem.put("PK", AttributeValue.fromS("LOOKUP#" + nameTag));
        lookupItem.put("SK", AttributeValue.fromS("PUUID"));
        lookupItem.put("puuid", AttributeValue.fromS(puuid));
        lookupItem.put("region", AttributeValue.fromS(region));
        lookupItem.put("updatedAt", AttributeValue.fromS(now));

        try {
            // Use batch write for efficiency
            ddb.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(
                            tableName, List.of(
                                    WriteRequest.builder().putRequest(PutRequest.builder().item(profileItem).build()).build(),
                                    WriteRequest.builder().putRequest(PutRequest.builder().item(lookupItem).build()).build()
                            )
                    ))
                    .build());
        } catch (DynamoDbException e) {
            LOG.error("Failed to store player profile: {}#{}", name, tag, e);
        }
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
