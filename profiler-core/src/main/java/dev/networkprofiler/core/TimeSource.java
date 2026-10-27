package dev.networkprofiler.core;

public interface TimeSource {
    long nanoTime();
    long epochMillis();
    TimeSource SYSTEM = new TimeSource() {
        public long nanoTime() { return System.nanoTime(); }
        public long epochMillis() { return System.currentTimeMillis(); }
    };
}
