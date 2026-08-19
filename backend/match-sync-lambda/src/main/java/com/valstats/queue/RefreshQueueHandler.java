package com.valstats.queue;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valstats.model.queue.RefreshJob;
import io.micronaut.context.ApplicationContext;

public final class RefreshQueueHandler implements RequestHandler<SQSEvent, Void>, AutoCloseable {
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final RefreshJobProcessor processor;

    public RefreshQueueHandler() {
        this(ApplicationContext.run());
    }

    RefreshQueueHandler(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.objectMapper = new ObjectMapper();
        this.processor = applicationContext.getBean(RefreshJobProcessor.class);
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            return null;
        }

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processor.process(objectMapper.readValue(message.getBody(), RefreshJob.class));
            } catch (Exception e) {
                context.getLogger().log("Refresh job failed for message " + message.getMessageId()
                        + ": " + e.getMessage());
                throw new IllegalStateException("Refresh job failed", e);
            }
        }
        return null;
    }

    @Override
    public void close() {
        applicationContext.close();
    }
}
