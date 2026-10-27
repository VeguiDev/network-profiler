package dev.vegui.vnp.core;

import dev.vegui.vnp.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TrafficProfilerTest {
    static final class Clock implements TimeSource {
        long nanos;
        public long nanoTime() { return nanos; }
        public long epochMillis() { return 1_790_000_000_000L + nanos / 1_000_000; }
        void advance(long millis) { nanos += millis * 1_000_000; }
    }
    @Test void computesRatesFromActualElapsedTimeAndKeepsPeaks() {
        var clock = new Clock(); var profiler = new TrafficProfiler(900, true, true, clock);
        assertNull(profiler.current().traffic().inbound().bytesPerSecond());
        profiler.collector().received(3000); profiler.collector().sent(6000); clock.advance(1500);
        var sample = profiler.sample();
        assertEquals(2000d, sample.traffic().inbound().bytesPerSecond());
        assertEquals(4000d, sample.traffic().outbound().bytesPerSecond());
        assertEquals("9000", sample.traffic().combinedTotalBytes());
        assertEquals(1500d, sample.intervalMs());
        clock.advance(1000); sample = profiler.sample();
        assertEquals(0d, sample.traffic().inbound().bytesPerSecond());
        assertEquals(2000d, sample.traffic().inbound().peakBytesPerSecond());
    }
    @Test void retainsBoundedOrderedHistoryAndExpiresByElapsedTime() {
        var clock = new Clock(); var profiler = new TrafficProfiler(3, true, true, clock);
        for (int i = 0; i < 5; i++) { clock.advance(1000); profiler.sample(); }
        assertEquals(List.of(3L, 4L, 5L), profiler.history().stream().map(TrafficSnapshot::sequence).toList());
        clock.advance(3000); assertTrue(profiler.history().isEmpty());
        profiler.sample(); assertEquals(1, profiler.history().size()); // no invented samples
    }
    @Test void resetIsolatesLateCompletionsAndClearsPeaksAndHistory() {
        var clock = new Clock(); var profiler = new TrafficProfiler(900, true, true, clock);
        var old = profiler.collector(); String session = profiler.current().sessionId();
        old.sent(500); clock.advance(1000); profiler.sample(); profiler.reset();
        old.sent(9000); profiler.collector().sent(7); clock.advance(1000);
        var sample = profiler.sample();
        assertNotEquals(session, sample.sessionId()); assertEquals("7", sample.traffic().outbound().totalBytes());
        assertEquals(1, sample.sequence()); assertEquals(1, profiler.history().size());
        assertEquals(7d, sample.traffic().outbound().peakBytesPerSecond());
    }
    @Test void concurrentCollectionDoesNotLoseBytes() throws Exception {
        var clock = new Clock(); var profiler = new TrafficProfiler(900, true, true, clock);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < 8; t++) futures.add(pool.submit(() -> {
                for (int i = 0; i < 50_000; i++) { profiler.collector().received(3); profiler.collector().sent(5); }
            }));
            for (var future : futures) future.get();
        }
        clock.advance(1000); var sample = profiler.sample();
        assertEquals("1200000", sample.traffic().inbound().totalBytes());
        assertEquals("2000000", sample.traffic().outbound().totalBytes());
    }
    @Test void disabledDirectionIsUnavailableRatherThanZero() {
        var clock = new Clock(); var profiler = new TrafficProfiler(900, false, true, clock);
        profiler.collector().received(10); clock.advance(1000); var sample = profiler.sample();
        assertFalse(sample.traffic().inbound().available()); assertNull(sample.traffic().inbound().totalBytes());
        assertNull(sample.traffic().combinedTotalBytes());
    }
    @Test void storesRealPlayerCountsWithHistoryAndDoesNotInventZeroOnProviderFailure() {
        var clock = new Clock(); var profiler = new TrafficProfiler(900, true, true, clock);
        profiler.playerCount(() -> 7); clock.advance(1000); assertEquals(7, profiler.sample().players());
        profiler.playerCount(() -> { throw new IllegalStateException("Unavailable"); }); clock.advance(1000);
        assertNull(profiler.sample().players()); assertEquals(7, profiler.history().getFirst().players());
    }
    @Test void invalidConfigurationAndDuplicateProvidersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficProfiler(0, true, true));
        var profiler = new TrafficProfiler(900, true, true);
        MetricProvider provider = new MetricProvider() {
            public String id() { return "test"; }
            public Set<ProfilerCapability> capabilities() { return Set.of(ProfilerCapability.TOTAL_TRAFFIC); }
            public void start() {}
            public void close() {}
        };
        profiler.register(provider);
        assertThrows(IllegalArgumentException.class, () -> profiler.register(provider));
    }
    @Test void samplerPublishesAndStops() throws Exception {
        var profiler = new TrafficProfiler(900, true, true);
        var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var latch = new CountDownLatch(1); profiler.subscribe(s -> { thread.set(Thread.currentThread()); latch.countDown(); });
        profiler.start(); assertTrue(latch.await(4, TimeUnit.SECONDS)); assertTrue(profiler.healthy());
        profiler.close(); profiler.close(); assertEquals("STOPPED", profiler.state());
        // Executor termination can be signalled just before the worker Thread.run() returns.
        thread.get().join(2000);
        assertFalse(thread.get().isAlive());
        assertThrows(IllegalStateException.class, profiler::start);
    }
    @Test void healthExpiresUsingMonotonicTimeEvenIfWallClockMovesBackward() throws Exception {
        var clock = new Clock();
        var profiler = new TrafficProfiler(900, true, true, new TimeSource() {
            public long nanoTime() { return clock.nanoTime(); }
            public long epochMillis() { return 1; }
        });
        try {
            profiler.start(); assertTrue(profiler.healthy());
            clock.advance(5000); assertFalse(profiler.healthy());
            profiler.sample(); assertTrue(profiler.healthy());
        } finally { profiler.close(); }
    }
}
