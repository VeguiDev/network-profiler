package dev.vegui.vnp.core;

import dev.vegui.vnp.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ScopedCaptureTest {
    private final String alice = UUID.randomUUID().toString(), bob = UUID.randomUUID().toString();
    private final String c1 = UUID.randomUUID().toString(), c2 = UUID.randomUUID().toString();
    private PacketContext context(String connection, String player, String server) { return new PacketContext(connection, player, "Player", server); }
    @Test void playerCaptureFollowsTransfersAndReconnectsWithoutMovingOldRows() throws Exception {
        var provider = new PacketCapturesTest.Provider();
        try (var captures = new PacketCaptures(provider, new PacketCapturesTest.MemoryStore(), CaptureOptions.defaults())) {
            PacketCapturesTest.ready(captures); var active = captures.start(60, new CaptureScope("PLAYER", alice));
            provider.recorder.record(context(c1, alice, "lobby"), 0, 100, false, false);
            provider.recorder.record(context(c1, alice, "survival"), 1, 200, false, false);
            provider.recorder.connectionClosed(c1); assertNotNull(captures.current());
            provider.recorder.record(context(c2, alice, "lobby"), 0, 50, false, false);
            provider.recorder.record(context(c2, bob, "lobby"), 0, 999, false, false);
            var report = captures.stop(active.id()).get(3, TimeUnit.SECONDS);
            assertEquals("350", report.totalBytes()); assertEquals(3, report.attributions().size());
            assertEquals("PLAYER", report.scope().type()); assertTrue(report.attributionComplete()); assertEquals(3, report.schemaVersion());
            assertEquals("200", report.attributions().stream().filter(a -> a.context().serverId().equals("survival")).findFirst().orElseThrow().packets().getFirst().totalBytes());
            assertThrows(UnsupportedOperationException.class, () -> report.attributions().getFirst().packets().clear());
        }
    }
    @Test void serverScopeOnlyIncludesAssociationAtObservationAndConnectionScopeAutoStops() throws Exception {
        var provider = new PacketCapturesTest.Provider();
        try (var captures = new PacketCaptures(provider, new PacketCapturesTest.MemoryStore(), CaptureOptions.defaults())) {
            PacketCapturesTest.ready(captures); var first = captures.start(new CaptureScope("SERVER", "lobby"));
            provider.recorder.record(context(c1, alice, "lobby"), 0, 20, false, false);
            provider.recorder.record(context(c1, alice, "survival"), 0, 99, false, false);
            provider.recorder.record(context(c2, bob, "lobby"), 1, 30, false, false);
            assertEquals("50", captures.stop(first.id()).get().totalBytes());
            var second = captures.start(new CaptureScope("CONNECTION", c1));
            provider.recorder.record(context(c2, bob, "lobby"), 0, 99, false, false);
            provider.recorder.record(context(c1, alice, "lobby"), 0, 10, false, false);
            provider.recorder.connectionClosed(c2); assertNotNull(captures.current());
            provider.recorder.connectionClosed(c1);
            assertEquals("10", captures.stop(second.id()).get(3, TimeUnit.SECONDS).totalBytes());
            assertEquals("Scoped connection closed", captures.find(second.id()).detail());
        }
    }
    @Test void dimensionLimitsNeverTruncateGlobalCountersSilently() throws Exception {
        var provider = new PacketCapturesTest.Provider();
        try (var captures = new PacketCaptures(provider, new PacketCapturesTest.MemoryStore(), new CaptureOptions(10, 7, 60, 1))) {
            PacketCapturesTest.ready(captures); var capture = captures.start();
            provider.recorder.record(context(c1, alice, "lobby"), 0, 100, false, false);
            provider.recorder.record(context(c2, bob, "lobby"), 1, 200, false, false);
            var report = captures.stop(capture.id()).get(); assertEquals("300", report.totalBytes());
            assertFalse(report.attributionComplete()); assertEquals(1, report.attributions().size());
        }
    }
    @Test void invalidScopeAndLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureScope("SERVER", ""));
        assertThrows(IllegalArgumentException.class, () -> new CaptureScope("PLAYER", "Alice"));
        assertThrows(IllegalArgumentException.class, () -> new CaptureScope("GLOBAL", "lobby"));
        assertThrows(IllegalArgumentException.class, () -> new CaptureOptions(10, 7, 60, 20001));
    }
    @Test void lostIdentityCannotBeSilentlyExcludedFromAScopedCapture() throws Exception {
        var provider = new PacketCapturesTest.Provider();
        try (var captures = new PacketCaptures(provider, new PacketCapturesTest.MemoryStore(), CaptureOptions.defaults())) {
            PacketCapturesTest.ready(captures); var capture = captures.start(new CaptureScope("PLAYER", alice));
            var recorder = provider.recorder;
            recorder.record(PacketContext.UNKNOWN, 0, 100, false, false);
            recorder.error();
            recorder.record(context(c1, alice, "lobby"), 0, 20, false, false);
            var report = captures.stop(capture.id()).get();
            assertEquals("20", report.totalBytes()); assertEquals("2", report.observationErrors());
            assertFalse(report.attributionComplete());
            recorder.error();
            assertEquals(report, captures.find(capture.id()));
        }
    }
}
