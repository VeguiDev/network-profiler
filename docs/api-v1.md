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
  "players": 12,
  "traffic": {
    "inbound": { "available": true, "totalBytes": "1024", "bytesPerSecond": 1022.721598, "peakBytesPerSecond": 1022.721598 },
    "outbound": { "available": true, "totalBytes": "2048", "bytesPerSecond": 2045.443196, "peakBytesPerSecond": 2045.443196 },
    "combinedTotalBytes": "3072"
  }
}
```

This example is illustrative, not production data. Snapshots represent the last completed sample, not an instantaneous linearizable read of all event-loop counters. Peaks are peaks of sampled average rates, not microburst measurements.

`players` is the real global proxy player count sampled alongside traffic, nullable before measurement or on provider failure. It is not a TCP connection count. HTTP history and WebSocket frames include it without a separate stream.

## Status: `/api/v1/status`

`state`: `STARTING | RUNNING | DISABLED | ERROR | STOPPED`.

Fields: `detail`, `platform`, `platformVersion`, profiler `version`, `sessionId`, `uptimeMs`, `capabilities`.

`TOTAL_TRAFFIC` requires working transport collection. `PACKET_TRAFFIC` means the external PacketEvents bridge is available for on-demand capture, not that a capture is always running. Clients must check status/health and capture availability to distinguish failed collection from legitimate zero traffic.

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

## Packet captures

- `GET /api/v1/captures`: `{ "available": true, "detail": "Ready", "csrfToken": "…", "maxDurationSeconds": 900, "reports": [] }`. Report summaries omit packet/attribution rows and history (`packets: [], attributions: [], timeline: null`); newest first. Availability is false during loading, when disabled or when supported external PacketEvents is missing. Saved reports can still be read without PacketEvents.
- `GET /api/v1/captures/{id}`: full live/final report, 404 if unknown or expired. Poll active reports once per second; final reports are immutable. No additional WebSocket is created per report/component.
- `POST /api/v1/captures`: JSON `{}` uses configured max duration/global scope, or `{ "durationSeconds": 60, "scope": { "type": "SERVER", "id": "lobby" } }`; returns 201. Scope types: `GLOBAL` (null/omitted id), `SERVER` (known server name), `PLAYER` (tracked online UUID), `CONNECTION` (active physical UUID). One shared capture only, including command-triggered sessions. Scope matching happens at collection time. Invalid/unknown options return 400, overlapping/finalizing captures 409, unavailable analysis 503.
- `POST /api/v1/captures/{id}/stop`: JSON `{}`, returns the finalized report after asynchronous persistence. A matching repeated stop is idempotent while the report is retained. A mismatched ID never stops a different capture (409).

Both POSTs require `Content-Type: application/json`, an allowed **Origin**, and `X-MNP-CSRF` matching the token from the index. Missing/foreign origin or wrong token returns 403. These are CSRF safeguards, not an authentication mechanism; protect every route with reverse-proxy authentication for remote access.

Report schema (illustrative values):

```json
{
  "schemaVersion": 3,
  "id": "uuid",
  "state": "COMPLETED",
  "startedAt": 1790000000000,
  "endedAt": 1790000001000,
  "durationMs": 1000,
  "detail": "Stopped by administrator",
  "inboundBytes": "150",
  "outboundBytes": "100",
  "totalBytes": "250",
  "packetCount": "3",
  "cancelledPackets": "0",
  "modifiedPackets": "0",
  "observationErrors": "0",
  "scope": { "type": "GLOBAL", "id": null },
  "attributionComplete": false,
  "attributions": [],
  "timeline": {
    "complete": false,
    "samples": [{
      "timestamp": 1790000001000, "elapsedMs": 1000, "intervalMs": 1000,
      "inboundBytes": "150", "outboundBytes": "100", "complete": false,
      "dimensions": [],
      "population": { "global": 12, "servers": { "lobby": 12 }, "players": {}, "connections": {}, "complete": true }
    }]
  },
  "packets": [
    { "name": "PING", "protocolState": "STATUS", "direction": "INBOUND", "totalBytes": "150", "packetCount": "2", "cancelledPackets": "0", "modifiedPackets": "0" },
    { "name": "PONG", "protocolState": "STATUS", "direction": "OUTBOUND", "totalBytes": "100", "packetCount": "1", "cancelledPackets": "0", "modifiedPackets": "0" }
  ]
}
```

States: `RUNNING`, `FINALIZING`, `COMPLETED`, `INTERRUPTED`, `SAVE_FAILED`. During finalization counts are observed values, not fabricated zeros. Live snapshots are weakly consistent across counters; final reports freeze observers before reading counters. Duration is monotonic elapsed time and stops when stop is requested, not after disk IO. Final reports remain in memory if saving fails, with `SAVE_FAILED` explicitly set.

All byte/count values are decimal strings. Packet identity is `(protocolState, direction, name)`, not a numeric protocol ID shared across versions/states. `totalBytes` includes the encoded packet ID + payload observed at PacketEvents MONITOR, before compression/encryption and possible wrapper re-encoding; it excludes framing and PROXY headers. OUT is a protocol observation, **not** a successful transport write. Cancelled-at-observation packets increment `cancelledPackets`, but are excluded from `packetCount` and byte totals. `modifiedPackets` means a wrapper is pending re-encoding at observation, not proof of changed content. Unknown types/unsupported event buffers increment `observationErrors`. Later/same-priority listeners and encoding can still change or cancel a packet.

The UI computes percentage by bytes: `rowBytes / (inboundBytes + outboundBytes)` for BOTH, otherwise by the selected directional total. Mean size is `rowBytes / rowPacketCount`. Search never changes the denominator. Sorting/search/tab changes do not alter server collection.

Each schema-2 attribution is `{ "context": { "connectionId": "uuid", "playerId": "uuid-or-null", "playerName": "name-or-null", "serverId": "lobby" }, "packets": [/* same row contract */] }`. Server identity is the association captured at observation, never retroactively changed after transfer; it is not backend-origin proof. `~unassigned` and `~transition` are reserved labels. Physical connection IDs differ after reconnect, player UUIDs do not. A PLAYER capture follows reconnects; a CONNECTION capture completes on disconnect. Reports expose scope and keep global totals when their bounded attribution capacity is exceeded (`attributionComplete: false`). Schema-1 reports remain readable with GLOBAL scope and no attribution, not fabricated historical dimensions.

Reports are local schema-versioned JSON files, atomic replacement where supported, bounded by count/age and 32 MiB per file. Five-second partial checkpoints recover as explicitly incomplete `INTERRUPTED` reports after an unclean restart; they never resume automatically and exclude traffic after the last checkpoint. Normal shutdown saves the observed interval as `INTERRUPTED`.

### Schema-3 timeline

`timeline` is null for schema-1/2 reports, not a reconstructed history. Samples include a zero-duration baseline, approximately one-second intervals and the final partial interval. `elapsedMs` is monotonic elapsed capture time; `intervalMs` is the **actual** interval. Delays are not backfilled. Sample bytes are interval deltas, not cumulative totals. Each sparse `dimensions` entry is `{ "context": /* attribution context */, "inboundBytes": "…", "outboundBytes": "…" }`, aggregated across packet types.

`sample.complete` covers dimensional traffic coverage: an absent context means measured zero only when true. When false, filtered rates are unknown, not zero; the sample's global byte delta can still be used. `timeline.complete` also reflects population availability and retention limits. Default bounds are 3,602 samples and 10,000 sparse dimension/population-map entries; both are configurable and validated. Exceeding them retains cumulative counters, flags incomplete history and prevents unbounded retention. Reaching the sample limit stops appending points. Do not extrapolate missing intervals.

Population contains nullable real `global` count, `servers` mapping server names to real counts (explicit zero for empty registered servers), and requested `players`/`connections` mapping identity to current server at that sample. Absent identities are unavailable/unassigned/disconnected. `population: null` means **unchanged from the preceding explicit population**, not unavailable; clients must carry it forward, including the baseline. An explicit unavailable population has null global count, empty maps and `complete: false`. Repeated equal maps consume no additional map entries. Exhausting the map budget keeps the observed global count but omits local maps and marks population incomplete.

Graph selection combines server/player/connection filters; packet search does not affect graphs. Current is the latest covered interval, mean is selected cumulative bytes / actual capture duration, min/max cover all valid measured intervals including idle zeros. Min/max are unavailable when traffic coverage is incomplete; graph-window selection does not change capture-wide statistics. Completed reports retain their last interval rather than pretending to measure current live traffic. Player populations are point-in-time counts on an independent axis; PLAYER/CONNECTION scopes follow the sampled current server, while server filters select that server's population.

## Shared dashboard settings

- `GET /api/v1/settings`: `{ "bandwidth": { "inboundMbps": null, "outboundMbps": null }, "csrfToken": "…", "persistent": true }`.
- `POST /api/v1/settings`: JSON `{ "inboundMbps": 100, "outboundMbps": 50 }`; returns the same response shape after persistence. Both fields are required, each null (disabled) or a finite decimal number greater than zero and at most 1,000,000. Unknown keys/invalid values return 400. IO failure returns an error and preserves the previous in-memory value; no false success.

POST requires JSON, allowed Origin and `X-MNP-CSRF` from settings/capture index; no additional authentication is provided. Settings work without PacketEvents. The Velocity plugin atomically persists `dashboard-settings.json` in its data folder. Values represent decimal Mbps: `bytesPerSecond = Mbps * 1,000,000 / 8`. They are shared, optional **visual references only**, never network rate limits. Chart domains include exceeded values rather than clipping them. UI clients refresh shared settings every 30 seconds; the saving client updates immediately. Protect these mutations with reverse-proxy authentication as well. SPA `/settings` supports direct refresh.

## Lightweight protocol statistics

- `GET /api/v1/servers` and `/api/v1/servers/{name}`
- `GET /api/v1/connections` and `/api/v1/connections/{uuid}`
- `GET /api/v1/players/{uuid}` (retained physical connections belonging to this UUID)

Published by an independent scheduler every 30 seconds by default. Bytes are counted continuously, not once per publication; no permanent packet-type classification or persisted background reports. These are protocol bytes with the same observation/cancellation semantics as captures, **not** Overview's transport bytes.

Response fields: `available`, `detail`, `timestamp`, `intervalMs` (actual elapsed time), `ready`, `complete`, `observationErrors`, `untrackedBytes`, `intervalInboundBytes`, `intervalOutboundBytes`, `total`, `page`, `pageSize`, `rows`.

Each row: `id`, nullable `playerId`/`playerName`, `serverId`, `active`, `connections` (active count), cumulative `inboundBytes`/`outboundBytes`/`totalBytes`, `intervalInboundBytes`/`intervalOutboundBytes`, numeric average `inboundBytesPerSecond`/`outboundBytesPerSecond`. Byte/error counts are decimal strings. Server totals start with the statistics service; connection totals start when tracked and can span earlier server associations. Rows carry the last published identity/state, so successful transfers and disconnects may take one publication interval to appear. Disconnected rows expire; server totals remain bounded/in-memory until restart.

Optional parameters: `q` (up to 256 characters), `page` (zero-based, max 100000), `pageSize` (1–200, default 50), `serverId`, `playerId`. Sort by combined interval bytes descending, then ID. Full collection `intervalInboundBytes`/`intervalOutboundBytes` denominators are calculated **before search, filtering and pagination**; `total` counts filtered rows. BOTH uses their sum; IN/OUT use their respective denominator. No normalization to filtered results. Unknown/expired detail IDs return 404 after the first publication; while collecting the first interval the response has `ready: false` and empty rows. Missing/disabled PacketEvents gives `available: false`, not fabricated healthy zeros. Already-published values can remain stale with that flag. `complete: false` and error/untracked counters signal lost coverage or configured limits; percentages cannot claim to represent omitted traffic.

No IP addresses are included. Protect these identity-bearing read endpoints with the same reverse-proxy authentication as reports and the dashboard. SPA routes `/servers`, `/servers/{name}`, `/connections`, `/connections/{uuid}`, `/players/{uuid}` support direct refresh.
