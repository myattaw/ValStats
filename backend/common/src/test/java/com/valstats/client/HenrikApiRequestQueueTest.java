package com.valstats.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HenrikApiRequestQueueTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void normalRequestRunsBeforeWaitingLowPriorityRequest() throws Exception {
        HenrikApiRequestQueue queue = new HenrikApiRequestQueue(100_000, 10, 0, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();

        Future<String> activeLow = executor.submit(() -> queue.executeLowPriority("active history", () -> {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            order.add("active-low");
            return "active-low";
        }));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        Future<String> waitingLow = executor.submit(() -> queue.executeLowPriority("waiting history", () -> {
            order.add("waiting-low");
            return "waiting-low";
        }));
        Future<String> normal = executor.submit(() -> queue.execute("account", () -> {
            order.add("normal");
            return "normal";
        }));

        Thread.sleep(50);
        releaseFirst.countDown();

        assertEquals("active-low", activeLow.get(2, TimeUnit.SECONDS));
        assertEquals("normal", normal.get(2, TimeUnit.SECONDS));
        assertEquals("waiting-low", waitingLow.get(2, TimeUnit.SECONDS));
        assertEquals(List.of("active-low", "normal", "waiting-low"), order);
    }
}
