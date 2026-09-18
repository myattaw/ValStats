package com.valstats.admin;

import com.valstats.filter.RateLimitFilter;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.*;

@Singleton
public class AdminDashboardService {
    private final SqsClient sqs;
    private final DynamoDbClient dynamo;
    private final RateLimitFilter rateLimitFilter;
    private final String tableName;
    private final String refreshQueueUrl;
    private final String historyQueueUrl;
    private final double henrikRequestsPerSecond;
    private final int henrikMaxQueued;
    private final int henrikMaxRetries;

    public AdminDashboardService(SqsClient sqs, DynamoDbClient dynamo, RateLimitFilter rateLimitFilter,
                                 @Value("${dynamodb.table-name}") String tableName,
                                 @Value("${refresh.queue-url:}") String refreshQueueUrl,
                                 @Value("${refresh.history-queue-url:}") String historyQueueUrl,
                                 @Value("${henrik-api.rate-limit.requests-per-second:1}") double henrikRequestsPerSecond,
                                 @Value("${henrik-api.rate-limit.max-queued-requests:100}") int henrikMaxQueued,
                                 @Value("${henrik-api.rate-limit.max-retries:3}") int henrikMaxRetries) {
        this.sqs = sqs;
        this.dynamo = dynamo;
        this.rateLimitFilter = rateLimitFilter;
        this.tableName = tableName;
        this.refreshQueueUrl = refreshQueueUrl;
        this.historyQueueUrl = historyQueueUrl;
        this.henrikRequestsPerSecond = henrikRequestsPerSecond;
        this.henrikMaxQueued = henrikMaxQueued;
        this.henrikMaxRetries = henrikMaxRetries;
    }

    public List<Map<String, Object>> queueStatus() {
        return List.of(status("Match refresh", refreshQueueUrl), status("Name history", historyQueueUrl));
    }

