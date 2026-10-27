package dev.networkprofiler.core;

import dev.networkprofiler.api.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;

/** Reproducible hot-path probe. Excludes PacketEvents, pipeline lookup, disk and UI costs. */
public final class PacketCaptureBenchmark {
    private record Measurement(long nanos, long bytes) {}
    public static void main(String[] args) throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported()) bean.setThreadAllocatedMemoryEnabled(true);
        for (boolean detailed : new boolean[]{false, true}) for (int threads : new int[]{1, 8}) {
            var provider = new PacketCapturesTest.Provider();
            try (var stats = new PacketStatistics(StatisticsOptions.defaults());
                 var captures = new PacketCaptures(provider, new PacketCapturesTest.MemoryStore(), CaptureOptions.defaults());
                 var pool = Executors.newFixedThreadPool(threads)) {
                PacketCapturesTest.ready(captures); var capture = detailed ? captures.start() : null;
                var recorder = provider.recorder;
                int iterations = 1_000_000;
                var gate = new CyclicBarrier(threads);
                var tasks = new ArrayList<Future<Measurement>>();
                for (int thread = 0; thread < threads; thread++) {
                    var connection = stats.open(); connection.associate(UUID.randomUUID().toString(), "Player" + thread, "lobby");
                    var association = connection.association();
                    tasks.add(pool.submit(() -> {
                    for (int i = 0; i < 200_000; i++) {
                        association.record(TrafficDirection.INBOUND, 150, false);
                        if (detailed) recorder.record(association.context(), i & 1, 150, false, false);
                    }
                    gate.await();
                    long id = Thread.currentThread().threadId();
                    long allocated = bean.isThreadAllocatedMemorySupported() ? bean.getThreadAllocatedBytes(id) : -1;
                    long start = System.nanoTime();
                    for (int i = 0; i < iterations; i++) {
                        association.record(TrafficDirection.INBOUND, 150, false);
                        if (detailed) recorder.record(association.context(), i & 1, 150, false, false);
                    }
                    long elapsed = System.nanoTime() - start;
                    return new Measurement(elapsed, allocated < 0 ? -1 : bean.getThreadAllocatedBytes(id) - allocated);
                }));
                }
                long nanos = 0, bytes = 0;
                for (var task : tasks) { var measured = task.get(); nanos += measured.nanos(); bytes += measured.bytes(); }
                var report = capture == null ? null : captures.stop(capture.id()).get();
                long observations = (long) threads * iterations;
                System.out.printf(Locale.ROOT, "mode=%s threads=%d mean-thread-wall-ns/observation=%.2f allocated-bytes/observation=%.4f captured=%s%n",
                        detailed ? "statistics+attributed-capture" : "statistics-only", threads, (double) nanos / observations, (double) bytes / observations, report == null ? "off" : report.packetCount());
            }
        }
        System.out.println("Indicative warmed core-only probe, NOT a TPS/production-overhead guarantee; test representative workloads with PacketEvents installed.");
    }
}
