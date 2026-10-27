package dev.networkprofiler.core;

import dev.networkprofiler.api.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One capture; constant work per observation, bounded type counters, no packet objects retained. */
public final class PacketCaptures implements AutoCloseable {
    private final PacketCaptureProvider provider;
    private final ReportStore store;
    private final CaptureOptions options;
    private final TimeSource clock;
    private final PopulationProvider population;
    private final ScheduledThreadPoolExecutor worker = createWorker();
    private static ScheduledThreadPoolExecutor createWorker() {
        var worker = new ScheduledThreadPoolExecutor(1, r -> {
            var t = new Thread(r, "network-profiler-captures"); t.setDaemon(true); return t;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return worker;
    }
    private final LinkedHashMap<String, PacketCaptureReport> reports = new LinkedHashMap<>();
    private final ThreadLocal<Cursor> cursor = ThreadLocal.withInitial(Cursor::new);
    private volatile Session active;
    private volatile boolean available, closed;
    private volatile String detail = "Loading saved reports";
    private ScheduledFuture<?> deadline;
    private ScheduledFuture<?> checkpoint;
    private ScheduledFuture<?> sampling;
    private CompletableFuture<PacketCaptureReport> stopping;
    private String stoppingId;
    private Session finalizing;
    private long finalizingEnded, finalizingNow;
    private static final class Cursor { Object token; AtomicInteger gate; }
    private static final class Counter {
        final LongAdder bytes = new LongAdder(), packets = new LongAdder(), cancelled = new LongAdder(), modified = new LongAdder();
    }
    private final class Session implements PacketCaptureProvider.Recorder {
        final CaptureScope scope;
        final String id = UUID.randomUUID().toString();
        final Object token = new Object();
        final long startedAt = clock.epochMillis(), started = clock.nanoTime();
        final List<PacketCaptureReport.Descriptor> descriptors = List.copyOf(provider.descriptors());
        final Counter[] counters = new Counter[descriptors.size()];
        final ConcurrentMap<Thread, AtomicInteger> gates = new ConcurrentHashMap<>();
        final LongAdder errors = new LongAdder();
        final ConcurrentMap<PacketContext, ConcurrentMap<PacketCaptureReport.Descriptor, Counter>> dimensions = new ConcurrentHashMap<>();
        volatile int dimensionRows;
        volatile boolean attributionComplete = true;
        volatile boolean accepting = true;
        volatile String storageWarning;
        final List<CaptureTimeline.Sample> samples = new ArrayList<>();
        final Map<PacketContext, long[]> previousDimensions = new HashMap<>();
        BigInteger previousIn = BigInteger.ZERO, previousOut = BigInteger.ZERO;
        long sampled = started;
        int timelineEntries;
        volatile boolean timelineComplete = true;
        PlayerPopulation previousPopulation;
        Session(CaptureScope scope) { this.scope = scope; Arrays.setAll(counters, i -> new Counter()); }
        public void record(int index, int bytes, boolean cancelled, boolean modified) {
            record(PacketContext.UNKNOWN, index, bytes, cancelled, modified);
        }
        public void record(PacketContext context, int index, int bytes, boolean cancelled, boolean modified) {
            if (!scope.matches(context)) {
                // Lost identity means we cannot establish exclusion from a scoped capture.
                if (context.connectionId().isEmpty()) error();
                return;
            }
            var gate = enter();
            if (gate == null) return;
            try {
                if (!accepting) return;
                if (index < 0 || index >= counters.length || bytes < 0) { errors.increment(); attributionComplete = false; return; }
                add(counters[index], bytes, cancelled, modified);
                if (context.connectionId().isEmpty()) { attributionComplete = false; return; }
                var descriptor = descriptors.get(index);
                var bucket = dimensions.get(context);
                var c = bucket == null ? null : bucket.get(descriptor);
                if (c == null) {
                    if (dimensionRows >= options.maxAttributionRows()) { attributionComplete = false; return; }
                    // Only first observation of a dimension/type allocates. Steady-state has no global lock.
                    synchronized (dimensions) {
                        bucket = dimensions.get(context);
                        c = bucket == null ? null : bucket.get(descriptor);
                        if (c == null) {
                            if (dimensionRows >= options.maxAttributionRows()) { attributionComplete = false; return; }
                            if (bucket == null) { bucket = new ConcurrentHashMap<>(); dimensions.put(context, bucket); }
                            c = new Counter(); bucket.put(descriptor, c); dimensionRows++;
                        }
                    }
                }
                add(c, bytes, cancelled, modified);
            } finally { gate.set(0); }
        }
        private AtomicInteger enter() {
            var local = cursor.get();
            if (local.token != token) {
                local.gate = gates.computeIfAbsent(Thread.currentThread(), ignored -> new AtomicInteger());
                local.token = token;
            }
            return accepting && local.gate.compareAndSet(0, 1) ? local.gate : null;
        }
        private void add(Counter c, int bytes, boolean cancelled, boolean modified) {
            if (cancelled) c.cancelled.increment();
            else { c.bytes.add(bytes); c.packets.increment(); if (modified) c.modified.increment(); }
        }
        public void connectionClosed(String connectionId) {
            if (accepting && scope.type().equals("CONNECTION") && scope.id().equals(connectionId))
                PacketCaptures.this.stop(id, "COMPLETED", "Scoped connection closed");
        }
        public void error() {
            var gate = enter();
            if (gate == null) return;
            try { if (accepting) { errors.increment(); attributionComplete = false; } }
            finally { gate.set(0); }
        }
        void freeze() {
            accepting = false;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // Only the worker waits. Per-thread gates avoid a global per-packet contention point.
            for (var gate : gates.values()) while (!gate.compareAndSet(0, -1)) {
                if (System.nanoTime() > until) throw new IllegalStateException("Packet observer did not quiesce");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
        }
        synchronized void sample(long now) {
            if (!samples.isEmpty() && now <= sampled) return;
            if (samples.size() >= options.maxTimelineSamples()) { timelineComplete = false; return; }
            var deltas = new ArrayList<CaptureTimeline.Delta>();
            boolean complete = attributionComplete;
            for (var entry : dimensions.entrySet()) {
                long in = 0, out = 0;
                for (var value : entry.getValue().entrySet()) {
                    if (value.getKey().direction() == TrafficDirection.INBOUND) in += value.getValue().bytes.sum();
                    else out += value.getValue().bytes.sum();
                }
                var old = previousDimensions.computeIfAbsent(entry.getKey(), ignored -> new long[2]);
                long di = in - old[0], dout = out - old[1]; old[0] = in; old[1] = out;
                if (di == 0 && dout == 0) continue;
                if (timelineEntries < options.maxTimelineEntries()) {
                    deltas.add(new CaptureTimeline.Delta(entry.getKey(), Long.toString(di), Long.toString(dout))); timelineEntries++;
                } else complete = false;
            }
            BigInteger in = BigInteger.ZERO, out = BigInteger.ZERO;
            for (var d : descriptors) {
                var bytes = BigInteger.valueOf(counters[d.index()].bytes.sum());
                if (d.direction() == TrafficDirection.INBOUND) in = in.add(bytes); else out = out.add(bytes);
            }
            var contexts = new HashSet<>(dimensions.keySet());
            if (scope.type().equals("PLAYER")) contexts.add(new PacketContext("", scope.id(), null, PacketContext.UNASSIGNED));
            if (scope.type().equals("CONNECTION")) contexts.add(new PacketContext(scope.id(), null, null, PacketContext.UNASSIGNED));
            PlayerPopulation players;
            try { players = samples.isEmpty() ? PlayerPopulation.unavailable() : population.snapshot(Set.copyOf(contexts)); }
            catch (RuntimeException failure) { players = PlayerPopulation.unavailable(); }
            if (!players.equals(previousPopulation) && players.entries() > options.maxTimelineEntries() - timelineEntries)
                players = new PlayerPopulation(players.global(), Map.of(), Map.of(), Map.of(), false);
            if (!complete || !samples.isEmpty() && !players.complete()) timelineComplete = false;
            if (players.equals(previousPopulation)) players = null; // unchanged point-in-time membership, compact on disk
            else { timelineEntries += players.entries(); previousPopulation = players; }
            samples.add(new CaptureTimeline.Sample(startedAt + Math.max(0, (now - started) / 1_000_000),
                    Math.max(0, (now - started) / 1_000_000d), Math.max(0, (now - sampled) / 1_000_000d),
                    in.subtract(previousIn).toString(), out.subtract(previousOut).toString(), complete, deltas, players));
            previousIn = in; previousOut = out; sampled = now;
        }
        synchronized CaptureTimeline timeline() { return new CaptureTimeline(timelineComplete, samples); }
        PacketCaptureReport snapshot(String state, String reason, Long ended, long now) {
            var rows = new ArrayList<PacketCaptureReport.Row>();
            BigInteger in = BigInteger.ZERO, out = BigInteger.ZERO, count = BigInteger.ZERO,
                    cancelled = BigInteger.ZERO, modified = BigInteger.ZERO;
            for (var descriptor : descriptors) {
                var c = counters[descriptor.index()];
                long bytes = c.bytes.sum(), packets = c.packets.sum(), dropped = c.cancelled.sum(), changed = c.modified.sum();
                if (packets == 0 && dropped == 0) continue;
                rows.add(new PacketCaptureReport.Row(descriptor.name(), descriptor.protocolState(), descriptor.direction(),
                        Long.toString(bytes), Long.toString(packets), Long.toString(dropped), Long.toString(changed)));
                if (descriptor.direction() == TrafficDirection.INBOUND) in = in.add(BigInteger.valueOf(bytes));
                else out = out.add(BigInteger.valueOf(bytes));
                count = count.add(BigInteger.valueOf(packets)); cancelled = cancelled.add(BigInteger.valueOf(dropped));
                modified = modified.add(BigInteger.valueOf(changed));
            }
            var attribution = new ArrayList<PacketCaptureReport.Attribution>();
            dimensions.forEach((context, bucket) -> {
                var detailRows = new ArrayList<PacketCaptureReport.Row>();
                bucket.forEach((d, c) -> detailRows.add(new PacketCaptureReport.Row(d.name(), d.protocolState(), d.direction(),
                        Long.toString(c.bytes.sum()), Long.toString(c.packets.sum()), Long.toString(c.cancelled.sum()), Long.toString(c.modified.sum()))));
                detailRows.sort(Comparator.comparing(PacketCaptureReport.Row::protocolState).thenComparing(PacketCaptureReport.Row::name).thenComparing(r -> r.direction().name()));
                attribution.add(new PacketCaptureReport.Attribution(context, detailRows));
            });
            attribution.sort(Comparator.comparing((PacketCaptureReport.Attribution a) -> a.context().connectionId()).thenComparing(a -> a.context().serverId()).thenComparing(a -> Objects.toString(a.context().playerId(), "")));
            return new PacketCaptureReport(3, id, state, startedAt, ended, Math.max(0, (now - started) / 1_000_000), reason,
                    in.toString(), out.toString(), in.add(out).toString(), count.toString(), cancelled.toString(),
                    modified.toString(), Long.toString(errors.sum()), List.copyOf(rows), scope, attributionComplete, attribution, timeline());
        }
    }
    public PacketCaptures(PacketCaptureProvider provider, ReportStore store, CaptureOptions options) {
        this(provider, store, options, TimeSource.SYSTEM);
    }
    public PacketCaptures(PacketCaptureProvider provider, ReportStore store, CaptureOptions options, TimeSource clock) {
        this(provider, store, options, clock, PopulationProvider.UNAVAILABLE);
    }
    public PacketCaptures(PacketCaptureProvider provider, ReportStore store, CaptureOptions options, TimeSource clock, PopulationProvider population) {
        this.provider = provider; this.store = store; this.options = options; this.clock = clock; this.population = population;
        var descriptors = provider == null ? List.<PacketCaptureReport.Descriptor>of() : provider.descriptors();
        if (descriptors.size() > 4096) throw new IllegalArgumentException("Too many packet types");
        for (int i = 0; i < descriptors.size(); i++) if (descriptors.get(i).index() != i)
            throw new IllegalArgumentException("Packet descriptors must use dense indices");
        worker.execute(() -> {
            try {
                var loaded = store.load();
                synchronized (this) {
                    loaded.stream().sorted(Comparator.comparingLong(PacketCaptureReport::startedAt)).forEach(r -> reports.put(r.id(), r));
                    trim(); available = provider != null; detail = available ? "Ready" : "PacketEvents analysis is unavailable";
                }
            } catch (Exception failure) { detail = "Cannot load reports: " + failure.getMessage(); }
        });
    }
    public boolean available() { return available && !closed && provider != null && provider.available(); }
    public String detail() {
        return available && !closed && provider != null && !provider.available()
                ? "Packet source unavailable; check transport health and PacketEvents" : detail;
    }
    public int maxDurationSeconds() { return options.maxDurationSeconds(); }
    /** Explicit sampling hook; the production scheduler calls this off the packet event loops. */
    public synchronized void sample() { if (active != null) active.sample(clock.nanoTime()); }
    public synchronized PacketCaptureReport start(int durationSeconds) {
        return start(durationSeconds, CaptureScope.GLOBAL);
    }
    public synchronized PacketCaptureReport start(int durationSeconds, CaptureScope scope) {
        if (!available()) throw new IllegalStateException(detail());
        if (active != null || finalizing != null) throw new IllegalStateException("A capture is already running or finalizing");
        if (durationSeconds < 1 || durationSeconds > options.maxDurationSeconds())
            throw new IllegalArgumentException("Duration must be 1.." + options.maxDurationSeconds() + " seconds");
        var s = new Session(Objects.requireNonNull(scope)); active = s;
        s.sample(s.started);
        try { provider.start(s); }
        catch (RuntimeException failure) { s.accepting = false; active = null; provider.stop(); throw failure; }
        deadline = worker.schedule(() -> stop(s.id, "COMPLETED", "Duration limit reached"), durationSeconds, TimeUnit.SECONDS);
        sampling = worker.scheduleWithFixedDelay(() -> {
            if (!s.accepting) return;
            if (!provider.available()) { stop(s.id, "INTERRUPTED", "Packet source became unavailable"); return; }
            try { s.sample(clock.nanoTime()); } catch (RuntimeException failure) { s.timelineComplete = false; }
        }, 1, 1, TimeUnit.SECONDS);
        checkpoint = worker.scheduleWithFixedDelay(() -> {
            if (!s.accepting) return;
            if (!provider.available()) { stop(s.id, "INTERRUPTED", "Packet source became unavailable"); return; }
            try { store.save(s.snapshot("RUNNING", "Partial checkpoint; capture is still active", null, clock.nanoTime())); s.storageWarning = null; }
            catch (Exception failure) { s.storageWarning = "Cannot checkpoint report: " + failure.getMessage(); }
        }, 0, 5, TimeUnit.SECONDS);
        detail = "Recording protocol observations";
        return s.snapshot("RUNNING", detail, null, clock.nanoTime());
    }
    public PacketCaptureReport start() { return start(options.maxDurationSeconds()); }
    public PacketCaptureReport start(CaptureScope scope) { return start(options.maxDurationSeconds(), scope); }
    public synchronized PacketCaptureReport current() {
        return active == null ? null : active.snapshot("RUNNING", active.storageWarning == null
                ? "Protocol observations, not confirmed transport writes" : active.storageWarning, null, clock.nanoTime());
    }
    public CompletableFuture<PacketCaptureReport> stop(String id) { return stop(id, "COMPLETED", "Stopped by administrator"); }
    private synchronized CompletableFuture<PacketCaptureReport> stop(String id, String state, String reason) {
        if (id != null && id.equals(stoppingId) && stopping != null) return stopping;
        if (active == null || id != null && !id.equals(active.id)) {
            var saved = reports.get(id);
            if (saved != null) return CompletableFuture.completedFuture(saved);
            throw new IllegalStateException("No matching active capture");
        }
        Session s = active; s.accepting = false; active = null;
        if (deadline != null) deadline.cancel(false);
        if (checkpoint != null) checkpoint.cancel(false);
        if (sampling != null) sampling.cancel(false);
        long ended = clock.epochMillis(), now = clock.nanoTime();
        finalizing = s; finalizingEnded = ended; finalizingNow = now; detail = "Finalizing report " + s.id;
        stoppingId = s.id; stopping = new CompletableFuture<>();
        var result = stopping;
        // Stop/freezing, serialization and disk IO never run on Minecraft's event loops.
        worker.execute(() -> {
            String finalState = state, message = reason;
            if (state.equals("COMPLETED") && !provider.available()) { finalState = "INTERRUPTED"; message = "Packet source became unavailable; coverage may be incomplete"; }
            try { provider.stop(); s.freeze(); }
            catch (RuntimeException failure) { available = false; finalState = "INTERRUPTED"; message = "Observer cleanup failed: " + failure.getMessage(); }
            try { s.sample(now); } catch (RuntimeException failure) { s.timelineComplete = false; }
            var report = s.snapshot(finalState, message, ended, now);
            try { store.save(report); }
            catch (Exception failure) {
                report = s.snapshot("SAVE_FAILED", "Report is only in memory: " + failure.getMessage(), ended, now);
            }
            synchronized (this) { reports.put(report.id(), report); trim(); detail = report.detail(); finalizing = null; }
            result.complete(report);
        });
        return result;
    }
    private void trim() {
        long cutoff = clock.epochMillis() - TimeUnit.DAYS.toMillis(options.retentionDays());
        reports.values().removeIf(r -> r.startedAt() < cutoff);
        while (reports.size() > options.maxReports()) reports.remove(reports.keySet().iterator().next());
    }
    public synchronized List<PacketCaptureReport> list() {
        trim(); var result = new ArrayList<>(reports.values());
        if (active != null) result.add(current());
        else if (finalizing != null) result.add(finalizing.snapshot("FINALIZING", "Saving report", finalizingEnded, finalizingNow));
        Collections.reverse(result); return List.copyOf(result);
    }
    public synchronized PacketCaptureReport find(String id) {
        return list().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (active != null) stop(active.id, "INTERRUPTED", "Proxy shutting down");
            worker.shutdown();
        }
        try { if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); worker.shutdownNow(); }
        cursor.remove();
    }
}
