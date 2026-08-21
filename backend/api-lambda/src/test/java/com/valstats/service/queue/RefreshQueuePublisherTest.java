package com.valstats.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valstats.model.queue.RefreshJob;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RefreshQueuePublisherTest {

    @Test
    void serializesEveryRequiredPlayerField() throws Exception {
        var publisher = new RefreshQueuePublisher(mock(SqsClient.class), "queue-url");
        var body = publisher.messageBody(new RefreshJob("player-id", "na", "EVERYONE LIES", "207", 123));
        var json = new ObjectMapper().readTree(body);

        assertEquals("player-id", json.get("puuid").asText());
        assertEquals("na", json.get("region").asText());
        assertEquals("EVERYONE LIES", json.get("name").asText());
        assertEquals("207", json.get("tag").asText());
        assertEquals(123, json.get("requestedAt").asLong());
    }
}
