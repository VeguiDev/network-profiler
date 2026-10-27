package dev.vegui.vnp.server;

import dev.vegui.vnp.api.PacketCaptureReport;
import dev.vegui.vnp.core.CaptureOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FileReportStoreTest {
    @TempDir Path directory;
    private PacketCaptureReport report() { return new PacketCaptureReport(1, UUID.randomUUID().toString(), "COMPLETED", System.currentTimeMillis(), System.currentTimeMillis(), 1000,
            "Stopped", "9007199254740993", "0", "9007199254740993", "1", "0", "0", "0", List.of()); }
    @Test void atomicRoundTripRestartAndBoundedRetention() throws Exception {
        var options = new CaptureOptions(2, 7, 60); var store = new FileReportStore(directory, options);
        var first = report(); store.save(first); assertEquals(List.of(first), store.load());
        var second = report(); store.save(second); var third = report(); store.save(third);
        assertEquals(2, store.load().size());
        assertEquals(2, new FileReportStore(directory, options).load().size());
        try (var files = Files.list(directory)) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp"))); }
        for (var saved : store.load()) Files.setLastModifiedTime(directory.resolve(saved.id() + ".json"), FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)));
        assertTrue(store.load().isEmpty());
    }
    @Test void ignoresCorruptAndUnownedFilesWithoutPathTraversal() throws Exception {
        var store = new FileReportStore(directory, CaptureOptions.defaults()); var valid = report(); store.save(valid);
        Files.writeString(directory.resolve(UUID.randomUUID() + ".json"), "not JSON");
        Files.writeString(directory.resolve("keep-user-file.txt"), "preserve");
        assertEquals(List.of(valid), store.load()); assertEquals("preserve", Files.readString(directory.resolve("keep-user-file.txt")));
        var invalid = new PacketCaptureReport(1, "../outside", "COMPLETED", 0, 0L, 0, "", "0", "0", "0", "0", "0", "0", "0", List.of());
        assertThrows(IllegalArgumentException.class, () -> store.save(invalid));
    }
    @Test void recoversRunningCheckpointAsExplicitlyPartialAndInterrupted() throws Exception {
        var store = new FileReportStore(directory, CaptureOptions.defaults()); var complete = report();
        var checkpoint = new PacketCaptureReport(1, complete.id(), "RUNNING", complete.startedAt(), null, 5000, "Partial", "150", "0", "150", "1", "0", "0", "0", List.of());
        store.save(checkpoint);
        var recovered = store.load().getFirst();
        assertEquals("INTERRUPTED", recovered.state()); assertEquals("150", recovered.totalBytes());
        assertEquals(5000, recovered.durationMs()); assertTrue(recovered.detail().contains("missing"));
        assertEquals(recovered, new FileReportStore(directory, CaptureOptions.defaults()).load().getFirst());
    }
    @Test void preservesSchemaTwoAttributionAndLoadsLegacyWithoutInventedDimensions() throws Exception {
        var store = new FileReportStore(directory, CaptureOptions.defaults()); var old = report(); store.save(old);
        String connection = UUID.randomUUID().toString(), player = UUID.randomUUID().toString();
        var row = new PacketCaptureReport.Row("PING", "PLAY", dev.vegui.vnp.api.TrafficDirection.INBOUND, "150", "2", "0", "0");
        var modern = new PacketCaptureReport(2, UUID.randomUUID().toString(), "COMPLETED", System.currentTimeMillis(), System.currentTimeMillis(), 1000,
                "Stopped", "150", "0", "150", "2", "0", "0", "0", List.of(row), new dev.vegui.vnp.api.CaptureScope("PLAYER", player), true,
                List.of(new PacketCaptureReport.Attribution(new dev.vegui.vnp.api.PacketContext(connection, player, "Alice", "lobby"), List.of(row))));
        store.save(modern); var loaded = store.load(); assertTrue(loaded.contains(modern)); assertTrue(loaded.contains(old));
        assertTrue(loaded.stream().filter(r -> r.schemaVersion() == 1).findFirst().orElseThrow().attributions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> loaded.stream().filter(r -> r.schemaVersion() == 2).findFirst().orElseThrow().attributions().clear());
    }
    @Test void preservesSchemaThreeTimelinePopulationAndRecoveryWithoutChangingOldReports() throws Exception {
        var store = new FileReportStore(directory, CaptureOptions.defaults()); var old = report(); store.save(old);
        var context = new dev.vegui.vnp.api.PacketContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Alice", "lobby");
        var population = new dev.vegui.vnp.api.PlayerPopulation(2, Map.of("lobby", 2), Map.of(context.playerId(), "lobby"), Map.of(context.connectionId(), "lobby"), true);
        var timeline = new dev.vegui.vnp.api.CaptureTimeline(true, List.of(new dev.vegui.vnp.api.CaptureTimeline.Sample(System.currentTimeMillis(), 1000, 1000, "150", "0", true,
                List.of(new dev.vegui.vnp.api.CaptureTimeline.Delta(context, "150", "0")), population)));
        var modern = new PacketCaptureReport(3, UUID.randomUUID().toString(), "RUNNING", System.currentTimeMillis(), null, 1000, "Checkpoint", "150", "0", "150", "1", "0", "0", "0", List.of(),
                dev.vegui.vnp.api.CaptureScope.GLOBAL, true, List.of(), timeline);
        store.save(modern); var recovered = store.load().stream().filter(r -> r.id().equals(modern.id())).findFirst().orElseThrow();
        assertEquals("INTERRUPTED", recovered.state()); assertEquals(timeline, recovered.timeline());
        assertNull(store.load().stream().filter(r -> r.id().equals(old.id())).findFirst().orElseThrow().timeline());
        assertEquals(recovered, new FileReportStore(directory, CaptureOptions.defaults()).load().stream().filter(r -> r.id().equals(modern.id())).findFirst().orElseThrow());
    }
}
