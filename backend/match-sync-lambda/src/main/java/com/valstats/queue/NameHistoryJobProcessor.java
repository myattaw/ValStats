package com.valstats.queue;

import com.valstats.client.HenrikApiRequestQueue;
import com.valstats.client.ValorantApiClient;
import com.valstats.model.queue.RefreshJob;
import com.valstats.service.DynamoDbService;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;

@Singleton
public class NameHistoryJobProcessor {
    private static final long SAMPLE_SECONDS = 7L * 24 * 60 * 60;
    // Ten normally finishes in seconds, while still leaving ample room inside
    // the four-minute worker timeout for Henrik Retry-After delays.
    private static final int CHECKPOINTS_PER_JOB = 10;
    private static final String META_SK = "NAME_HISTORY_META_V3";
    private static final String CHECKPOINT_PREFIX = "NAME_HISTORY_CHECKPOINT_V3#";

    private final DynamoDbService dynamoDb;
    private final DynamoDbClient ddb;
    private final ValorantApiClient apiClient;
    private final HenrikApiRequestQueue requestQueue;
    private final BackfillQueuePublisher publisher;
    private final String tableName;

    public NameHistoryJobProcessor(DynamoDbService dynamoDb, DynamoDbClient ddb,
                                   ValorantApiClient apiClient, HenrikApiRequestQueue requestQueue,
                                   BackfillQueuePublisher publisher,
                                   @Value("${dynamodb.table-name:valstats}") String tableName) {
        this.dynamoDb = dynamoDb;
        this.ddb = ddb;
        this.apiClient = apiClient;
        this.requestQueue = requestQueue;
        this.publisher = publisher;
        this.tableName = tableName;
    }

    public void process(RefreshJob job) {
        setStatus(job.puuid(), "RUNNING", false);
        Map<Long, String> checkpoints = checkpoints(job.puuid());
        if (checkpoints.isEmpty()) {
            setStatus(job.puuid(), "WAITING_FOR_MATCHES", false);
            return;
        }

        int attempted = 0;
        boolean more = false;
        boolean retryNeeded = false;
        for (String matchId : spreadAcrossTimeline(checkpoints)) {
            if (isProcessed(job.puuid(), matchId)) continue;
            if (attempted >= CHECKPOINTS_PER_JOB) {
                more = true;
                break;
            }
            attempted++;
            try {
                Map<String, Object> response = requestQueue.executeLowPriority(
                        "name-history match details " + matchId,
                        () -> apiClient.getMatchById(matchId));
                if (!recordNames(response, job.puuid())) {
                    throw new IllegalStateException("Target player was missing from name-history match " + matchId);
                }
                markProcessed(job.puuid(), matchId);
            } catch (RuntimeException failure) {
                int failures = recordCheckpointFailure(job.puuid(), matchId);
                // A deleted or permanently unavailable match must not prevent
                // every later checkpoint from being scanned forever.
                if (failures < 3) retryNeeded = true;
            }
        }

        if (more || retryNeeded) {
            setStatus(job.puuid(), "QUEUED", false);
            publisher.enqueue(job, true);
        } else {
            setStatus(job.puuid(), "COMPLETE", true);
        }
    }

    public void markFailed(String puuid) {
        // SQS retries this message (and may be honoring a long Henrik
        // Retry-After). Keep the public state active between attempts.
        setStatus(puuid, "RETRYING", false);
    }

