package com.valstats.admin;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class AdminDashboardService {

    private final SqsClient sqs;
    private final String refreshQueueUrl;
    private final String historyQueueUrl;

    public AdminDashboardService(SqsClient sqs,
                                 @Value("${refresh.queue-url:}") String refreshQueueUrl,
                                 @Value("${refresh.history-queue-url:}") String historyQueueUrl) {
        this.sqs = sqs;
        this.refreshQueueUrl = refreshQueueUrl;
        this.historyQueueUrl = historyQueueUrl;
    }

    public List<Map<String, Object>> queueStatus() {
        return List.of(status("Match refresh", refreshQueueUrl), status("Name history", historyQueueUrl));
    }

    private Map<String, Object> status(String name, String queueUrl) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        if (queueUrl == null || queueUrl.isBlank()) {
            result.put("available", false);
            return result;
        }
        var attributes = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)
                .build()).attributes();
        result.put("available", true);
        result.put("waiting", value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        result.put("running", value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
        result.put("delayed", value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED));
        return result;
    }

    private int value(Map<QueueAttributeName, String> attributes, QueueAttributeName key) {
        return Integer.parseInt(attributes.getOrDefault(key, "0"));
    }
}
