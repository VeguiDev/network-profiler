package dev.networkprofiler.core;

import dev.networkprofiler.api.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Lightweight counters only. No packet catalog, packet content, disk IO or per-packet global lock. */
public final class PacketStatistics implements AutoCloseable {
    private final StatisticsOptions options;
    private final TimeSource clock;
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final Map<String, Counter> servers = new LinkedHashMap<>();
    private final LongAdder errors = new LongAdder(), untracked = new LongAdder();
    private ScheduledExecutorService worker;
    private long sampled;
    private volatile boolean closed, complete = true;
    private volatile PacketStatisticsSnapshot snapshot;
    private static final class Counter {
        final LongAdder in = new LongAdder(), out = new LongAdder();
        long previousIn, previousOut;
        void add(TrafficDirection direction, int bytes) { (direction == TrafficDirection.INBOUND ? in : out).add(bytes); }
    }
    public final class Connection {
        private final String id = UUID.randomUUID().toString();
        private final Counter counter = new Counter();
        private volatile PacketContext context = new PacketContext(id, null, null, PacketContext.UNASSIGNED);
        private volatile boolean active = true;
        private volatile long disconnected;
        private volatile Association association;
        private Connection(Counter server) { association = new Association(context, counter, server); }
        public String id() { return id; }
        public PacketContext context() { return context; }
        public boolean active() { return active; }
        public Association association() { return association; }
        /** Called on the channel's event loop, not while aggregating observations. */
        public void associate(String playerId, String name, String serverId) {
            PacketContext next = new PacketContext(id, playerId, name, serverId);
            Counter server = server(serverId);
            context = next; association = new Association(next, counter, server);
        }
        public void disconnect() { disconnected = clock.nanoTime(); active = false; }
    }
    /** Captured once at observation. A later server change cannot move this observation. */
    public final class Association {
        private final PacketContext context;
        private final Counter connection, server;
        private Association(PacketContext context, Counter connection, Counter server) {
            this.context = context; this.connection = connection; this.server = server;
        }
        public PacketContext context() { return context; }
        public void record(TrafficDirection direction, int bytes, boolean cancelled) {
            if (closed || !options.enabled() || cancelled || bytes < 0) return;
            connection.add(direction, bytes);
            if (server != null) server.add(direction, bytes); else { complete = false; untracked.add(bytes); }
        }
    }
    public PacketStatistics(StatisticsOptions options) { this(options, TimeSource.SYSTEM); }
    public PacketStatistics(StatisticsOptions options, TimeSource clock) {
        this.options = options; this.clock = clock; sampled = clock.nanoTime();
        servers.put(PacketContext.UNASSIGNED, new Counter()); servers.put(PacketContext.TRANSITION, new Counter());
        snapshot = new PacketStatisticsSnapshot(clock.epochMillis(), 0, false, true, "0", "0", List.of(), List.of());
    }
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Statistics closed");
        if (worker != null || !options.enabled()) return;
        worker = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "network-profiler-statistics"); t.setDaemon(true); return t; });
        worker.scheduleWithFixedDelay(() -> { try { sample(); } catch (RuntimeException failure) { error(); } },
                options.intervalSeconds(), options.intervalSeconds(), TimeUnit.SECONDS);
    }
    public synchronized Connection open() {
        prune();
        if (closed || connections.size() >= options.maxConnections()) { complete = false; return null; }
        var connection = new Connection(servers.get(PacketContext.UNASSIGNED));
        connections.put(connection.id(), connection); return connection;
    }
    private synchronized Counter server(String id) {
        var counter = servers.get(id);
        if (counter != null) return counter;
        if (servers.size() >= options.maxServers() + 2) { complete = false; return null; }
        counter = new Counter(); servers.put(id, counter); return counter;
    }
    public void error() { errors.increment(); complete = false; }
    public void untracked(int bytes) { if (bytes > 0) untracked.add(bytes); complete = false; }
    public boolean enabled() { return options.enabled(); }
    public int intervalSeconds() { return options.intervalSeconds(); }
    public void registerServer(String id) { server(id); }
    public synchronized boolean containsServer(String id) { return servers.containsKey(id); }
    public PacketStatisticsSnapshot current() { return snapshot; }
    public synchronized boolean containsConnection(String id) { var c = connections.get(id); return c != null && c.active; }
    public synchronized PacketContext activeContext(String id) { var c = connections.get(id); return c != null && c.active ? c.context : null; }
    public synchronized boolean containsPlayer(String id) { return connections.values().stream().anyMatch(c -> c.active && id.equals(c.context.playerId())); }
    public synchronized void sample() {
        long now = clock.nanoTime(); double elapsed = (now - sampled) / 1_000_000d;
        if (elapsed <= 0 || closed) return;
        var serverRows = new ArrayList<PacketStatisticsSnapshot.Row>();
        var counts = new HashMap<String, Integer>();
        for (var c : connections.values()) if (c.active) counts.merge(c.context.serverId(), 1, Integer::sum);
        for (var entry : servers.entrySet()) {
            int count = counts.getOrDefault(entry.getKey(), 0);
            serverRows.add(row(entry.getKey(), null, null, entry.getKey(), count > 0, count, entry.getValue(), elapsed));
        }
        var connectionRows = new ArrayList<PacketStatisticsSnapshot.Row>();
        // Publish closed connections one last time before pruning, so their final interval is not lost.
        for (var c : connections.values()) connectionRows.add(row(c.id, c.context.playerId(), c.context.playerName(), c.context.serverId(), c.active, c.active ? 1 : 0, c.counter, elapsed));
        snapshot = new PacketStatisticsSnapshot(clock.epochMillis(), elapsed, true, complete, Long.toString(errors.sum()),
                Long.toString(untracked.sum()), serverRows, connectionRows);
        sampled = now; prune();
    }
    private PacketStatisticsSnapshot.Row row(String id, String player, String name, String server, boolean active, int count, Counter c, double elapsed) {
        long in = c.in.sum(), out = c.out.sum(), di = in - c.previousIn, dout = out - c.previousOut;
        c.previousIn = in; c.previousOut = out;
        return new PacketStatisticsSnapshot.Row(id, player, name, server, active, count, Long.toString(in), Long.toString(out),
                BigInteger.valueOf(in).add(BigInteger.valueOf(out)).toString(), Long.toString(di), Long.toString(dout), di * 1000d / elapsed, dout * 1000d / elapsed);
    }
    private void prune() {
        long cutoff = clock.nanoTime() - TimeUnit.SECONDS.toNanos(options.disconnectedRetentionSeconds());
        connections.values().removeIf(c -> !c.active && c.disconnected < cutoff
                && c.counter.previousIn == c.counter.in.sum() && c.counter.previousOut == c.counter.out.sum());
    }
    @Override public void close() {
        ScheduledExecutorService scheduler;
        synchronized (this) { if (closed) return; closed = true; scheduler = worker; }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
