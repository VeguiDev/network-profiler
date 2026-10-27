package dev.vegui.vnp.core;

import dev.vegui.vnp.api.TrafficSnapshot;
import java.util.ArrayList;
import java.util.List;

/** Locks are per sample/history request, never per network event. Retention uses monotonic time. */
public final class SnapshotHistory {
    private final TrafficSnapshot[] samples;
    private final long[] times;
    private final long retentionNanos;
    private int head, size;

    public SnapshotHistory(int capacity, long retentionNanos) {
        if (capacity < 1 || retentionNanos < 1) throw new IllegalArgumentException("Invalid history limits");
        samples = new TrafficSnapshot[capacity]; times = new long[capacity]; this.retentionNanos = retentionNanos;
    }
    public synchronized void add(TrafficSnapshot sample, long now) {
        expire(now);
        if (size == samples.length) { samples[head] = null; head = (head + 1) % samples.length; size--; }
        int next = (head + size) % samples.length;
        samples[next] = sample; times[next] = now; size++;
    }
    public synchronized List<TrafficSnapshot> snapshot(long now) {
        expire(now);
        var result = new ArrayList<TrafficSnapshot>(size);
        for (int i = 0; i < size; i++) result.add(samples[(head + i) % samples.length]);
        return List.copyOf(result);
    }
    public synchronized void clear() { java.util.Arrays.fill(samples, null); head = size = 0; }
    private void expire(long now) {
        while (size > 0 && now - times[head] >= retentionNanos) {
            samples[head] = null; head = (head + 1) % samples.length; size--;
        }
    }
}
