package com.valstats.filter;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-client rate limiter filter.
 * Applies to /api/valorant/** endpoints.
 */
@Filter("/api/valorant/**")
@Singleton
public class RateLimitFilter implements HttpServerFilter {

    // Per-client limiters
    private final ConcurrentHashMap<String, SimpleRateLimiter> limiters = new ConcurrentHashMap<>();

    // DEFAULT: 60 requests per minute per client
    private static final double REQUESTS_PER_MINUTE = 60.0;

    private SimpleRateLimiter createLimiter() {
        return SimpleRateLimiter.perMinute(REQUESTS_PER_MINUTE);
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String clientKey = resolveClientKey(request);

        SimpleRateLimiter limiter = limiters.computeIfAbsent(clientKey, k -> createLimiter());

        if (!limiter.tryConsume()) {
            MutableHttpResponse<Map<String, Object>> tooMany =
                    HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                            .body(Map.of(
                                    "error", "Too Many Requests",
                                    "message", "Rate limit exceeded"
                            ));

            return Publishers.just(tooMany);
        }

        return chain.proceed(request);
    }

    private String resolveClientKey(HttpRequest<?> request) {
        // Prefer X-Forwarded-For when behind proxies / load balancers
        String xfwd = request.getHeaders().get("X-Forwarded-For");
        if (xfwd != null && !xfwd.isBlank()) {
            int comma = xfwd.indexOf(',');
            return comma == -1 ? xfwd.trim() : xfwd.substring(0, comma).trim();
        }

        SocketAddress remote = request.getRemoteAddress();
        if (remote instanceof InetSocketAddress isa && isa.getAddress() != null) {
            return isa.getAddress().getHostAddress();
        }

        return remote != null ? remote.toString() : "unknown";
    }

}
