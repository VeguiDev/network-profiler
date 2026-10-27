package dev.vegui.vnp.api;

import java.util.Set;

public interface NetworkPlatform {
    String name();
    String version();
    Set<ProfilerCapability> capabilities();
}
