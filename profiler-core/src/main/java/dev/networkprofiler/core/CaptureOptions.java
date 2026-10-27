package dev.networkprofiler.core;

public record CaptureOptions(int maxReports, int retentionDays, int maxDurationSeconds, int maxAttributionRows,
                             int maxTimelineEntries, int maxTimelineSamples) {
    public CaptureOptions {
        if (maxReports < 1 || maxReports > 1000 || retentionDays < 1 || retentionDays > 365
                || maxDurationSeconds < 1 || maxDurationSeconds > 86400 || maxAttributionRows < 1 || maxAttributionRows > 20000
                || maxTimelineEntries < 1 || maxTimelineEntries > 20000 || maxTimelineSamples < 2 || maxTimelineSamples > 86402)
            throw new IllegalArgumentException("Capture limits: reports 1..1000, retention 1..365 days, duration 1..86400 seconds");
    }
    public CaptureOptions(int maxReports, int retentionDays, int maxDurationSeconds, int maxAttributionRows) {
        this(maxReports, retentionDays, maxDurationSeconds, maxAttributionRows, 10000, 3602);
    }
    public CaptureOptions(int maxReports, int retentionDays, int maxDurationSeconds) { this(maxReports, retentionDays, maxDurationSeconds, 10000); }
    public static CaptureOptions defaults() { return new CaptureOptions(50, 7, 900); }
}
