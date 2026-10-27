package dev.networkprofiler.core;

import dev.networkprofiler.api.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PacketStatisticsTest {
    @Test void averagesActualThirtySecondIntervalsWithoutMovingPreviousServerBytes() {
        var clock = new TrafficProfilerTest.Clock();
        try (var stats = new PacketStatistics(StatisticsOptions.defaults(), clock)) {
            var connection = stats.open(); var early = connection.association();
            early.record(TrafficDirection.INBOUND, 30, false);
            connection.associate("player", "Alice", "lobby"); var lobby = connection.association();
            lobby.record(TrafficDirection.INBOUND, 300, false);
            lobby.record(TrafficDirection.OUTBOUND, 900, false);
            connection.associate("player", "Alice", "survival");
            connection.association().record(TrafficDirection.OUTBOUND, 600, false);
            // A captured old association never gets attributed to the new server.
            lobby.record(TrafficDirection.OUTBOUND, 30, false);
            clock.advance(30000); stats.sample(); var snapshot = stats.current();
            assertTrue(snapshot.ready()); assertTrue(snapshot.complete()); assertEquals(30000, snapshot.intervalMs());
            var lobbyRow = snapshot.servers().stream().filter(r -> r.id().equals("lobby")).findFirst().orElseThrow();
            assertEquals("300", lobbyRow.inboundBytes()); assertEquals("930", lobbyRow.outboundBytes());
            assertEquals(10d, lobbyRow.inboundBytesPerSecond()); assertEquals(31d, lobbyRow.outboundBytesPerSecond());
            assertEquals(0, lobbyRow.connections());
            var c = snapshot.connections().getFirst(); assertEquals("survival", c.serverId()); assertEquals("1860", c.totalBytes());
            clock.advance(45000); stats.sample();
            assertEquals("0", stats.current().connections().getFirst().intervalInboundBytes());
            assertEquals(45000, stats.current().intervalMs());
        }
    }
    @Test void cancellationAndCardinalityLimitsAreExplicitAndRetainedMemoryIsBounded() {
        var clock = new TrafficProfilerTest.Clock();
        try (var stats = new PacketStatistics(new StatisticsOptions(true, 30, 1, 1, 0), clock)) {
            var c = stats.open(); assertNull(stats.open());
            c.associate(null, null, "one"); c.association().record(TrafficDirection.INBOUND, 500, true);
            c.associate(null, null, "two"); c.association().record(TrafficDirection.INBOUND, 200, false);
            c.disconnect(); clock.advance(30000); stats.sample();
            assertFalse(stats.current().complete()); assertEquals("200", stats.current().untrackedBytes());
            assertEquals("200", stats.current().connections().getFirst().totalBytes());
            assertNotNull(stats.open(), "Expired disconnected connections release registry capacity after their final sample");
        }
    }
    @Test void concurrentPerConnectionCountersDoNotLoseBytesOrCountCancelledPackets() throws Exception {
        var clock = new TrafficProfilerTest.Clock();
        try (var stats = new PacketStatistics(StatisticsOptions.defaults(), clock); var pool = Executors.newFixedThreadPool(8)) {
            var c = stats.open(); c.associate("player", "Alice", "lobby"); var association = c.association();
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int n = 0; n < 8; n++) jobs.add(pool.submit(() -> { for (int i = 0; i < 25000; i++) association.record(TrafficDirection.INBOUND, 10, false); }));
            for (var job : jobs) job.get(); clock.advance(30000); stats.sample();
            assertEquals("2000000", stats.current().connections().getFirst().inboundBytes());
            assertEquals("2000000", stats.current().servers().stream().filter(r -> r.id().equals("lobby")).findFirst().orElseThrow().inboundBytes());
        }
    }
    @Test void disabledStatisticsDoNotCountAndClosedStatisticsRejectNewConnections() {
        var stats = new PacketStatistics(new StatisticsOptions(false, 30, 10, 10, 900));
        var c = stats.open(); c.association().record(TrafficDirection.OUTBOUND, 100, false);
        assertFalse(stats.enabled()); assertFalse(stats.current().ready()); stats.close(); stats.close();
        assertNull(stats.open()); assertThrows(IllegalStateException.class, stats::start);
    }
}
