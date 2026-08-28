package com.valstats.service.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valstats.model.queue.RefreshJob;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.LinkedHashMap;
import java.util.Map;

@Singleton
public class RefreshQueuePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshQueuePublisher.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public RefreshQueuePublisher(
            SqsClient sqsClient,
            @Value("${refresh.queue-url:}") String queueUrl
    ) {
        this.sqsClient = sqsClient;
        this.objectMapper = new ObjectMapper();
        this.queueUrl = queueUrl;
    }

    public boolean isConfigured() {
        return queueUrl != null && !queueUrl.isBlank();
    }

    public void enqueue(RefreshJob job) {
        if (!isConfigured()) {
            throw new IllegalStateException("refresh.queue-url is not configured");
        }
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody(job))
                    .build());
            LOG.info("Queued match refresh for {}#{}", job.name(), job.tag());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize refresh job", e);
        }
    }

    String messageBody(RefreshJob job) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("puuid", job.puuid());
        payload.put("region", job.region());
        payload.put("name", job.name());
        payload.put("tag", job.tag());
        payload.put("requestedAt", job.requestedAt());
        payload.put("kind", job.kind());
        payload.put("page", job.page());
        payload.put("pagesPerJob", job.pagesPerJob());
        payload.put("targetSeasonId", job.targetSeasonId());
        payload.put("targetSeen", job.targetSeen());
        return objectMapper.writeValueAsString(payload);
    }
}
