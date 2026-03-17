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
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Filter("/api/valorant/**")
@Singleton
public class RateLimitFilter implements HttpServerFilter {

    private final ConcurrentHashMap<String, SimpleRateLimiter> limiters = new ConcurrentHashMap<>();

    // Allow burst of 15 requests (handles 3 parallel calls × 5 users simultaneously)
    private static final double BURST_CAPACITY = 15.0;
    // Sustained rate: 60 requests per minute
    private static final double REQUESTS_PER_MINUTE = 60.0;

    private SimpleRateLimiter createLimiter() {
        return SimpleRateLimiter.withBurst(BURST_CAPACITY, REQUESTS_PER_MINUTE);
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String clientKey = resolveClientKey(request);

        SimpleRateLimiter limiter = limiters.computeIfAbsent(clientKey, k -> createLimiter());

        if (!limiter.tryConsume()) {
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("error", "Too Many Requests");
            errorBody.put("message", "Rate limit exceeded. Please wait before retrying.");
            errorBody.put("retryAfterSeconds", 1);

            MutableHttpResponse<?> tooMany = HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(errorBody)
                    .header("Retry-After", "1")
                    .header("X-RateLimit-Limit", String.valueOf((int) REQUESTS_PER_MINUTE))
                    .header("X-RateLimit-Remaining", "0")
                    .header("X-RateLimit-Burst", String.valueOf((int) BURST_CAPACITY));

            return Publishers.just(tooMany);
        }

        // Add rate limit headers to successful responses
        int remaining = (int) limiter.getAvailableTokens();

        return Flux.from(chain.proceed(request))
                .map(response -> {
                    response.header("X-RateLimit-Limit", String.valueOf((int) REQUESTS_PER_MINUTE));
                    response.header("X-RateLimit-Remaining", String.valueOf(remaining));
                    response.header("X-RateLimit-Burst", String.valueOf((int) BURST_CAPACITY));
                    return response;
                });
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

    /**
     * Periodically clean up old limiters to prevent memory leaks.
     * Call this from a scheduled task if needed.
     */
    public void cleanupStaleLimiters() {
        // Simple cleanup - remove limiters that are at full capacity (inactive)
        limiters.entrySet().removeIf(entry ->
            entry.getValue().getAvailableTokens() >= BURST_CAPACITY - 0.1
        );
    }

}
