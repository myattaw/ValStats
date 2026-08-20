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
}
