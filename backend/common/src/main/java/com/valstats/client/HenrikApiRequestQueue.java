package com.valstats.client;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.exceptions.HttpClientException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Instance-local, bounded FIFO gate for calls to Henrik's API.
 *
 * <p>The request gate serializes calls, prioritizes interactive work over
 * opportunistic background work, and smooths bursts with a minimum interval.
 * Retry delays release the gate so unrelated interactive requests can proceed.</p>
 */
@Singleton
public class HenrikApiRequestQueue {

    private static final Logger LOG = LoggerFactory.getLogger(HenrikApiRequestQueue.class);

    private final ReentrantLock requestGate = new ReentrantLock();
    private final Condition requestAvailable = requestGate.newCondition();
    private final Semaphore normalQueueCapacity;
    private final Semaphore lowQueueCapacity;
    private final AtomicInteger normalDemand = new AtomicInteger();
    private final AtomicLong nextRequestAtNanos = new AtomicLong();
    private boolean requestInProgress;
    private final long minimumIntervalMillis;
    private final int maxRetries;
    private final long maxRetryDelaySeconds;

    public HenrikApiRequestQueue(
            @Value("${henrik-api.rate-limit.requests-per-second:1}") double requestsPerSecond,
            @Value("${henrik-api.rate-limit.max-queued-requests:100}") int maxQueuedRequests,
            @Value("${henrik-api.rate-limit.max-retries:3}") int maxRetries,
            @Value("${henrik-api.rate-limit.max-retry-delay-seconds:60}") long maxRetryDelaySeconds
    ) {
        if (requestsPerSecond <= 0 || maxQueuedRequests < 1 || maxRetries < 0 || maxRetryDelaySeconds < 1) {
            throw new IllegalArgumentException("Invalid Henrik API rate-limit configuration");
        }
        this.minimumIntervalMillis = Math.max(1L, (long) Math.ceil(1000.0 / requestsPerSecond));
        this.normalQueueCapacity = new Semaphore(maxQueuedRequests, true);
        this.lowQueueCapacity = new Semaphore(maxQueuedRequests, true);
        this.maxRetries = maxRetries;
        this.maxRetryDelaySeconds = maxRetryDelaySeconds;
    }

    public <T> T execute(String operation, Callable<T> request) {
        return execute(operation, request, false);
    }

    /**
     * Executes opportunistic work only when no normal request is active or waiting.
     * An HTTP request that is already in flight is allowed to finish.
     */
    public <T> T executeLowPriority(String operation, Callable<T> request) {
        return execute(operation, request, true);
    }

    private <T> T execute(String operation, Callable<T> request, boolean lowPriority) {
        long operationStartedAt = System.nanoTime();
        Semaphore capacity = lowPriority ? lowQueueCapacity : normalQueueCapacity;
        if (!capacity.tryAcquire()) {
            throw new HenrikApiQueueFullException("Henrik API request queue is full: " + operation);
        }

        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    acquireRequestSlot(lowPriority);
                    try {
                        awaitNextRequestSlot();
                        T result = request.call();
                        logSlowRequest(operation, operationStartedAt, attempt);
                        return result;
                    } finally {
                        releaseRequestSlot();
                    }
                } catch (HttpClientResponseException exception) {
                    boolean rateLimited = exception.getStatus() == HttpStatus.TOO_MANY_REQUESTS;
                    boolean temporaryServerFailure = exception.getStatus().getCode() == 408
                            || exception.getStatus().getCode() >= 500;
                    if ((!rateLimited && !temporaryServerFailure) || attempt >= maxRetries) {
                        throw exception;
                    }

                    long delaySeconds = rateLimited
                            ? retryDelaySeconds(exception)
                            : transientRetryDelaySeconds(attempt);
                    LOG.warn("Henrik API temporary HTTP {} during {}. Retrying in {}s ({}/{})",
                            exception.getStatus().getCode(), operation, delaySeconds, attempt + 1, maxRetries);
                    sleep(TimeUnit.SECONDS.toMillis(delaySeconds));
                } catch (HttpClientException exception) {
                    if (!isTransientNetworkFailure(exception) || attempt >= maxRetries) {
                        throw exception;
                    }
                    long delaySeconds = transientRetryDelaySeconds(attempt);
                    LOG.warn("Henrik API network timeout during {}. Retrying in {}s ({}/{})",
                            operation, delaySeconds, attempt + 1, maxRetries);
                    sleep(TimeUnit.SECONDS.toMillis(delaySeconds));
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new HenrikApiRequestException("Henrik API request failed: " + operation, exception);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new HenrikApiRequestException("Interrupted while waiting for Henrik API: " + operation, exception);
        } finally {
            capacity.release();
        }
    }

    private void logSlowRequest(String operation, long operationStartedAt, int retryCount) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - operationStartedAt);
        if (elapsedMillis >= 1_000) {
            LOG.info("Henrik API completed {} in {}ms (retries={})", operation, elapsedMillis, retryCount);
        }
    }

    private void acquireRequestSlot(boolean lowPriority) throws InterruptedException {
        if (!lowPriority) normalDemand.incrementAndGet();
        boolean locked = false;
        try {
            requestGate.lockInterruptibly();
            locked = true;
            while (requestInProgress || (lowPriority && normalDemand.get() > 0)) {
                requestAvailable.await();
            }
            requestInProgress = true;
        } finally {
            if (!lowPriority) normalDemand.decrementAndGet();
            if (locked) {
                requestAvailable.signalAll();
                requestGate.unlock();
            }
        }
    }

    private void releaseRequestSlot() {
        requestGate.lock();
        try {
            requestInProgress = false;
            requestAvailable.signalAll();
        } finally {
            requestGate.unlock();
        }
    }

    private boolean isTransientNetworkFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        String message = exception.getMessage();
        return message != null && (message.toLowerCase().contains("timed out")
                || message.toLowerCase().contains("connection reset"));
    }

    private long transientRetryDelaySeconds(int attempt) {
        long exponentialDelay = 1L << Math.min(attempt, 5);
        return Math.min(maxRetryDelaySeconds, exponentialDelay);
    }

    private void awaitNextRequestSlot() throws InterruptedException {
        long now = System.nanoTime();
        long waitNanos = Math.max(0L, nextRequestAtNanos.get() - now);
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
        nextRequestAtNanos.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(minimumIntervalMillis));
    }

    private long retryDelaySeconds(HttpClientResponseException exception) {
        String value = exception.getResponse().getHeaders().get("Retry-After");
        if (value == null || value.isBlank()) {
            value = exception.getResponse().getHeaders().get("X-RateLimit-Reset");
        }

        long parsed = 1L;
        if (value != null) {
            try {
                parsed = Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                LOG.debug("Unparseable Henrik retry delay header: {}", value);
            }
        }
        return Math.min(maxRetryDelaySeconds, Math.max(1L, parsed));
    }

    private void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    public static class HenrikApiQueueFullException extends RuntimeException {
        public HenrikApiQueueFullException(String message) {
            super(message);
        }
    }

    public static class HenrikApiRequestException extends RuntimeException {
        public HenrikApiRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
