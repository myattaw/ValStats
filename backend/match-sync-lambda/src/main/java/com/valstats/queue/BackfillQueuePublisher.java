package com.valstats.queue;

import com.valstats.model.queue.RefreshJob;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.IOException;

@Singleton
public class BackfillQueuePublisher {
    private final SqsClient sqs;
    private final JsonMapper json;
    private final String queueUrl;
    private final String historyQueueUrl;

    public BackfillQueuePublisher(SqsClient sqs, JsonMapper json,
                                  @Value("${refresh.queue-url:}") String queueUrl,
                                  @Value("${refresh.history-queue-url:}") String historyQueueUrl) {
        this.sqs = sqs;
        this.json = json;
        this.queueUrl = queueUrl;
        this.historyQueueUrl = historyQueueUrl;
    }

    public void enqueue(RefreshJob job, boolean lowPriority) {
        String destination = lowPriority && historyQueueUrl != null && !historyQueueUrl.isBlank()
                ? historyQueueUrl : queueUrl;
        if (destination == null || destination.isBlank()) return;
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(destination)
                    .delaySeconds(0)
                    .messageBody(json.writeValueAsString(job))
                    .build());
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to serialize continuation job", e);
        }
    }
}
