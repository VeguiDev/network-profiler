package dev.networkprofiler.core;

import dev.networkprofiler.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public final class TrafficProfiler implements TrafficSource, AutoCloseable {
    private final TimeSource clock;
    private final boolean inbound, outbound;
    private final SnapshotHistory history;
    private final Map<String, MetricProvider> providers = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<TrafficSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private volatile Session session;
    private volatile TrafficSnapshot current;
    private volatile String state = "STARTING", detail = "Waiting for instrumentation";
    private ScheduledExecutorService scheduler;
    private Thread samplerThread;
    private boolean closed;
    private volatile java.util.function.Supplier<Integer> playerCount = () -> null;
    public void playerCount(java.util.function.Supplier<Integer> supplier) { playerCount = Objects.requireNonNull(supplier); }
    private Integer players() { try { return playerCount.get(); } catch (RuntimeException unavailable) { return null; } }

    private static final class Session implements TrafficCollector {
        final String id = UUID.randomUUID().toString();
        final LongAdder received = new LongAdder(), sent = new LongAdder();
        final long started;
        final boolean inbound, outbound;
        long sampled, previousIn, previousOut, sequence;
        double peakIn, peakOut;
        Session(long now, boolean in, boolean out) { started = sampled = now; inbound = in; outbound = out; }
        public void received(long bytes) { if (inbound && bytes > 0) received.add(bytes); }
        public void sent(long bytes) { if (outbound && bytes > 0) sent.add(bytes); }
    }
    public TrafficProfiler(int historySeconds, boolean inbound, boolean outbound) {
        this(historySeconds, inbound, outbound, TimeSource.SYSTEM);
    }
    public TrafficProfiler(int historySeconds, boolean inbound, boolean outbound, TimeSource clock) {
        if (historySeconds < 1 || historySeconds > 86400) throw new IllegalArgumentException("History must be 1..86400 seconds");
        this.clock = clock; this.inbound = inbound; this.outbound = outbound;
        history = new SnapshotHistory(historySeconds, TimeUnit.SECONDS.toNanos(historySeconds));
        reset();
    }
    @Override public TrafficCollector collector() { return session; }
    public synchronized void register(MetricProvider provider) {
        if (closed || scheduler != null) throw new IllegalStateException("Register providers before starting an open profiler");
        if (providers.putIfAbsent(provider.id(), provider) != null) throw new IllegalArgumentException("Duplicate provider");
    }
    public synchronized void start() throws Exception {
        if (closed) throw new IllegalStateException("A closed profiler cannot restart; create a new instance");
        if (scheduler != null) return;
        try {
            for (var provider : providers.values()) provider.start();
            if (!state.equals("ERROR")) { state = "RUNNING"; detail = "Client-side transport bytes"; }
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                samplerThread = new Thread(r, "network-profiler-sampler"); samplerThread.setDaemon(true); return samplerThread;
            });
            scheduler.scheduleAtFixedRate(() -> {
                try { sample(); } catch (Throwable failure) { fail("Sampling failed: " + failure.getClass().getSimpleName()); }
            }, 1, 1, TimeUnit.SECONDS);
        } catch (Exception failure) { close(); fail("Instrumentation unavailable: " + failure.getMessage()); throw failure; }
    }
    public synchronized TrafficSnapshot sample() {
        Session s = session;
        long now = clock.nanoTime(), elapsed = now - s.sampled;
        if (elapsed <= 0) return current;
        long in = s.received.sum(), out = s.sent.sum();
        double inRate = (in - s.previousIn) * 1_000_000_000d / elapsed;
        double outRate = (out - s.previousOut) * 1_000_000_000d / elapsed;
        s.peakIn = Math.max(s.peakIn, inRate); s.peakOut = Math.max(s.peakOut, outRate);
        var snapshot = new TrafficSnapshot(s.id, ++s.sequence, clock.epochMillis(), elapsed / 1_000_000d,
                (now - s.started) / 1_000_000, new TrafficSnapshot.Traffic(
                direction(inbound, in, inRate, s.peakIn), direction(outbound, out, outRate, s.peakOut),
                inbound && outbound ? java.math.BigInteger.valueOf(in).add(java.math.BigInteger.valueOf(out)).toString() : null), players());
        s.sampled = now; s.previousIn = in; s.previousOut = out;
        current = snapshot; history.add(snapshot, now);
        for (var listener : listeners) {
            try { listener.accept(snapshot); } catch (RuntimeException ignored) { /* One consumer cannot stop collection. */ }
        }
        return snapshot;
    }
    private static TrafficSnapshot.Direction direction(boolean enabled, long total, Double rate, Double peak) {
        return new TrafficSnapshot.Direction(enabled, enabled ? Long.toString(total) : null, enabled ? rate : null, enabled ? peak : null);
    }
    public synchronized void reset() {
        session = new Session(clock.nanoTime(), inbound, outbound);
        current = new TrafficSnapshot(session.id, 0, clock.epochMillis(), 0, 0,
                new TrafficSnapshot.Traffic(direction(inbound, 0, null, null), direction(outbound, 0, null, null),
                        inbound && outbound ? "0" : null));
        history.clear();
    }
    public TrafficSnapshot current() { return current; }
    public synchronized List<TrafficSnapshot> history() { return history.snapshot(clock.nanoTime()); }
    public AutoCloseable subscribe(Consumer<TrafficSnapshot> listener) {
        listeners.add(listener); return () -> listeners.remove(listener);
    }
    public String state() { return state; }
    public String detail() { return detail; }
    public void fail(String message) { detail = message; state = "ERROR"; }
    public void disabled(String message) { detail = message; state = "DISABLED"; }
    public synchronized boolean healthy() { return state.equals("RUNNING") && clock.nanoTime() - session.sampled < TimeUnit.SECONDS.toNanos(5); }
    public Set<ProfilerCapability> capabilities() {
        if (!state.equals("RUNNING")) return Set.of();
        var result = EnumSet.noneOf(ProfilerCapability.class);
        providers.values().forEach(p -> result.addAll(p.capabilities()));
        return Set.copyOf(result);
    }
    @Override public void close() {
        ScheduledExecutorService stopping;
        List<MetricProvider> reverse;
        synchronized (this) {
            if (closed) return;
            closed = true; stopping = scheduler; scheduler = null;
            reverse = new ArrayList<>(providers.values()); Collections.reverse(reverse);
            state = "STOPPED";
        }
        if (stopping != null) {
            stopping.shutdownNow();
            // Do not hold the sampling monitor while waiting for its executor to terminate.
            if (Thread.currentThread() != samplerThread) {
                try {
                    if (!stopping.awaitTermination(5, TimeUnit.SECONDS)) detail = "Sampler cleanup timed out";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); detail = "Sampler cleanup interrupted";
                }
            }
        }
        for (var provider : reverse) {
            try { provider.close(); } catch (RuntimeException failure) { detail = "Provider cleanup failed: " + provider.id(); }
        }
        state = "STOPPED";
    }
}
