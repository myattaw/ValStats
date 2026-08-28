package com.valstats.service.match;

import com.valstats.model.stored.StoredMatchesResponse;
import com.valstats.service.player.PlayerNameRecorder;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;

class MatchProcessorTest {

    @Test
    void bulkHistoryUsesBatchWrites() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.batchWriteItem(any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());
        MatchProcessor processor = new MatchProcessor(dynamoDb, List.of());

        int stored = processor.processStoredMatchBatch(List.of(match()), "target-puuid", 1, true);

        assertTrue(stored == 1);
        verify(dynamoDb, atLeastOnce()).batchWriteItem(
                any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class));
    }

    @Test
    void bulkHistoryDeduplicatesRepeatedHenrikMatchKeys() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        when(dynamoDb.batchWriteItem(any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());
        MatchProcessor processor = new MatchProcessor(dynamoDb, List.of());

        int stored = processor.processStoredMatchBatch(List.of(match(), match()), "target-puuid", 1, true);

        assertTrue(stored == 1);
    }

    @Test
    void initialMatchProcessingRecordsEveryObservedPlayerName() {
        DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
        PlayerNameRecorder recorder = mock(PlayerNameRecorder.class);
        MatchProcessor processor = new MatchProcessor(dynamoDb, List.of(recorder));
        StoredMatchesResponse.StoredMatch match = match();

        assertTrue(processor.processStoredMatchSummary(match, "target-puuid"));

        long observedAt = Instant.parse("2026-01-02T03:04:05Z").getEpochSecond();
        verify(recorder).record("target-puuid", "Current", "NA1", observedAt);
        verify(recorder).record("other-puuid", "Previous", "OLD", observedAt);
        verify(recorder).record("target-puuid", "PreviousTargetName", "OLD1", observedAt);
    }

    private StoredMatchesResponse.StoredMatch match() {
        return new StoredMatchesResponse.StoredMatch(
                new StoredMatchesResponse.Meta(
                        "match-1",
                        new StoredMatchesResponse.MapInfo("map-1", "Ascent"),
                        "1",
                        "Competitive",
                        "2026-01-02T03:04:05Z",
                        new StoredMatchesResponse.Season("season-1", "e1a1"),
                        "na",
                        "na"
                ),
                new StoredMatchesResponse.Stats(
                        "target-puuid",
                        "PreviousTargetName",
                        "OLD1",
                        "Red",
                        1,
                        new StoredMatchesResponse.Character("agent-1", "Sage"),
                        10,
                        4000,
                        20,
                        15,
                        5,
                        new StoredMatchesResponse.Shots(10, 20, 2),
                        new StoredMatchesResponse.Damage(3000, 2500)
                ),
                new StoredMatchesResponse.Teams(13, 10),
                List.of(
                        new StoredMatchesResponse.Player("target-puuid", "Current", "NA1", "Red"),
                        new StoredMatchesResponse.Player("other-puuid", "Previous", "OLD", "Blue")
                )
        );
    }
}
