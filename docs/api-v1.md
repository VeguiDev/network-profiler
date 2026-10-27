# API v1

All timestamps are Unix epoch milliseconds. `intervalMs` is the real measured interval; `uptimeMs` is monotonic elapsed session time. Rates are numeric bytes/second, regardless of dashboard unit selection. Cumulative byte totals are decimal **strings** to preserve JavaScript precision. Missing rates/disabled directions use `null`, not zero. The initial baseline has sequence 0 and no computed rates.

HTTP responses are JSON with `Cache-Control: no-store`. Unknown API routes return 404. Health returns 503 when collection is disabled, failed, stopped or stale (five seconds without a sample). A healthy idle connection legitimately produces zero rates.

## Snapshot: `/api/v1/traffic/current`

```json
{
  "sessionId": "uuid",
  "sequence": 1,
  "timestamp": 1790000000000,
  "intervalMs": 1001.25,
  "uptimeMs": 1001,
  "traffic": {
    "inbound": { "available": true, "totalBytes": "1024", "bytesPerSecond": 1022.721598, "peakBytesPerSecond": 1022.721598 },
    "outbound": { "available": true, "totalBytes": "2048", "bytesPerSecond": 2045.443196, "peakBytesPerSecond": 2045.443196 },
    "combinedTotalBytes": "3072"
  }
}
```

This example is illustrative, not production data. Snapshots represent the last completed sample, not an instantaneous linearizable read of all event-loop counters. Peaks are peaks of sampled average rates, not microburst measurements.

## Status: `/api/v1/status`

`state`: `STARTING | RUNNING | DISABLED | ERROR | STOPPED`.

Fields: `detail`, `platform`, `platformVersion`, profiler `version`, `sessionId`, `uptimeMs`, `capabilities`.

Capabilities are empty when collection is unavailable. Only `TOTAL_TRAFFIC` can be active in this MVP. Clients must check status/health to distinguish failed collection from legitimate zero traffic.

## History: `/api/v1/traffic/history`

```json
{ "sessionId": "uuid", "samples": [] }
```

Samples are ordered by sequence and belong to that session. Reset clears history and changes session ID. No pagination/database exists; server configuration bounds the response (default 900 samples).

## Health: `/api/v1/health`

```json
{ "healthy": true, "state": "RUNNING", "detail": "Client-side transport bytes" }
```

## Live: `/api/v1/live`

Read-only WebSocket. Each JSON message is a snapshot with the additional field `"type": "traffic.snapshot"`. A baseline/current snapshot is sent on connect. Client application messages close the stream with policy violation. No client subscription messages are necessary.

Deduplicate using `(sessionId, sequence)`, never timestamp alone. On reconnect, receive the current session, fetch history, then merge only that session. A stale HTTP response must never replace a newer session. Slow clients can miss intermediate live frames; history recovers retained samples. Switching bytes/bits or pausing does not change server collection.

Security: same-origin by default, explicit Host allowlist, no permissive CORS, Origin validation for browser upgrades. Authentication must be provided externally for remote use.
