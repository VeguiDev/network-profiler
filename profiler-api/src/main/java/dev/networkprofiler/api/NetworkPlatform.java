package dev.networkprofiler.api;

import java.util.Set;

public interface NetworkPlatform {
    String name();
    String version();
    Set<ProfilerCapability> capabilities();
}
