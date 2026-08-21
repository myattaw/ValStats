package com.valstats.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;

import java.net.URISyntaxException;

/**
 * Restores a valid percent-encoded path before Micronaut constructs a URI.
 *
 * <p>For the HTTP API default route, API Gateway can supply both {@code rawPath}
 * and the request-context path in decoded form ({@code EVERYONE LIES}). Micronaut AWS
 * 4.11 uses the request-context value when adapting payload-v2 events, and
 * Java's URI parser rejects the resulting literal space before controller
 * routing can begin.</p>
 */
final class ApiGatewayPathNormalizer {

    private ApiGatewayPathNormalizer() {
    }

    static APIGatewayV2HTTPEvent normalize(APIGatewayV2HTTPEvent event) {
        if (event == null) {
            return event;
        }

        var requestContext = event.getRequestContext();
        if (requestContext == null || requestContext.getHttp() == null
                || requestContext.getHttp().getPath() == null
                || requestContext.getHttp().getPath().isBlank()) {
            return event;
        }

        requestContext.getHttp().setPath(encodePath(requestContext.getHttp().getPath()));
        return event;
    }

    private static String encodePath(String decodedPath) {
        try {
            // The component constructor quotes characters that are illegal in a
            // URI path while retaining '/' as the path-segment separator.
            return new java.net.URI(null, null, decodedPath, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Unable to encode API Gateway request path", exception);
        }
    }
}
