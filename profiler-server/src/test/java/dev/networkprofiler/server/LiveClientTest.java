package dev.networkprofiler.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class LiveClientTest {
    @Test void replacesPendingFramesInsteadOfAccumulatingMessages() {
        var sent = new ArrayList<String>(); var completions = new ArrayList<Consumer<Throwable>>();
        var client = new LiveClient((message, done) -> { sent.add(message); completions.add(done); }, () -> {});
        client.offer("first");
        for (int i = 0; i < 10000; i++) client.offer("pending-" + i);
        assertEquals(List.of("first"), sent);
        completions.getFirst().accept(null);
        assertEquals(List.of("first", "pending-9999"), sent);
    }
    @Test void timesOutAndStopsSendingAfterFailure() {
        int[] closed = {0}, sends = {0};
        var client = new LiveClient((message, done) -> sends[0]++, () -> closed[0]++);
        client.offer("first"); client.checkTimeout(System.nanoTime() + 6_000_000_000L);
        client.offer("later"); client.close(); assertEquals(1, closed[0]); assertEquals(1, sends[0]);
    }
}
