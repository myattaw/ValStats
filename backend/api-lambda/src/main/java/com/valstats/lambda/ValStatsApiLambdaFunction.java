package com.valstats.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import io.micronaut.context.ApplicationContext;
import io.micronaut.function.aws.proxy.payload2.APIGatewayV2HTTPEventFunction;

/** JVM Lambda entry point that preserves percent-encoded API Gateway paths. */
public final class ValStatsApiLambdaFunction extends APIGatewayV2HTTPEventFunction {

    public ValStatsApiLambdaFunction() {
        super();
    }

    public ValStatsApiLambdaFunction(ApplicationContext applicationContext) {
        super(applicationContext);
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        return super.handleRequest(ApiGatewayPathNormalizer.normalize(event), context);
    }
}