    private Map<Long, String> checkpoints(String puuid) {
        Map<Long, String> result = new TreeMap<>();
        for (Map<String, AttributeValue> match : dynamoDb.getStoredMatchesForPlayer(puuid, 50_000, 1)) {
            AttributeValue id = match.get("matchId");
            AttributeValue start = match.get("gameStart");
            if (id == null || id.s() == null || start == null || start.n() == null) continue;
            try {
                result.putIfAbsent(Math.floorDiv(Long.parseLong(start.n()), SAMPLE_SECONDS), id.s());
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private boolean recordNames(Map<String, Object> response, String requiredPuuid) {
        if (response == null || !(response.get("data") instanceof Map<?, ?> data)
                || !(data.get("players") instanceof Map<?, ?> players)
                || !(players.get("all_players") instanceof List<?> allPlayers)) return false;
        long observedAt = Instant.now().getEpochSecond();
        if (data.get("metadata") instanceof Map<?, ?> metadata
                && metadata.get("game_start") instanceof Number gameStart) {
            observedAt = gameStart.longValue();
            if (observedAt > 10_000_000_000L) observedAt /= 1000;
        }
        boolean found = false;
        for (Object value : allPlayers) {
            if (!(value instanceof Map<?, ?> player)) continue;
            String puuid = Objects.toString(player.get("puuid"), "");
            recordName(puuid, Objects.toString(player.get("name"), ""),
                    Objects.toString(player.get("tag"), ""), observedAt);
            if (requiredPuuid.equals(puuid)) found = true;
        }
        return found;
    }

    private void recordName(String puuid, String name, String tag, long observedAt) {
        if (puuid.isBlank() || name.isBlank() || tag.isBlank()
                || "null".equalsIgnoreCase(name) || "null".equalsIgnoreCase(tag)) return;
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS("PLAYER#" + puuid),
                "SK", AttributeValue.fromS("NAME#" + (name + "#" + tag).toLowerCase(Locale.ROOT)));
        ddb.updateItem(UpdateItemRequest.builder().tableName(tableName).key(key)
                .updateExpression("SET #n = :name, #t = :tag, firstSeen = if_not_exists(firstSeen, :seen), lastSeen = if_not_exists(lastSeen, :seen), updatedAt = :now ADD observations :one")
                .expressionAttributeNames(Map.of("#n", "name", "#t", "tag"))
                .expressionAttributeValues(Map.of(
                        ":name", AttributeValue.fromS(name), ":tag", AttributeValue.fromS(tag),
                        ":seen", AttributeValue.fromN(Long.toString(observedAt)),
                        ":now", AttributeValue.fromS(Instant.now().toString()), ":one", AttributeValue.fromN("1")))
                .build());
        updateBoundary(key, "firstSeen", observedAt, "firstSeen > :seen");
        updateBoundary(key, "lastSeen", observedAt, "lastSeen < :seen");
    }

    private void updateBoundary(Map<String, AttributeValue> key, String field, long seen, String condition) {
        try {
            ddb.updateItem(UpdateItemRequest.builder().tableName(tableName).key(key)
                    .updateExpression("SET " + field + " = :seen").conditionExpression(condition)
                    .expressionAttributeValues(Map.of(":seen", AttributeValue.fromN(Long.toString(seen)))).build());
        } catch (ConditionalCheckFailedException ignored) {
        }
    }

    private boolean isProcessed(String puuid, String matchId) {
        GetItemResponse response = ddb.getItem(GetItemRequest.builder().tableName(tableName)
                .key(checkpointKey(puuid, matchId))
                .projectionExpression("SK, processedAt, skippedAt, failedAttempts").build());
        if (!response.hasItem()) return false;
        Map<String, AttributeValue> item = response.item();
        // Rows written by the previous scanner only contained SK + processedAt.
        return item.containsKey("processedAt") || item.containsKey("skippedAt")
                || !item.containsKey("failedAttempts");
    }

    private void markProcessed(String puuid, String matchId) {
        Map<String, AttributeValue> item = new HashMap<>(checkpointKey(puuid, matchId));
        item.put("processedAt", AttributeValue.fromS(Instant.now().toString()));
        ddb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    private int recordCheckpointFailure(String puuid, String matchId) {
        UpdateItemResponse response = ddb.updateItem(UpdateItemRequest.builder().tableName(tableName)
                .key(checkpointKey(puuid, matchId))
                .updateExpression("SET lastFailureAt = :now ADD failedAttempts :one")
                .expressionAttributeValues(Map.of(
                        ":now", AttributeValue.fromS(Instant.now().toString()),
                        ":one", AttributeValue.fromN("1")))
                .returnValues(ReturnValue.ALL_NEW)
                .build());
        int failures = Integer.parseInt(response.attributes().get("failedAttempts").n());
        if (failures >= 3) {
            ddb.updateItem(UpdateItemRequest.builder().tableName(tableName)
                    .key(checkpointKey(puuid, matchId))
                    .updateExpression("SET skippedAt = :now")
                    .expressionAttributeValues(Map.of(":now", AttributeValue.fromS(Instant.now().toString())))
                    .build());
        }
        return failures;
    }

    private Map<String, AttributeValue> checkpointKey(String puuid, String matchId) {
        return Map.of("PK", AttributeValue.fromS("PLAYER#" + puuid),
                "SK", AttributeValue.fromS(CHECKPOINT_PREFIX + matchId));
    }

    private void setStatus(String puuid, String status, boolean complete) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", AttributeValue.fromS(status));
        values.put(":now", AttributeValue.fromS(Instant.now().toString()));
        String expression = "SET #status = :status, updatedAt = :now";
        if (complete) {
            expression += ", lastBackfill = :backfill";
            values.put(":backfill", AttributeValue.fromN(Long.toString(Instant.now().getEpochSecond())));
        }
        ddb.updateItem(UpdateItemRequest.builder().tableName(tableName)
                .key(Map.of("PK", AttributeValue.fromS("PLAYER#" + puuid), "SK", AttributeValue.fromS(META_SK)))
                .updateExpression(expression).expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(values).build());
    }

    private List<String> spreadAcrossTimeline(Map<Long, String> checkpoints) {
        List<String> chronological = new ArrayList<>(checkpoints.values());
        if (chronological.size() <= 2) return chronological;
        List<String> spread = new ArrayList<>(chronological.size());
        boolean[] added = new boolean[chronological.size()];
        add(chronological, spread, added, 0);
        add(chronological, spread, added, chronological.size() - 1);
        ArrayDeque<int[]> ranges = new ArrayDeque<>();
        ranges.add(new int[]{0, chronological.size() - 1});
        while (!ranges.isEmpty()) {
            int[] range = ranges.removeFirst();
            if (range[1] - range[0] <= 1) continue;
            int midpoint = (range[0] + range[1]) / 2;
            add(chronological, spread, added, midpoint);
            ranges.addLast(new int[]{range[0], midpoint});
            ranges.addLast(new int[]{midpoint, range[1]});
        }
        return spread;
    }

    private void add(List<String> source, List<String> target, boolean[] added, int index) {
        if (!added[index]) {
            target.add(source.get(index));
            added[index] = true;
        }
    }
}
