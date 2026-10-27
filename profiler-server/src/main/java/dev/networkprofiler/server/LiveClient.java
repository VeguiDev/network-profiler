package dev.networkprofiler.server;

import java.util.function.Consumer;

/** At most one in-flight frame and one replaceable pending frame. */
final class LiveClient {
    interface Sender { void send(String message, Consumer<Throwable> completion); }
    private final Sender sender;
    private final Runnable disconnect;
    private String pending;
    private boolean sending, closed;
    private long started;
    LiveClient(Sender sender, Runnable disconnect) { this.sender = sender; this.disconnect = disconnect; }
    synchronized void offer(String message) {
        if (closed) return;
        if (sending) { pending = message; return; }
        sending = true; started = System.nanoTime();
        try { sender.send(message, this::complete); } catch (RuntimeException failure) { complete(failure); }
    }
    private synchronized void complete(Throwable failure) {
        if (closed) return;
        sending = false;
        if (failure != null) { close(); return; }
        String next = pending; pending = null;
        if (next != null) offer(next);
    }
    synchronized void checkTimeout(long now) {
        if (sending && now - started > 5_000_000_000L) close();
    }
    synchronized void close() {
        if (closed) return;
        closed = true; pending = null; disconnect.run();
    }
}
