package com.valstats.lambda;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Bridges decoded API Gateway path segments through Micronaut's URI builder. */
public final class ApiGatewayPathCodec {
    private static final String PREFIX = "vspace-";

    private ApiGatewayPathCodec() {
    }

    static String encodeUnsafeSegments(String path) {
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].chars().anyMatch(Character::isWhitespace)) {
                segments[i] = encodeSegment(segments[i]);
            }
        }
        return String.join("/", segments);
    }

    public static String encodeSegment(String value) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String decodeSegment(String value) {
        if (value == null || !value.startsWith(PREFIX)) return value;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value.substring(PREFIX.length()));
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }
}
