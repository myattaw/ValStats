package com.valstats.queue;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.function.aws.runtime.AbstractMicronautLambdaRuntime;

import java.net.MalformedURLException;

/** AWS custom-runtime entry point used by the GraalVM native executable. */
public final class RefreshQueueLambdaRuntime
        extends AbstractMicronautLambdaRuntime<SQSEvent, Void, SQSEvent, Void> {

    public static void main(String[] args) {
        try {
            new RefreshQueueLambdaRuntime().run(args);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Unable to start the AWS Lambda custom runtime", e);
        }
    }

    @Override
    @Nullable
    protected RequestHandler<SQSEvent, Void> createRequestHandler(String... args) {
        var applicationContext = createApplicationContextBuilderWithArgs(args).build().start();
        return new RefreshQueueHandler(applicationContext);
    }
}
