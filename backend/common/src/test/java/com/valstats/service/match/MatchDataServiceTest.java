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
    void recentRefreshCachesFullMatchesWithoutCallingStoredOrSingleMatchEndpoints() {
        var dynamo = mock(DynamoDbService.class);
        var api = mock(ValorantApiClient.class);
        var processor = mock(MatchProcessor.class);
        var cache = mock(FullMatchCache.class);
        var queue = new HenrikApiRequestQueue(100_000, 10, 0, 1);
        var full = RecentMatchMapperTest.fullMatch();
        when(api.getRecentMatches("na", "Player", "Tag", 10))
                .thenReturn(Map.of("status", 200, "data", List.of(full, full)));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), processor, queue, cache);
        assertTrue(service.processBackfill(RefreshJob.matches("p1", "na", "Player", "Tag")).complete());
        verify(cache).put("m1", full);
        verify(processor).processStoredMatchSummary(any(), eq("p1"));
        verify(api, never()).getStoredMatches(any(), any(), any(), any(), any(), any());
        verify(api, never()).getMatchById(any());
        verify(dynamo).updateBackfillState("p1", "RECENT", "COMPLETE", 1);
    }

    @Test
    void detailCacheMissFetchesOnceThenServesFullCachedPayload() {
        var api = mock(ValorantApiClient.class);
        var cache = mock(FullMatchCache.class);
        var full = RecentMatchMapperTest.fullMatch();
        when(cache.get("m1")).thenReturn(java.util.Optional.empty(), java.util.Optional.of(full));
        when(api.getMatchById("m1")).thenReturn(Map.of("status", 200, "data", full));
        var service = new MatchDataService(mock(DynamoDbService.class), api, new MatchResponseFormatter(),
                mock(MatchProcessor.class), new HenrikApiRequestQueue(100_000, 10, 0, 1), cache);
        service.getMatchDetails("m1");
        assertEquals(Map.of("status", 200, "cached", true, "data", full), service.getMatchDetails("m1"));
        verify(api).getMatchById("m1");
        verify(cache).put("m1", full);
    }

    @Test
    void preloadingOnlyReturnsCachedDetailsAndNeverFetchesMissingMatches() {
        var api = mock(ValorantApiClient.class);
        var queue = mock(HenrikApiRequestQueue.class);
        var cache = mock(FullMatchCache.class);
        var full = RecentMatchMapperTest.fullMatch();
        when(cache.get("m1")).thenReturn(java.util.Optional.of(full));
        var service = new MatchDataService(mock(DynamoDbService.class), api, new MatchResponseFormatter(),
                mock(MatchProcessor.class), queue, cache);
        assertEquals(Map.of("m1", full), service.getCachedMatchDetails(List.of("m1", "old", "m1")).get("data"));
        verify(cache).get("m1");
        verifyNoInteractions(api, queue);
    }

    @Test
    void invalidRecentResponseDoesNotAdvanceRefreshCooldown() {
        var dynamo = mock(DynamoDbService.class);
        var api = mock(ValorantApiClient.class);
        when(api.getRecentMatches(any(), any(), any(), any())).thenReturn(Map.of("status", 503));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), mock(MatchProcessor.class),
                new HenrikApiRequestQueue(100_000, 10, 0, 1), mock(FullMatchCache.class));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.processBackfill(RefreshJob.matches("p1", "na", "Player", "Tag")));
        verify(dynamo, never()).updatePlayerLastRecentMatchUpdate(any(), any(), any());
    }

    @Test
    void preloadDefersDetailsThatWouldExceedTheResponseBudget() {
        var cache = mock(FullMatchCache.class);
        var large = Map.<String, Object>of("rounds", "x".repeat(1_100_000));
        when(cache.get(any())).thenReturn(java.util.Optional.of(large));
        var service = new MatchDataService(mock(DynamoDbService.class), mock(ValorantApiClient.class),
                new MatchResponseFormatter(), mock(MatchProcessor.class), mock(HenrikApiRequestQueue.class), cache);
        var response = service.getCachedMatchDetails(List.of("m1", "m2"));
        assertEquals(Map.of("m1", large), response.get("data"));
        assertEquals(List.of("m2"), response.get("deferred"));
    }

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
                dynamo, api, new MatchResponseFormatter(), processor, queue, mock(FullMatchCache.class));

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
                dynamo, api, new MatchResponseFormatter(), processor, queue, mock(FullMatchCache.class));

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
                dynamo, api, new MatchResponseFormatter(), mock(MatchProcessor.class), queue, mock(FullMatchCache.class));

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
                mock(MatchProcessor.class), mock(HenrikApiRequestQueue.class), mock(FullMatchCache.class));

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
