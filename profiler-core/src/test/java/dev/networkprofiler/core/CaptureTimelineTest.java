package dev.networkprofiler.core;

import dev.networkprofiler.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CaptureTimelineTest {
    private final PacketContext lobby = new PacketContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Alice", "lobby");
    private final PacketContext survival = new PacketContext(lobby.connectionId(), lobby.playerId(), "Alice", "survival");
    @Test void samplesActualTimeIdleZerosTransferAndFinalPartialIntervalWithoutMovingHistory() throws Exception {
        var clock = new TrafficProfilerTest.Clock(); var provider = new PacketCapturesTest.Provider();
        var population = new AtomicReference<>(new PlayerPopulation(2, Map.of("lobby", 2, "survival", 0), Map.of(lobby.playerId(), "lobby"), Map.of(lobby.connectionId(), "lobby"), true));
        var store = new PacketCapturesTest.MemoryStore();
        try (var captures = new PacketCaptures(provider, store, CaptureOptions.defaults(), clock, ignored -> population.get())) {
            PacketCapturesTest.ready(captures); var start = captures.start();
            provider.recorder.record(lobby, 0, 300, false, false); clock.advance(1500); captures.sample();
            var first = captures.current().timeline().samples().getLast();
            assertEquals(1500d, first.intervalMs()); assertEquals("300", first.inboundBytes());
            assertEquals(lobby, first.dimensions().getFirst().context()); assertEquals("lobby", first.population().players().get(lobby.playerId()));
            clock.advance(1000); captures.sample(); var idle = captures.current().timeline().samples().getLast();
            assertEquals("0", idle.inboundBytes()); assertTrue(idle.dimensions().isEmpty()); assertNull(idle.population(), "Unchanged membership is stored once");
            population.set(new PlayerPopulation(2, Map.of("lobby", 1, "survival", 1), Map.of(lobby.playerId(), "survival"), Map.of(lobby.connectionId(), "survival"), true));
            provider.recorder.record(survival, 1, 100, false, false); clock.advance(250);
            var report = captures.stop(start.id()).get(); var last = report.timeline().samples().getLast();
            assertEquals(250d, last.intervalMs()); assertEquals("100", last.outboundBytes()); assertEquals(2750, report.durationMs());
            assertEquals("survival", last.population().players().get(lobby.playerId()));
            assertEquals("lobby", first.population().players().get(lobby.playerId())); assertTrue(report.timeline().complete());
            assertEquals(report, store.saved.getFirst()); assertThrows(UnsupportedOperationException.class, () -> report.timeline().samples().clear());
        }
    }
    @Test void boundedDimensionsAndSamplesRemainExplicitGapsWhileCountersContinue() throws Exception {
        var clock = new TrafficProfilerTest.Clock(); var provider = new PacketCapturesTest.Provider();
        var options = new CaptureOptions(10, 7, 60, 100, 1, 3);
        try (var captures = new PacketCaptures(provider, new PacketCapturesTest.MemoryStore(), options, clock)) {
            PacketCapturesTest.ready(captures); var start = captures.start();
            provider.recorder.record(lobby, 0, 10, false, false); clock.advance(1000); captures.sample();
            provider.recorder.record(survival, 1, 20, false, false); clock.advance(1000); captures.sample();
            var limited = captures.current().timeline().samples().getLast();
            assertFalse(limited.complete()); assertTrue(limited.dimensions().isEmpty()); assertEquals("20", limited.outboundBytes());
            provider.recorder.record(survival, 1, 30, false, false); clock.advance(1000);
            var report = captures.stop(start.id()).get();
            assertFalse(report.timeline().complete()); assertEquals(3, report.timeline().samples().size()); assertEquals("60", report.totalBytes());
        }
    }
    @Test void populationBudgetDoesNotInventEmptyServersOrCountSocketsAsPlayers() throws Exception {
        var clock = new TrafficProfilerTest.Clock(); var provider = new PacketCapturesTest.Provider();
        try (var captures = new PacketCaptures(provider, new PacketCapturesTest.MemoryStore(), new CaptureOptions(10, 7, 60, 100, 1, 10), clock,
                ignored -> new PlayerPopulation(10, Map.of("lobby", 8, "survival", 2), Map.of(), Map.of(), true))) {
            PacketCapturesTest.ready(captures); var start = captures.start(); clock.advance(1000); captures.sample();
            var p = captures.current().timeline().samples().getLast().population();
            assertEquals(10, p.global()); assertFalse(p.complete()); assertTrue(p.servers().isEmpty());
            captures.stop(start.id()).get();
        }
    }
}
