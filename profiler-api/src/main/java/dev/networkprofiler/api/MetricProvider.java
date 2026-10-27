package dev.networkprofiler.api;

import java.util.Set;

/** Lifecycle of a provider. Implementations own only their instrumentation, never platform connections. */
public interface MetricProvider extends AutoCloseable {
    String id();
    Set<ProfilerCapability> capabilities();
    void start() throws Exception;
    @Override void close();
}
