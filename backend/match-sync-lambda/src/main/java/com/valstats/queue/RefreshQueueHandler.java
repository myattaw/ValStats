package com.valstats.queue;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.valstats.model.queue.RefreshJob;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextProvider;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;

public final class RefreshQueueHandler
        implements RequestHandler<SQSEvent, Void>, ApplicationContextProvider, AutoCloseable {

    private final ApplicationContext applicationContext;
    private final JsonMapper jsonMapper;
    private final RefreshJobProcessor processor;

    public RefreshQueueHandler() {
        this(ApplicationContext.run());
    }

    RefreshQueueHandler(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.jsonMapper = applicationContext.getBean(JsonMapper.class);
        this.processor = applicationContext.getBean(RefreshJobProcessor.class);
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            return null;
        }

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processor.process(parseRefreshJob(message.getBody()));
            } catch (Exception e) {
                context.getLogger().log("Refresh job failed for message " + message.getMessageId()
                        + ": " + e.getMessage());
                throw new IllegalStateException("Refresh job failed", e);
            }
        }
        return null;
    }

    RefreshJob parseRefreshJob(String body) throws java.io.IOException {
        JsonNode json = jsonMapper.readValue(body, JsonNode.class);
        return new RefreshJob(
                stringValue(json, "puuid"),
                stringValue(json, "region"),
                stringValue(json, "name"),
                stringValue(json, "tag"),
                longValue(json, "requestedAt")
        );
    }

    private static String stringValue(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value == null || value.isNull() ? null : value.coerceStringValue();
    }

    private static long longValue(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value == null || value.isNull() ? 0L : value.getLongValue();
    }

    @Override
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    public void close() {
        applicationContext.close();
    }
}
