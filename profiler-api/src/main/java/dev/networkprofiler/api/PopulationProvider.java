package dev.networkprofiler.api;

import java.util.Set;

@FunctionalInterface
public interface PopulationProvider {
    PlayerPopulation snapshot(Set<PacketContext> contexts);
    PopulationProvider UNAVAILABLE = contexts -> PlayerPopulation.unavailable();
}