    public Map<String, Object> henrikStatus() {
        Map<String, AttributeValue> row = dynamo.getItem(request -> request.tableName(tableName).key(Map.of(
                "PK", AttributeValue.fromS("ADMIN#METRICS"), "SK", AttributeValue.fromS("HENRIK")))).item();
        long until = number(row, "throttledUntil");
        String recorded = string(row, "status");
        String status = until > Instant.now().getEpochSecond() ? "THROTTLED"
                : (recorded.isBlank() ? "NO DATA" : ("THROTTLED".equals(recorded) ? "RECOVERED" : recorded));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status); result.put("requestsPerSecond", henrikRequestsPerSecond);
        result.put("maxQueued", henrikMaxQueued); result.put("maxRetries", henrikMaxRetries);
        result.put("lastEventAt", string(row, "lastEventAt")); result.put("lastOperation", string(row, "lastOperation"));
        result.put("successfulRequests", number(row, "successfulRequests"));
        result.put("rateLimitedRequests", number(row, "rateLimitedRequests"));
        result.put("failedRequests", number(row, "failedRequests"));
        result.put("retryAfterSeconds", Math.max(0, until - Instant.now().getEpochSecond()));
        return result;
    }

    public Map<String, Object> liveStatus() {
        return Map.of("queues", queueStatus(), "activeSyncs", activeSyncs(), "stalledSyncs", stalledSyncs(),
                "henrik", henrikStatus(), "ipLimits", ipRateLimitStatus(), "refreshedAt", Instant.now().toString());
    }

    public Map<String, Object> ipRateLimitStatus() { return rateLimitFilter.snapshot(); }

    public List<Map<String, Object>> activeSyncs() {
        return syncs(false);
    }

    public List<Map<String, Object>> stalledSyncs() {
        return syncs(true);
    }

    private List<Map<String, Object>> syncs(boolean stalledOnly) {
        List<Map<String, AttributeValue>> rows = stalledOnly
                ? scan("begins_with(SK, :sync) AND (#status = :queued OR #status = :running)",
                    Map.of("#status", "status"), Map.of(
                            ":sync", AttributeValue.fromS("SYNC#"), ":queued", AttributeValue.fromS("QUEUED"),
                            ":running", AttributeValue.fromS("RUNNING")))
                : scan("begins_with(SK, :sync)", Map.of(), Map.of(":sync", AttributeValue.fromS("SYNC#")));
        long now = Instant.now().getEpochSecond();
        long recentCutoff = now - (7 * 24 * 60 * 60);
        long activityCutoff = now - (15 * 60);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            String pk = string(row, "PK");
            String puuid = pk.startsWith("PLAYER#") ? pk.substring(7) : pk;
            Map<String, AttributeValue> profile = get(puuid, "PROFILE");
            long lease = number(row, "leaseUntil");
            String storedStatus = string(row, "status");
            boolean unfinished = "QUEUED".equals(storedStatus) || "RUNNING".equals(storedStatus);
            boolean stalled = unfinished && lease <= now;
            if (stalledOnly && !stalled) continue;
            if (stalledOnly && updatedEpoch(row) < recentCutoff) continue;
            if (!stalledOnly && updatedEpoch(row) < activityCutoff) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("puuid", puuid);
            item.put("account", accountName(profile, puuid));
            item.put("scope", string(row, "SK").replace("SYNC#", ""));
            item.put("status", stalled ? "STALLED" : storedStatus);
            item.put("page", number(row, "nextPage", 1));
            item.put("updatedAt", string(row, "updatedAt"));
            result.add(item);
        }
        result.sort(Comparator.comparing(row -> row.get("updatedAt").toString(), Comparator.reverseOrder()));
        return result;
    }

    private long updatedEpoch(Map<String, AttributeValue> row) {
        try { return Instant.parse(string(row, "updatedAt")).getEpochSecond(); }
        catch (RuntimeException ignored) { return 0; }
    }

    public List<Map<String, Object>> searchAccounts(String query) {
        if (query == null || query.isBlank()) return List.of();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : scan("SK = :profile", Map.of(), Map.of(":profile", AttributeValue.fromS("PROFILE")))) {
            String puuid = string(row, "puuid");
            if (puuid.isBlank() && string(row, "PK").startsWith("PLAYER#")) puuid = string(row, "PK").substring(7);
            String account = accountName(row, puuid);
            if (!account.toLowerCase(Locale.ROOT).contains(needle) && !puuid.toLowerCase(Locale.ROOT).contains(needle)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("puuid", puuid); item.put("account", account); item.put("region", string(row, "region"));
            item.put("level", number(row, "accountLevel")); item.put("updatedAt", string(row, "updatedAt"));
            result.add(item);
            if (result.size() == 50) break;
        }
        return result;
    }

    public int deleteAccount(String puuid) {
        if (puuid == null || puuid.isBlank()) return 0;
        List<Map<String, AttributeValue>> rows = new ArrayList<>();
        Map<String, AttributeValue> cursor = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder().tableName(tableName)
                    .keyConditionExpression("PK = :player")
                    .expressionAttributeValues(Map.of(":player", AttributeValue.fromS("PLAYER#" + puuid)));
            if (cursor != null && !cursor.isEmpty()) request.exclusiveStartKey(cursor);
            QueryResponse response = dynamo.query(request.build());
            rows.addAll(response.items()); cursor = response.lastEvaluatedKey();
        } while (cursor != null && !cursor.isEmpty());
        rows.addAll(scan("begins_with(PK, :lookup) AND puuid = :puuid", Map.of(), Map.of(
                ":lookup", AttributeValue.fromS("LOOKUP#"), ":puuid", AttributeValue.fromS(puuid))));
        List<WriteRequest> deletes = rows.stream().map(row -> WriteRequest.builder().deleteRequest(
                DeleteRequest.builder().key(Map.of("PK", row.get("PK"), "SK", row.get("SK"))).build()).build()).toList();
        for (int start = 0; start < deletes.size(); start += 25) {
            List<WriteRequest> batch = deletes.subList(start, Math.min(start + 25, deletes.size()));
            dynamo.batchWriteItem(BatchWriteItemRequest.builder().requestItems(Map.of(tableName, batch)).build());
        }
        return deletes.size();
    }

    private Map<String, AttributeValue> get(String puuid, String sk) {
        return dynamo.getItem(request -> request.tableName(tableName).key(Map.of(
                "PK", AttributeValue.fromS("PLAYER#" + puuid), "SK", AttributeValue.fromS(sk)))).item();
    }

    private List<Map<String, AttributeValue>> scan(String filter, Map<String, String> names,
                                                    Map<String, AttributeValue> values) {
        List<Map<String, AttributeValue>> result = new ArrayList<>();
        Map<String, AttributeValue> cursor = null;
        do {
            ScanRequest.Builder request = ScanRequest.builder().tableName(tableName).filterExpression(filter)
                    .expressionAttributeValues(values);
            if (!names.isEmpty()) request.expressionAttributeNames(names);
            if (cursor != null && !cursor.isEmpty()) request.exclusiveStartKey(cursor);
            ScanResponse response = dynamo.scan(request.build());
            result.addAll(response.items()); cursor = response.lastEvaluatedKey();
        } while (cursor != null && !cursor.isEmpty());
        return result;
    }

    private Map<String, Object> status(String name, String queueUrl) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("name", name);
        if (queueUrl == null || queueUrl.isBlank()) { result.put("available", false); return result; }
        var attributes = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED).build()).attributes();
        result.put("available", true);
        result.put("waiting", value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        result.put("running", value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
        result.put("delayed", value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED));
        return result;
    }

    private int value(Map<QueueAttributeName, String> attributes, QueueAttributeName key) {
        return Integer.parseInt(attributes.getOrDefault(key, "0"));
    }
    private String accountName(Map<String, AttributeValue> row, String fallback) {
        String name = string(row, "name"), tag = string(row, "tag");
        return name.isBlank() ? fallback : name + (tag.isBlank() ? "" : "#" + tag);
    }
    private String string(Map<String, AttributeValue> row, String key) {
        AttributeValue value = row.get(key); return value == null || value.s() == null ? "" : value.s();
    }
    private long number(Map<String, AttributeValue> row, String key) { return number(row, key, 0); }
    private long number(Map<String, AttributeValue> row, String key, long fallback) {
        AttributeValue value = row.get(key); return value == null || value.n() == null ? fallback : Long.parseLong(value.n());
    }
}
