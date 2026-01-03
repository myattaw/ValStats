package com.valstats.filter;

import java.util.concurrent.TimeUnit;

/**
 * Token bucket rate limiter with smooth refill.
 */
public class SimpleRateLimiter {

    private final double capacity;
    private final double refillTokens;
    private final long refillPeriodNanos;

    private double tokens;
    private long lastRefillNanos;

    public SimpleRateLimiter(double capacity, double refillTokens, long refillPeriodNanos) {
        if (refillPeriodNanos <= 0) {
            throw new IllegalArgumentException("refillPeriodNanos must be > 0");
        }

        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriodNanos = refillPeriodNanos;

        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryConsume() {
        refill();

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;

        if (elapsed <= 0) {
            return;
        }

        double periods = (double) elapsed / (double) refillPeriodNanos;
        double tokensToAdd = periods * refillTokens;

        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillNanos = now;
        }
    }

    public static SimpleRateLimiter perMinute(double requestsPerMinute) {
        double capacity = requestsPerMinute;
        double refillTokens = requestsPerMinute;
        long refillPeriodNanos = TimeUnit.MINUTES.toNanos(1);

        return new SimpleRateLimiter(capacity, refillTokens, refillPeriodNanos);
    }

}
