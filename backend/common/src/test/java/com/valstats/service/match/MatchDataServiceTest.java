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
    void oversizedTwentyMatchBatchReturnsEveryDetailAcrossDeferredPages() {
        var cache = mock(FullMatchCache.class);
        var full = RecentMatchMapperTest.fullMatch();
        @SuppressWarnings("unchecked") var metadata = (Map<String, Object>) full.get("metadata");
        metadata.put("padding", "x".repeat(250_000));
        when(cache.get(any())).thenReturn(java.util.Optional.of(full));
        var service = new MatchDataService(mock(DynamoDbService.class), mock(ValorantApiClient.class),
                new MatchResponseFormatter(), mock(MatchProcessor.class), mock(HenrikApiRequestQueue.class), cache);
        List<String> pending = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "match-" + i).toList();
        var received = new java.util.HashSet<String>();
        int pages = 0;
        while (!pending.isEmpty()) {
            var response = service.getCachedMatchDetails(pending);
            @SuppressWarnings("unchecked") var data = (Map<String, Object>) response.get("data");
            @SuppressWarnings("unchecked") var deferred = (List<String>) response.get("deferred");
            assertTrue(data.size() > 0);
            assertEquals(pending.size(), data.size() + deferred.size());
            received.addAll(data.keySet());
            pending = deferred;
            assertTrue(++pages <= 20);
        }
        assertEquals(20, received.size());
        assertTrue(pages > 1);
    }

    @Test
    void filteredHistoryReadsBatchesAndResumesAfterTheLastDisplayedMatch() {
        var dynamo = mock(DynamoDbService.class);
        var processor = new MatchProcessor(mock(software.amazon.awssdk.services.dynamodb.DynamoDbClient.class), List.of());
        var rows = java.util.stream.IntStream.range(0, 25).mapToObj(index -> {
            var row = processor.storedMatchItem(RecentMatchMapper.summary(RecentMatchMapperTest.fullMatch(), "p1").orElseThrow(), "p1");
            row.put("matchId", AttributeValue.fromS("match-" + index));
            row.put("SK", AttributeValue.fromS("MATCH#" + index));
            row.put("mode", AttributeValue.fromS("competitive"));
            return row;
        }).toList();
        when(dynamo.getMatchesFromGSI("p1", 100, null)).thenReturn(QueryResponse.builder().items(rows).build());
        var service = new MatchDataService(dynamo, mock(ValorantApiClient.class), new MatchResponseFormatter(), processor,
                mock(HenrikApiRequestQueue.class), mock(FullMatchCache.class));
        var result = service.getPlayerMatches("p1", "na", "Player", "Tag", 20, null, "all", "competitive");
        assertEquals(20, result.data().size());
        assertEquals("MATCH#19", result.lastKey().sk());
        verify(dynamo).getMatchesFromGSI("p1", 100, null);
    }

    @Test
    void bootstrapFillsAPartialCompetitiveCacheWithoutDuplicateMatches() {
        var api = mock(ValorantApiClient.class);
        var dynamo = mock(DynamoDbService.class);
        var processor = new MatchProcessor(mock(software.amazon.awssdk.services.dynamodb.DynamoDbClient.class), List.of());
        var stored = java.util.stream.IntStream.range(0, 20).mapToObj(index -> {
            var full = RecentMatchMapperTest.fullMatch();
            @SuppressWarnings("unchecked") var metadata = (Map<String, Object>) full.get("metadata");
            metadata.put("matchid", "match-" + index);
            metadata.put("mode_id", "competitive");
            return RecentMatchMapper.summary(full, "p1").orElseThrow();
        }).toList();
        when(dynamo.getMatchesFromGSI("p1", 100, null)).thenReturn(QueryResponse.builder()
                .items(stored.subList(0, 2).stream().map(match -> processor.storedMatchItem(match, "p1")).toList()).build());
        when(api.getStoredMatches("na", "Player", "Tag", 20, 1, "competitive"))
                .thenReturn(storedResponse(500, stored));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), processor,
                new HenrikApiRequestQueue(100_000, 10, 0, 1), mock(FullMatchCache.class));
        var result = service.getPlayerMatches("p1", "na", "Player", "Tag", 20, null, "all", "competitive", true);
        assertEquals(20, result.data().size());
        assertEquals(20, result.data().stream().map(com.valstats.model.response.MatchResponses.MatchSummary::id).distinct().count());
    }

    @Test
    void competitiveViewFetchesOnlyTheFirstTenCompetitiveScoreboards() {
        var api = mock(ValorantApiClient.class);
        var dynamo = mock(DynamoDbService.class);
        var cache = mock(FullMatchCache.class);
        var processor = spy(new MatchProcessor(mock(software.amazon.awssdk.services.dynamodb.DynamoDbClient.class), List.of()));
        doReturn(true).when(processor).processRecentMatchSummary(any(), any());
        var matches = java.util.stream.IntStream.range(0, 20).mapToObj(index -> {
            var full = RecentMatchMapperTest.fullMatch();
            @SuppressWarnings("unchecked") var metadata = (Map<String, Object>) full.get("metadata");
            metadata.put("matchid", "competitive-" + index);
            metadata.put("mode_id", "competitive");
            return full;
        }).toList();
        when(api.getRecentMatchesByMode("na", "Player", "Tag", 10, 0, "competitive"))
                .thenReturn(Map.of("status", 200, "data", matches.subList(0, 10)));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), processor,
                new HenrikApiRequestQueue(100_000, 10, 0, 1), cache);
        var result = service.getRecentMatchHistory("p1", "na", "Player", "Tag", "Competitive", true);
        var rows = (List<com.valstats.model.response.MatchResponses.MatchSummary>) result.get("data");
        assertEquals(10, rows.size());
        verify(api, never()).getRecentMatchesByMode("na", "Player", "Tag", 10, 10, "competitive");
        assertTrue(rows.stream().allMatch(row -> row.placement() != null));
        verify(api, never()).getRecentMatches(any(), any(), any(), any(), any());
        verify(dynamo).updatePlayerLastRecentMatchUpdate("na", "Player", "Tag", "competitive");
        verify(processor, never()).processStoredMatchSummary(any(), any());
    }

    @Test
    void allModeCooldownDoesNotSuppressTheFirstCompetitiveFetch() {
        var dynamo = mock(DynamoDbService.class);
        when(dynamo.getPlayerLastRecentMatchUpdate("na", "Player", "Tag", "all"))
                .thenReturn(java.util.Optional.of(java.time.Instant.now().getEpochSecond()));
        var service = new MatchDataService(dynamo, mock(ValorantApiClient.class), new MatchResponseFormatter(),
                mock(MatchProcessor.class), mock(HenrikApiRequestQueue.class), mock(FullMatchCache.class));
        assertFalse(service.needsRefresh("p1", "na", "Player", "Tag", "all"));
        assertTrue(service.needsRefresh("p1", "na", "Player", "Tag", "competitive"));
    }

    @Test
    void newAccountReturnsTwentyStoredSummariesWithoutFullMatchesOrDerivedWrites() {
        var api = mock(ValorantApiClient.class);
        var dynamo = mock(DynamoDbService.class);
        var cache = mock(FullMatchCache.class);
        var db = mock(software.amazon.awssdk.services.dynamodb.DynamoDbClient.class);
        var processor = new MatchProcessor(db, List.of());
        when(dynamo.getMatchesFromGSI("p1", 20, null)).thenReturn(QueryResponse.builder().build());
        var stored = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> {
                    var full = RecentMatchMapperTest.fullMatch();
                    @SuppressWarnings("unchecked") var meta = (Map<String, Object>) full.get("metadata");
                    meta.put("matchid", "match-" + i);
                    return RecentMatchMapper.summary(full, "p1").orElseThrow();
                }).toList();
        when(api.getStoredMatches("na", "Player", "Tag", 20, 1, null))
                .thenReturn(storedResponse(500, stored));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), processor,
                new HenrikApiRequestQueue(100_000, 10, 0, 1), cache);
        var response = service.getPlayerMatches("p1", "na", "Player", "Tag", 20, null, "all", "all", true);
        assertEquals(20, response.data().size());
        verify(api).getStoredMatches("na", "Player", "Tag", 20, 1, null);
        verifyNoMoreInteractions(api);
        verifyNoInteractions(db);
    }

    @Test
    void emptyCachePollingDoesNotFetchStoredMatches() {
        var api = mock(ValorantApiClient.class);
        var dynamo = mock(DynamoDbService.class);
        when(dynamo.getMatchesFromGSI("p1", 10, null)).thenReturn(QueryResponse.builder().build());
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), mock(MatchProcessor.class),
                mock(HenrikApiRequestQueue.class), mock(FullMatchCache.class));
        assertEquals(List.of(), service.getRecentMatchHistory("p1", "na", "Player", "Tag", "all", false).get("data"));
        verifyNoInteractions(api);
    }

    @Test
    void recentRefreshCachesFullMatchesWithoutCallingStoredOrSingleMatchEndpoints() {
        var dynamo = mock(DynamoDbService.class);
        var api = mock(ValorantApiClient.class);
        var processor = mock(MatchProcessor.class);
        var cache = mock(FullMatchCache.class);
        var queue = new HenrikApiRequestQueue(100_000, 10, 0, 1);
        var full = RecentMatchMapperTest.fullMatch();
        when(api.getRecentMatches("na", "Player", "Tag", 10, 0))
                .thenReturn(Map.of("status", 200, "data", List.of(full, full)));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), processor, queue, cache);
        assertTrue(service.processBackfill(RefreshJob.matches("p1", "na", "Player", "Tag")).complete());
        verify(cache).putIfChanged("m1", full);
        verify(processor).processRecentMatchSummary(any(), eq("p1"));
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
        assertEquals(Map.of("m1", MatchDetailProjection.forDisplay(full)),
                service.getCachedMatchDetails(List.of("m1", "old", "m1")).get("data"));
        verify(cache).get("m1");
        verifyNoInteractions(api, queue);
    }

    @Test
    void invalidRecentResponseDoesNotAdvanceRefreshCooldown() {
        var dynamo = mock(DynamoDbService.class);
        var api = mock(ValorantApiClient.class);
        when(api.getRecentMatches(any(), any(), any(), any(), any())).thenReturn(Map.of("status", 503));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), mock(MatchProcessor.class),
                new HenrikApiRequestQueue(100_000, 10, 0, 1), mock(FullMatchCache.class));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.processBackfill(RefreshJob.matches("p1", "na", "Player", "Tag")));
        verify(dynamo, never()).updatePlayerLastRecentMatchUpdate(any(), any(), any());
    }

    @Test
    void preloadDefersDetailsThatWouldExceedTheResponseBudget() {
        var cache = mock(FullMatchCache.class);
        var large = Map.<String, Object>of("metadata", Map.of("padding", "x".repeat(1_100_000)));
        when(cache.get(any())).thenReturn(java.util.Optional.of(large));
        var service = new MatchDataService(mock(DynamoDbService.class), mock(ValorantApiClient.class),
                new MatchResponseFormatter(), mock(MatchProcessor.class), mock(HenrikApiRequestQueue.class), cache);
        var response = service.getCachedMatchDetails(List.of("m1", "m2"));
        assertEquals(Map.of("m1", MatchDetailProjection.forDisplay(large)), response.get("data"));
        assertEquals(List.of("m2"), response.get("deferred"));
    }

    @Test
    void returnsFirstTenRecentMatchesWithoutFetchingTheSecondPage() {
        var api = mock(ValorantApiClient.class);
        var dynamo = mock(DynamoDbService.class);
        var cache = mock(FullMatchCache.class);
        var processor = spy(new MatchProcessor(mock(software.amazon.awssdk.services.dynamodb.DynamoDbClient.class), List.of()));
        doReturn(true).when(processor).processRecentMatchSummary(any(), any());
        var matches = java.util.stream.IntStream.range(0, 20).mapToObj(index -> {
            var match = RecentMatchMapperTest.fullMatch();
            @SuppressWarnings("unchecked") var metadata = (Map<String, Object>) match.get("metadata");
            metadata.put("matchid", "match-" + index);
            return match;
        }).toList();
        when(api.getRecentMatches("na", "Player", "Tag", 10, 0))
                .thenReturn(Map.of("status", 200, "data", matches.subList(0, 10)));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), processor,
                new HenrikApiRequestQueue(100_000, 10, 0, 1), cache);
        var result = service.getRecentMatchHistory("p1", "na", "Player", "Tag", "all", true);
        assertEquals(10, ((List<?>) result.get("data")).size());
        assertEquals(10, ((Map<?, ?>) result.get("details")).size());
        verify(cache, times(10)).putIfChanged(any(), any());
        var order = inOrder(cache, processor);
        order.verify(cache, times(10)).putIfChanged(any(), any());
        order.verify(processor).processRecentMatchSummary(any(), eq("p1"));
        assertTrue(((List<com.valstats.model.response.MatchResponses.MatchSummary>) result.get("data"))
                .stream().allMatch(match -> match.placement() != null));
        verify(cache, never()).getPlacements(any(), any());
        verify(processor, never()).processStoredMatchSummary(any(), any());
        verify(api).getRecentMatches("na", "Player", "Tag", 10, 0);
        verify(api, never()).getRecentMatches("na", "Player", "Tag", 10, 10);
        verify(api, never()).getStoredMatches(any(), any(), any(), any(), any(), any());
        verify(api, never()).getMatchById(any());
        verify(dynamo, never()).getMatchesFromGSI(any(), anyInt(), any());
    }

    @Test
    void selectedModeWorkerPublishesSnapshotBeforeCompletingAndPollingAvoidsIndexAndDetails() {
        var api = mock(ValorantApiClient.class);
        var dynamo = mock(DynamoDbService.class);
        var cache = mock(FullMatchCache.class);
        var processor = spy(new MatchProcessor(mock(software.amazon.awssdk.services.dynamodb.DynamoDbClient.class), List.of()));
        var full = RecentMatchMapperTest.fullMatch();
        ((Map<?, ?>) full.get("metadata")).remove("season_id");
        when(api.getRecentMatchesByMode("na", "Player", "Tag", 10, 0, "competitive"))
                .thenReturn(Map.of("status", 200, "data", List.of(full)));
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), processor,
                new HenrikApiRequestQueue(100_000, 10, 0, 1), cache);
        var job = RefreshJob.recentView("p1", "na", "Player", "Tag", "competitive");
        assertTrue(service.processBackfill(job).complete());
        var rows = List.of(processor.storedMatchItem(RecentMatchMapper.summary(full, "p1").orElseThrow(), "p1"));
        var order = inOrder(dynamo);
        order.verify(dynamo).updateBackfillState("p1", "RECENT_VIEW#competitive", "RUNNING", 1);
        order.verify(dynamo).putRecentMatchSnapshot(eq("p1"), eq("competitive"), argThat(items ->
                items.size() == 1 && "m1".equals(items.get(0).get("matchId").s())));
        order.verify(dynamo).updatePlayerLastRecentMatchUpdate("na", "Player", "Tag", "competitive");
        order.verify(dynamo).updateBackfillState("p1", "RECENT_VIEW#competitive", "COMPLETE", 1);
        verify(api).getRecentMatchesByMode("na", "Player", "Tag", 10, 0, "competitive");
        verifyNoMoreInteractions(api);
        when(dynamo.getRecentMatchSnapshot("p1", "competitive")).thenReturn(java.util.Optional.of(rows));
        var result = service.getRecentMatchHistory("p1", "na", "Player", "Tag", "competitive", false);
        assertEquals(1, ((List<?>) result.get("data")).size());
        assertEquals(Map.of(), result.get("details"));
        verify(dynamo, never()).getMatchesFromGSI(any(), anyInt(), any());
        verify(cache, never()).get(any());
        verify(processor, never()).processRecentMatchSummary(any(), any());
        service.markBackfillFailed(job);
        verify(dynamo).updateBackfillState("p1", "RECENT_VIEW#competitive", "FAILED", 1);
    }

    @Test
    void cachesDetailsEvenWhenSeasonMetadataIsMissing() {
        var api = mock(ValorantApiClient.class);
        var cache = mock(FullMatchCache.class);
        var processor = mock(MatchProcessor.class);
        var full = RecentMatchMapperTest.fullMatch();
        ((Map<?, ?>) full.get("metadata")).remove("season_id");
        when(api.getRecentMatches(any(), any(), any(), eq(10), eq(0))).thenReturn(Map.of("status", 200, "data", List.of(full)));
        var service = new MatchDataService(mock(DynamoDbService.class), api, new MatchResponseFormatter(), processor,
                new HenrikApiRequestQueue(100_000, 10, 0, 1), cache);
        service.processBackfill(RefreshJob.matches("p1", "na", "Player", "Tag"));
        verify(cache).putIfChanged("m1", full);
        verify(processor, never()).processStoredMatchSummary(any(), any());
        verify(processor, never()).processRecentMatchSummary(any(), any());
    }

    @Test
    void cachedHistoryIncludesPermanentPlacementWithoutFullDetails() {
        var dynamo = mock(DynamoDbService.class);
        var cache = mock(FullMatchCache.class);
        var processor = new MatchProcessor(mock(software.amazon.awssdk.services.dynamodb.DynamoDbClient.class), List.of());
        var summary = RecentMatchMapper.summary(RecentMatchMapperTest.fullMatch(), "p1").orElseThrow();
        when(dynamo.getMatchesFromGSI(any(), anyInt(), any()))
                .thenReturn(QueryResponse.builder().items(processor.storedMatchItem(summary, "p1")).build());
        var placement = new com.valstats.model.response.MatchResponses.MatchPlacement(3, false, false);
        when(cache.getPlacements(List.of("m1"), "p1")).thenReturn(Map.of("m1", placement));
        var api = mock(ValorantApiClient.class);
        var service = new MatchDataService(dynamo, api, new MatchResponseFormatter(), processor,
                mock(HenrikApiRequestQueue.class), cache);
        var response = service.getPlayerMatches("p1", "na", "Player", "Tag", 20, null, "all", "all");
        assertEquals(placement, response.data().get(0).placement());
        verify(cache, never()).get(any());
        verifyNoInteractions(api);
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
        verify(dynamo).getMatchesFromGSI(eq("p1"), eq(100), keyCaptor.capture());
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
