package com.valstats.queue;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefreshJobSerdeTest {

    @Test
    void deserializesQueueMessageWithMicronautSerde() throws Exception {
        try (var context = ApplicationContext.run()) {
            var job = new RefreshQueueHandler(context).parseRefreshJob(
                    "{\"puuid\":\"player-id\",\"region\":\"na\",\"name\":\"EVERYONE LIES\","
                            + "\"tag\":\"207\",\"requestedAt\":123}");

            assertEquals("player-id", job.puuid());
            assertEquals("EVERYONE LIES", job.name());
            assertEquals(123L, job.requestedAt());
        }
    }

    @Test
    void deserializesTargetedActContinuation() throws Exception {
        try (var context = ApplicationContext.run()) {
            var job = new RefreshQueueHandler(context).parseRefreshJob(
                    "{\"puuid\":\"player-id\",\"region\":\"na\",\"name\":\"Player\","
                            + "\"tag\":\"NA1\",\"requestedAt\":123,\"kind\":\"ACT\",\"page\":21,"
                            + "\"pagesPerJob\":10,\"targetSeasonId\":\"season-1\",\"targetSeen\":true}");

            assertEquals("ACT", job.kind());
            assertEquals(21, job.page());
            assertEquals("season-1", job.targetSeasonId());
            assertEquals(true, job.targetSeen());
        }
    }
}
