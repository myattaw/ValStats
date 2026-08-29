package com.valstats.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;

import java.net.URISyntaxException;

/**
 * Restores a decoded path before Micronaut constructs a URI.
 *
 * <p>For the HTTP API default route, API Gateway can supply both {@code rawPath}
 * and the request-context path in decoded form ({@code EVERYONE LIES}). Micronaut AWS
 * 4.11 uses the request-context value when adapting payload-v2 events, and
 * Micronaut's API Gateway adapter passes this value through {@code UriBuilder},
 * which performs the required URI encoding. Supplying an already escaped path
 * makes the builder escape the percent sign a second time.</p>
 */
final class ApiGatewayPathNormalizer {

    private ApiGatewayPathNormalizer() {
    }

    static APIGatewayV2HTTPEvent normalize(APIGatewayV2HTTPEvent event) {
        if (event == null) {
            return event;
        }

        var requestContext = event.getRequestContext();
        if (requestContext == null || requestContext.getHttp() == null) {
            return event;
        }

        // API Gateway's rawPath normally retains escapes such as %20. Prefer it
        // as the source of truth, then decode it for Micronaut's UriBuilder.
        String sourcePath = event.getRawPath();
        if (sourcePath == null || sourcePath.isBlank()) {
            sourcePath = requestContext.getHttp().getPath();
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            return event;
        }

        requestContext.getHttp().setPath(ApiGatewayPathCodec.encodeUnsafeSegments(decodePath(sourcePath)));
        return event;
    }

    private static String decodePath(String path) {
        try {
            // getPath decodes percent escapes without treating '+' as a space,
            // which is the correct behavior for a URI path component.
            return new java.net.URI(path).getPath();
        } catch (URISyntaxException ignored) {
            // requestContext.http.path may already be decoded and contain spaces.
            return path;
        }
    }
}
