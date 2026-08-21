package com.valstats.queue;

import com.valstats.model.queue.RefreshJob;
import com.valstats.service.match.MatchDataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RefreshJobProcessorTest {
    private final MatchDataService matchDataService = mock(MatchDataService.class);
    private final RefreshJobProcessor processor = new RefreshJobProcessor(matchDataService);

    @Test
    void delegatesValidJobToMatchRefreshService() {
        RefreshJob job = new RefreshJob("puuid", "na", "Player", "NA1", 123L);

        processor.process(job);

        verify(matchDataService).refreshPlayerMatches("puuid", "na", "Player", "NA1");
    }

    @Test
    void rejectsIncompleteJobsSoSqsCanRetryThem() {
        assertThrows(IllegalArgumentException.class,
                () -> processor.process(new RefreshJob("", "na", "Player", "NA1", 123L)));
    }
}
