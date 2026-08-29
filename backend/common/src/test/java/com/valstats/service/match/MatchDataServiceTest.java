package com.valstats.service.match;

import com.valstats.client.HenrikApiRequestQueue;
import com.valstats.client.ValorantApiClient;
import com.valstats.model.queue.RefreshJob;
import com.valstats.model.stored.StoredMatchesResponse;
import com.valstats.service.DynamoDbService;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class MatchDataServiceTest {

    @Test
    void historyBackfillCheckpointsA500MatchPage() {
        DynamoDbService dynamo = mock(DynamoDbService.class);
        ValorantApiClient api = mock(ValorantApiClient.class);
        MatchProcessor processor = mock(MatchProcessor.class);
        HenrikApiRequestQueue queue = new HenrikApiRequestQueue(100_000, 10, 0, 1);
        RefreshJob job = RefreshJob.history("puuid", "na", "Player", "Tag", 1);
        List<StoredMatchesResponse.StoredMatch> matches = Collections.nCopies(500, storedMatch("match"));
        when(api.getStoredMatches("na", "Player", "Tag", 500, 1, null))
                .thenReturn(storedResponse(3_868, matches));

        MatchDataService service = new MatchDataService(
                dynamo, api, new MatchResponseFormatter(), processor, queue);

        MatchDataService.BackfillResult result = service.processBackfill(job);

        assertFalse(result.complete());
        assertEquals(2, result.nextPage());
        verify(processor).processStoredMatchBatch(matches, "puuid", 1, true);
        verify(dynamo).updateBackfillState("puuid", "HISTORY", "QUEUED", 2);
    }

    @Test
    void historyBackfillCompletesOnItsFinalPartialPage() {
        DynamoDbService dynamo = mock(DynamoDbService.class);
        ValorantApiClient api = mock(ValorantApiClient.class);
        MatchProcessor processor = mock(MatchProcessor.class);
        HenrikApiRequestQueue queue = new HenrikApiRequestQueue(100_000, 10, 0, 1);
        RefreshJob job = RefreshJob.history("puuid", "na", "Player", "Tag", 8);
        List<StoredMatchesResponse.StoredMatch> matches = Collections.nCopies(368, storedMatch("match"));
        when(api.getStoredMatches("na", "Player", "Tag", 500, 8, null))
                .thenReturn(storedResponse(3_868, matches));

        MatchDataService service = new MatchDataService(
                dynamo, api, new MatchResponseFormatter(), processor, queue);

        MatchDataService.BackfillResult result = service.processBackfill(job);

        assertTrue(result.complete());
        assertEquals(9, result.nextPage());
        verify(processor).processStoredMatchBatch(matches, "puuid", 8, true);
        verify(dynamo).updateBackfillState("puuid", "HISTORY", "COMPLETE", 9);
    }

    @Test
    void cachedReadNeverCallsHenrik() {
        DynamoDbService dynamo = mock(DynamoDbService.class);
        ValorantApiClient api = mock(ValorantApiClient.class);
        HenrikApiRequestQueue queue = mock(HenrikApiRequestQueue.class);
        when(dynamo.getMatchesFromGSI(any(), anyInt(), any()))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(dynamo.getMMRHistory(any())).thenReturn(List.of());

        MatchDataService service = new MatchDataService(
                dynamo, api, new MatchResponseFormatter(), mock(MatchProcessor.class), queue);

        var response = service.getPlayerMatches(
                "puuid", "na", "Player", "Tag", 15, null, "all", "competitive");

        assertTrue(response.data().isEmpty());
        verifyNoInteractions(api, queue);
        verify(dynamo, never()).updatePlayerLastRecentMatchUpdate(any(), any(), any());
    }

    @Test
    void passesDecodedCursorToDynamoDb() {
        DynamoDbService dynamo = mock(DynamoDbService.class);
        when(dynamo.getMatchesFromGSI(any(), anyInt(), any()))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(dynamo.getMMRHistory(any())).thenReturn(List.of());

        MatchDataService service = new MatchDataService(
                dynamo, mock(ValorantApiClient.class), new MatchResponseFormatter(),
                mock(MatchProcessor.class), mock(HenrikApiRequestQueue.class));

        String cursor = "%7B%22PK%22%3A%22PLAYER%23p1%22%2C%22SK%22%3A%22MATCH%23m1%22%2C"
                + "%22GSI1PK%22%3A%22PLAYER%23p1%22%2C%22GSI1SK%22%3A123%7D";
        service.getPlayerMatches("p1", "na", "Player", "Tag", 15, cursor, "all", "competitive");

        @SuppressWarnings("unchecked")
        var keyCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(dynamo).getMatchesFromGSI(eq("p1"), eq(15), keyCaptor.capture());
        Map<String, AttributeValue> key = keyCaptor.getValue();
        assertEquals("PLAYER#p1", key.get("PK").s());
        assertEquals("MATCH#m1", key.get("SK").s());
        assertEquals("PLAYER#p1", key.get("GSI1PK").s());
        assertEquals("123", key.get("GSI1SK").n());
    }

    private StoredMatchesResponse storedResponse(
            int total, List<StoredMatchesResponse.StoredMatch> matches) {
        return new StoredMatchesResponse(200, "Player", "Tag",
                new StoredMatchesResponse.Results(total, matches.size(), 0, 0), matches);
    }

    private StoredMatchesResponse.StoredMatch storedMatch(String id) {
        return new StoredMatchesResponse.StoredMatch(
                new StoredMatchesResponse.Meta(id, null, "1", "Competitive",
                        "2026-01-02T03:04:05Z", null, "na", "na"),
                null, null, List.of());
    }
}
