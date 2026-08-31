package com.valstats.service;

import com.valstats.client.HenrikApiRequestQueue;
import com.valstats.client.ValorantApiClient;
import com.valstats.service.match.MatchDataService;
import com.valstats.service.player.PlayerCacheService;
import com.valstats.service.player.PlayerStatsService;
import com.valstats.service.queue.RefreshQueuePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import com.valstats.model.queue.RefreshJob;
import com.valstats.model.response.MatchResponses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class ValorantServiceTest {

    @Mock MatchDataService matchDataService;
    @Mock PlayerStatsService playerStatsService;
    @Mock PlayerCacheService playerCacheService;
    @Mock ValorantApiClient apiClient;
    @Mock DynamoDbService dynamoDbService;
    @Mock HenrikApiRequestQueue apiRequestQueue;
    @Mock RefreshQueuePublisher refreshQueuePublisher;

    @Test
    void summaryUsesCachedAggregateAndBoundedRecentMatches() {
        when(playerCacheService.getCachedAccount("Player", "NA1"))
                .thenReturn(Optional.of(Map.of(
                        "puuid", "puuid", "name", "Player", "tag", "NA1", "account_level", 100L)));
        when(playerStatsService.getOverallStats("puuid"))
                .thenReturn(Map.of("status", 200, "data", Map.of("matches_played", 25L)));
        when(matchDataService.getPlayerMatches("puuid", "na", "Player", "NA1", 10, null, "all", "all"))
                .thenReturn(new MatchResponses.MatchHistoryResponse(200, true, java.util.List.of(), null));
        ValorantService service = new ValorantService(
                matchDataService, playerStatsService, playerCacheService, apiClient,
                dynamoDbService, apiRequestQueue, refreshQueuePublisher);

        Map<String, Object> response = service.getPlayerSummary("na", "Player", "NA1", 99);

        assertEquals(200, response.get("status"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(10, data.get("recent_match_limit"));
        assertEquals(25L, ((Map<?, ?>) data.get("overall")).get("matches_played"));
        assertEquals(java.util.List.of(), data.get("recent_matches"));
        verifyNoInteractions(apiClient, apiRequestQueue);
    }

    @Test
    void refreshIsQueuedWhenSqsIsConfigured() {
        when(playerCacheService.getPuuidByNameTag("Player", "NA1"))
                .thenReturn(Optional.of("puuid"));
        when(matchDataService.needsRefresh("puuid", "na", "Player", "NA1"))
                .thenReturn(true);
        when(refreshQueuePublisher.isConfigured()).thenReturn(true);
        when(dynamoDbService.tryQueueBackfill("puuid", "RECENT")).thenReturn(true);
        ValorantService service = new ValorantService(
                matchDataService, playerStatsService, playerCacheService, apiClient,
                dynamoDbService, apiRequestQueue, refreshQueuePublisher);

        Map<String, Object> response = service.refreshMatches("na", "Player", "NA1");

        assertEquals(202, response.get("status"));
        verify(refreshQueuePublisher).enqueue(argThat((RefreshJob job) ->
                "puuid".equals(job.puuid()) && "na".equals(job.region())));
    }

    @Test
    void stalledHistoryIsResumedBeforeStartingAnotherRecentRefresh() {
        when(playerCacheService.getPuuidByNameTag("Player", "NA1"))
                .thenReturn(Optional.of("puuid"));
        when(refreshQueuePublisher.isConfigured()).thenReturn(true);
        when(dynamoDbService.getBackfillState("puuid", "HISTORY")).thenReturn(Optional.of(Map.of(
                "status", "STALLED", "refreshing", false, "nextPage", 16L)));
        when(dynamoDbService.tryQueueBackfill("puuid", "HISTORY")).thenReturn(true);
        ValorantService service = new ValorantService(
                matchDataService, playerStatsService, playerCacheService, apiClient,
                dynamoDbService, apiRequestQueue, refreshQueuePublisher);

        Map<String, Object> response = service.refreshMatches("na", "Player", "NA1");

        assertEquals(202, response.get("status"));
        verify(refreshQueuePublisher).enqueueLowPriority(argThat((RefreshJob job) ->
                "HISTORY".equals(job.kind()) && job.page() == 16));
        verifyNoInteractions(matchDataService);
    }

    @Test
    void actBackfillIsNotQueuedWhileFullHistoryIsActive() {
        when(playerCacheService.getPuuidByNameTag("Player", "NA1"))
                .thenReturn(Optional.of("puuid"));
        when(refreshQueuePublisher.isConfigured()).thenReturn(true);
        when(dynamoDbService.getBackfillState("puuid", "HISTORY")).thenReturn(Optional.of(Map.of(
                "status", "RUNNING", "refreshing", true, "nextPage", 4L)));
        ValorantService service = new ValorantService(
                matchDataService, playerStatsService, playerCacheService, apiClient,
                dynamoDbService, apiRequestQueue, refreshQueuePublisher);

        Map<String, Object> response = service.refreshActMatches(
                "na", "Player", "NA1", "season-1");

        assertEquals(202, response.get("status"));
        verify(refreshQueuePublisher, org.mockito.Mockito.never()).enqueue(any());
    }

    @Test
    void invalidAccountReturnsPlayerNotFoundWhenHenrikResponseIsNull() {
        when(playerCacheService.getPuuidByNameTag("mentally chill", "lol"))
                .thenReturn(Optional.empty());
        ValorantService service = new ValorantService(
                matchDataService,
                playerStatsService,
                playerCacheService,
                apiClient,
                dynamoDbService,
                apiRequestQueue,
                refreshQueuePublisher);

        Object response = service.getUnifiedMatches(
                "na", "mentally chill", "lol", 10, null, "all", "competitive");

        Map<?, ?> error = assertInstanceOf(Map.class, response);
        assertEquals(404, error.get("status"));
        assertEquals("Player not found", error.get("error"));
        verifyNoInteractions(matchDataService);
    }

    @Test
    void currentPromotionRaisesPeakAboveLaggingHighestRank() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("highest_rank", Map.of(
                "tier", 26,
                "patched_tier", "Immortal 3",
                "season", "e11a4"));
        data.put("current_data", Map.of(
                "currenttier", 27,
                "currenttierpatched", "Radiant"));
        data.put("by_season", Map.of());
        Map<String, Object> response = new java.util.HashMap<>(Map.of("status", 200, "data", data));
        when(apiRequestQueue.<Map<String, Object>>execute(anyString(), any())).thenReturn(response);
        ValorantService service = new ValorantService(
                matchDataService,
                playerStatsService,
                playerCacheService,
                apiClient,
                dynamoDbService,
                apiRequestQueue,
                refreshQueuePublisher);

        Map<String, Object> result = service.getPlayerMMR("na", "Rages", "1337", "all");

        Map<?, ?> resultData = (Map<?, ?>) result.get("data");
        Map<?, ?> highestRank = (Map<?, ?>) resultData.get("highest_rank");
        assertEquals(27, highestRank.get("tier"));
        assertEquals("Radiant", highestRank.get("patched_tier"));
    }

    @Test
    void historicalRadiantNameBeatsLargerOldOrCurrentNumericTier() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("highest_rank", Map.of("tier", 24, "patched_tier", "Radiant", "season", "e1a1", "old", true));
        data.put("current_data", Map.of("currenttier", 26, "currenttierpatched", "Immortal 3"));
        data.put("by_season", Map.of(
                "e1a1", Map.of("final_rank", 18, "final_rank_patched", "Diamond 1", "old", true),
                "e1a2", Map.of("error", "No data available")));
        Map<String, Object> response = new java.util.HashMap<>(Map.of("status", 200, "data", data));
        when(apiRequestQueue.<Map<String, Object>>execute(anyString(), any())).thenReturn(response);
        ValorantService service = new ValorantService(
                matchDataService, playerStatsService, playerCacheService, apiClient,
                dynamoDbService, apiRequestQueue, refreshQueuePublisher);

        Map<String, Object> result = service.getPlayerMMR("na", "Rages", "1337", "all");

        Map<?, ?> highestRank = (Map<?, ?>) ((Map<?, ?>) result.get("data")).get("highest_rank");
        assertEquals(27, highestRank.get("tier"));
        assertEquals("Radiant", highestRank.get("patched_tier"));
        assertEquals("e1a1", highestRank.get("season"));
    }
}
