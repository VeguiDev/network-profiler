package dev.networkprofiler.core;

import dev.networkprofiler.api.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PacketCapturesTest {
    static final class Provider implements PacketCaptureProvider {
        volatile Recorder recorder;
        volatile boolean healthy = true;
        public boolean available() { return healthy; }
        public List<PacketCaptureReport.Descriptor> descriptors() { return List.of(
                new PacketCaptureReport.Descriptor(0, "PING", "STATUS", TrafficDirection.INBOUND),
                new PacketCaptureReport.Descriptor(1, "PONG", "STATUS", TrafficDirection.OUTBOUND)); }
        public void start(Recorder recorder) { this.recorder = recorder; }
        public void stop() { }
    }
    static class MemoryStore implements ReportStore {
        final List<PacketCaptureReport> saved = new CopyOnWriteArrayList<>();
        public List<PacketCaptureReport> load() { return List.copyOf(saved); }
        public void save(PacketCaptureReport report) throws IOException { saved.removeIf(r -> r.id().equals(report.id())); saved.add(report); }
    }
    static void ready(PacketCaptures captures) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!captures.available() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(captures.available(), captures.detail());
    }
    @Test void countsTypesDirectionsCancellationAndErrorsAndPersistsAnImmutableReport() throws Exception {
        var provider = new Provider(); var store = new MemoryStore();
        try (var captures = new PacketCaptures(provider, store, CaptureOptions.defaults())) {
            ready(captures); var running = captures.start();
            provider.recorder.record(0, 150, false, false); provider.recorder.record(0, 10, true, false);
            provider.recorder.record(1, 100, false, true); provider.recorder.error();
            assertEquals("250", captures.current().totalBytes());
            var report = captures.stop(running.id()).get(3, TimeUnit.SECONDS);
            assertEquals("COMPLETED", report.state()); assertEquals("150", report.inboundBytes()); assertEquals("100", report.outboundBytes());
            assertEquals("2", report.packetCount()); assertEquals("1", report.cancelledPackets());
            assertEquals("1", report.modifiedPackets()); assertEquals("1", report.observationErrors());
            assertEquals(List.of(report), store.saved); assertSame(report, captures.find(report.id()));
            assertEquals(report, captures.stop(report.id()).get());
            assertThrows(UnsupportedOperationException.class, () -> report.packets().clear());
        }
    }
    @Test void rejectsOverlapAndKeepsOldCallbacksOutOfNewSessions() throws Exception {
        var provider = new Provider();
        try (var captures = new PacketCaptures(provider, new MemoryStore(), CaptureOptions.defaults())) {
            ready(captures); var first = captures.start(); var old = provider.recorder;
            assertThrows(IllegalStateException.class, captures::start);
            old.record(0, 20, false, false); captures.stop(first.id()).get(3, TimeUnit.SECONDS);
            var second = captures.start(); old.record(0, 999, false, false); old.error();
            provider.recorder.record(1, 30, false, false);
            var report = captures.stop(second.id()).get(3, TimeUnit.SECONDS);
            assertEquals("30", report.totalBytes()); assertEquals("0", report.observationErrors());
            assertEquals("20", captures.find(first.id()).totalBytes());
        }
    }
    @Test void concurrentCountersAreExactAndFreezeStopsLateObservations() throws Exception {
        var provider = new Provider();
        try (var captures = new PacketCaptures(provider, new MemoryStore(), CaptureOptions.defaults()); var pool = Executors.newFixedThreadPool(8)) {
            ready(captures); var capture = captures.start(); var recorder = provider.recorder;
            var jobs = new ArrayList<Future<?>>();
            for (int thread = 0; thread < 8; thread++) jobs.add(pool.submit(() -> {
                for (int i = 0; i < 25000; i++) recorder.record(i % 2, 10, false, false);
            }));
            for (var job : jobs) job.get();
            var report = captures.stop(capture.id()).get(3, TimeUnit.SECONDS);
            assertEquals("2000000", report.totalBytes()); assertEquals("200000", report.packetCount());
            for (int i = 0; i < 100; i++) recorder.record(0, 999, false, false);
            assertEquals(report, captures.find(report.id()));
        }
    }
    @Test void stoppingWhileEventsAreRunningDoesNotLeakIntoAnotherCapture() throws Exception {
        var provider = new Provider(); var run = new AtomicBoolean(true);
        try (var captures = new PacketCaptures(provider, new MemoryStore(), CaptureOptions.defaults()); var pool = Executors.newFixedThreadPool(4)) {
            ready(captures); var first = captures.start(); var recorder = provider.recorder;
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> { while (run.get()) recorder.record(0, 1, false, false); }));
            Thread.sleep(30);
            var report = captures.stop(first.id()).get(3, TimeUnit.SECONDS);
            var next = captures.start(); Thread.sleep(30);
            assertEquals("0", captures.current().totalBytes());
            assertEquals(report, captures.find(first.id()));
            captures.stop(next.id()).get(); run.set(false);
            for (var job : jobs) job.get();
        } finally { run.set(false); }
    }
    @Test void autoStopsAndLimitsRetention() throws Exception {
        var provider = new Provider();
        try (var captures = new PacketCaptures(provider, new MemoryStore(), new CaptureOptions(1, 7, 1))) {
            ready(captures);
            assertThrows(IllegalArgumentException.class, () -> captures.start(2));
            var first = captures.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while ((captures.find(first.id()) == null || !captures.find(first.id()).state().equals("COMPLETED")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("COMPLETED", captures.find(first.id()).state());
            var second = captures.start(); captures.stop(second.id()).get();
            assertEquals(1, captures.list().size()); assertNull(captures.find(first.id()));
        }
    }
    @Test void shutdownSavesInterruptedAndDiskFailureIsNotReportedAsSuccess() throws Exception {
        var provider = new Provider(); var store = new MemoryStore();
        var captures = new PacketCaptures(provider, store, CaptureOptions.defaults()); ready(captures); captures.start();
        captures.close(); captures.close();
        assertEquals("INTERRUPTED", store.saved.getFirst().state()); assertFalse(captures.available());
        assertThrows(IllegalStateException.class, captures::start);
        var failing = new MemoryStore() { @Override public void save(PacketCaptureReport report) throws IOException { throw new IOException("Disk full"); } };
        try (var next = new PacketCaptures(provider, failing, CaptureOptions.defaults())) {
            ready(next); var report = next.stop(next.start().id()).get();
            assertEquals("SAVE_FAILED", report.state()); assertTrue(report.detail().contains("Disk full")); assertNotNull(next.find(report.id()));
        }
    }
    @Test void slowDiskNeverBlocksStopCallerOrAllowsOverlappingCaptures() throws Exception {
        var provider = new Provider(); var writing = new CountDownLatch(1); var release = new CountDownLatch(1);
        var store = new MemoryStore() {
            @Override public void save(PacketCaptureReport report) throws IOException {
                if (report.state().equals("RUNNING")) {
                    writing.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test timeout"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                }
                super.save(report);
            }
        };
        try (var captures = new PacketCaptures(provider, store, CaptureOptions.defaults())) {
            ready(captures); var active = captures.start(); provider.recorder.record(0, 150, false, false);
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            var stopped = captures.stop(active.id()); assertFalse(stopped.isDone());
            assertEquals("FINALIZING", captures.find(active.id()).state()); assertEquals("150", captures.find(active.id()).totalBytes());
            assertThrows(IllegalStateException.class, captures::start);
            release.countDown(); assertEquals("COMPLETED", stopped.get(3, TimeUnit.SECONDS).state());
        } finally { release.countDown(); }
    }
    @Test void losingThePacketSourceNeverProducesACompletedReport() throws Exception {
        var provider = new Provider();
        try (var captures = new PacketCaptures(provider, new MemoryStore(), CaptureOptions.defaults())) {
            ready(captures); var active = captures.start();
            provider.recorder.record(0, 150, false, false);
            provider.healthy = false;
            assertFalse(captures.available()); assertTrue(captures.detail().contains("unavailable"));
            var report = captures.stop(active.id()).get(3, TimeUnit.SECONDS);
            assertEquals("INTERRUPTED", report.state()); assertEquals("150", report.totalBytes());
            assertTrue(report.detail().contains("unavailable"));
            assertThrows(IllegalStateException.class, captures::start);
        }
    }
}
