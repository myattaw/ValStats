package com.valstats.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import io.micronaut.context.ApplicationContext;
import io.micronaut.function.aws.proxy.payload2.APIGatewayV2HTTPEventFunction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return normalizeResponseCookies(super.handleRequest(
                ApiGatewayPathNormalizer.normalize(normalizeRequestCookies(event)), context));
    }

    /** Makes API Gateway v2's top-level cookie array visible to Micronaut's header-based decoder. */
    static APIGatewayV2HTTPEvent normalizeRequestCookies(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getCookies() == null || event.getCookies().isEmpty()) return event;
        Map<String, String> headers = event.getHeaders() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(event.getHeaders());
        boolean alreadyPresent = headers.keySet().stream().anyMatch("cookie"::equalsIgnoreCase);
        if (!alreadyPresent) headers.put("Cookie", String.join("; ", event.getCookies()));
        event.setHeaders(headers);
        return event;
    }

    /**
     * Micronaut's proxy adapter emits cookies as Set-Cookie headers, while an API Gateway HTTP API
     * payload-v2 response requires them in its top-level cookies array. Without this conversion API
     * Gateway drops the admin session cookie and every successful login appears to fail.
     */
    static APIGatewayV2HTTPResponse normalizeResponseCookies(APIGatewayV2HTTPResponse response) {
        List<String> cookies = new ArrayList<>();
        if (response.getCookies() != null) cookies.addAll(response.getCookies());

        Map<String, List<String>> multiHeaders = withoutSetCookie(
                response.getMultiValueHeaders(), values -> cookies.addAll(values));
        Map<String, String> headers = withoutSetCookie(
                response.getHeaders(), value -> {
                    if (cookies.isEmpty()) cookies.add(value);
                });

        response.setHeaders(headers);
        response.setMultiValueHeaders(multiHeaders);
        if (!cookies.isEmpty()) response.setCookies(cookies);
        return response;
    }

    private static <T> Map<String, T> withoutSetCookie(Map<String, T> source,
                                                        java.util.function.Consumer<T> cookieConsumer) {
        if (source == null) return null;
        Map<String, T> copy = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            if ("set-cookie".equalsIgnoreCase(name)) cookieConsumer.accept(value);
            else copy.put(name, value);
        });
        return copy;
    }
}
