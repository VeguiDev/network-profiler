package dev.vegui.vnp.api;

/** A handle belongs to one session. Async completions must retain that handle, not fetch a new one. */
public interface TrafficCollector {
    void received(long bytes);
    void sent(long bytes);
}
