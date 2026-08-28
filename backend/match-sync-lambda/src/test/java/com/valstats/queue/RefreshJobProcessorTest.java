package com.valstats.queue;

import com.valstats.model.queue.RefreshJob;
import com.valstats.service.match.MatchDataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.eq;

class RefreshJobProcessorTest {
    private final MatchDataService matchDataService = mock(MatchDataService.class);
    private final BackfillQueuePublisher publisher = mock(BackfillQueuePublisher.class);
    private final RefreshJobProcessor processor = new RefreshJobProcessor(matchDataService, publisher);

    @Test
    void delegatesValidJobToMatchRefreshService() {
        RefreshJob job = new RefreshJob("puuid", "na", "Player", "NA1", 123L);
        when(matchDataService.processBackfill(job))
                .thenReturn(new MatchDataService.BackfillResult(true, 3, false));

        processor.process(job);

        verify(matchDataService).processBackfill(job);
    }

    @Test
    void rejectsIncompleteJobsSoSqsCanRetryThem() {
        assertThrows(IllegalArgumentException.class,
                () -> processor.process(new RefreshJob("", "na", "Player", "NA1", 123L)));
    }

    @Test
    void recentJobQueuesDelayedHistoricalContinuation() {
        RefreshJob job = RefreshJob.recent("puuid", "na", "Player", "NA1");
        when(matchDataService.processBackfill(job))
                .thenReturn(new MatchDataService.BackfillResult(false, 3, false));

        processor.process(job);

        verify(matchDataService).markBackfillQueued(argThat(next ->
                "HISTORY".equals(next.kind()) && next.page() == 1 && next.pagesPerJob() == 1));
        verify(publisher).enqueue(argThat(next ->
                "HISTORY".equals(next.kind()) && next.page() == 1 && next.pagesPerJob() == 1), eq(true));
    }

    @Test
    void failedJobRecordsTerminalStateBeforeSqsRetriesIt() {
        RefreshJob job = RefreshJob.recent("puuid", "na", "Player", "NA1");
        when(matchDataService.processBackfill(job)).thenThrow(new IllegalStateException("Henrik unavailable"));

        assertThrows(IllegalStateException.class, () -> processor.process(job));

        verify(matchDataService).markBackfillFailed(job);
    }
}
