package com.valstats.filter;

/**
 * Token bucket rate limiter with burst capacity and smooth refill.
 */
public class SimpleRateLimiter {

    private final double capacity;         // Max burst size
    private final double refillRate;       // Tokens per second

    private double tokens;
    private long lastRefillNanos;

    public SimpleRateLimiter(double capacity, double refillRate) {
        if (capacity <= 0 || refillRate <= 0) {
            throw new IllegalArgumentException("capacity and refillRate must be > 0");
        }

        this.capacity = capacity;
        this.refillRate = refillRate;

        this.tokens = capacity;  // Start with full burst capacity
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryConsume() {
        return tryConsume(1);
    }

    public synchronized boolean tryConsume(int tokensToConsume) {
        refill();

        if (tokens >= tokensToConsume) {
            tokens -= tokensToConsume;
            return true;
        }
        return false;
    }

    public synchronized double getAvailableTokens() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;

        if (elapsedNanos <= 0) {
            return;
        }

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double tokensToAdd = elapsedSeconds * refillRate;

        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillNanos = now;
        }
    }

    /**
     * Create a limiter with burst capacity and requests per minute sustained rate.
     *
     * @param burstCapacity     Maximum burst size (e.g., 15 for handling parallel requests)
     * @param requestsPerMinute Sustained rate limit
     */
    public static SimpleRateLimiter withBurst(double burstCapacity, double requestsPerMinute) {
        double refillRate = requestsPerMinute / 60.0;  // Convert to per-second
        return new SimpleRateLimiter(burstCapacity, refillRate);
    }

    /**
     * Legacy method - creates limiter where burst = sustained rate
     */
    public static SimpleRateLimiter perMinute(double requestsPerMinute) {
        return withBurst(requestsPerMinute, requestsPerMinute);
    }
}
