package com.valstats.lambda;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import io.micronaut.function.aws.runtime.APIGatewayV2HTTPEventMicronautLambdaRuntime;

/** Native custom-runtime entry point that preserves percent-encoded paths. */
public final class ValStatsApiLambdaRuntime extends APIGatewayV2HTTPEventMicronautLambdaRuntime {

    @Override
    protected RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> createRequestHandler(String... args) {
        var applicationContext = createApplicationContextBuilderWithArgs(args).build();
        return new ValStatsApiLambdaFunction(applicationContext);
    }

    public static void main(String[] args) throws Exception {
        new ValStatsApiLambdaRuntime().run(args);
    }
}
