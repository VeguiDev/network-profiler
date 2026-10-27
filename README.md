# Minecraft Network Profiler

A modular, open-source network profiler for Minecraft. Measure **real client ↔ proxy transport traffic**, inspect lightweight server/connection statistics, then run scoped on-demand packet analysis with a live view and saved reports. No database, packet-content capture or synthetic production data.

## Compatibility

| Component | Requirement |
| --- | --- |
| Velocity plugin | **Velocity 4.3.0**, tested against build 44; **Java 25+** |
| Shared Java modules | Java 21+ |
| Build | JDK 21 and JDK 25 installed; Gradle Wrapper included |
| Frontend development/build | Node.js 24.15+ and npm |
| PacketEvents | External **2.14.0 required for protocol statistics and packet analysis**; optional for transport totals |

Velocity 4.x itself requires Java 25. A Java 21-compatible core does **not** make the latest Velocity run on Java 21. Other Velocity releases are deliberately rejected until their pipeline integration is validated. Installation is at proxy startup; plugin hot loading is unsupported.

## Build

```sh
./gradlew build
```

On Windows use `gradlew.bat build`. Gradle automatically detects common JDK installations. For custom locations, set `org.gradle.java.installations.paths` in your **local** Gradle configuration. The build runs Java/frontend tests, type-checks and compiles the frontend, bundles its static assets and produces:

```text
profiler-platforms/velocity/build/libs/minecraft-network-profiler-velocity-0.1.0.jar
```

Install **this JAR**, not the `-thin` or `-sources` artifacts. The shared modules target Java 21 and the Velocity adapter targets Java 25. Dependencies are pinned in `gradle/libs.versions.toml` and `profiler-web/package-lock.json`. The wrapper validates its distribution checksum.

## Install and open the dashboard

1. Stop Velocity and copy the plugin JAR into its `plugins/` directory.
2. Start Velocity with Java 25 or later.
3. Open **http://127.0.0.1:8765/** on the proxy host.
4. Generate Minecraft traffic: the Overview updates once per second.

For access from your own workstation without exposing HTTP:

```sh
ssh -L 8765:127.0.0.1:8765 user@proxy-host
```

The plugin creates `plugins/networkprofiler/config.yml`. Changes require a proxy restart. Commands require `networkprofiler.admin` (or the Velocity console). `/networkprofiler` and `/mnp` are aliases:

| Command | Action |
| --- | --- |
| `/mnp start [seconds]` | Start the single shared packet capture; defaults to the configured maximum duration |
| `/mnp start [seconds] --server <name>` | Capture only packets associated with this server |
| `/mnp start [seconds] --player <name\|uuid>` | Follow the player UUID across transfers/reconnections |
| `/mnp start [seconds] --connection <uuid>` | Capture one physical connection; stops on disconnect |
| `/mnp stop` | Finalize and save that capture; print interval protocol totals and a report link |
| `/mnp status` | Transport state and packet capture state |
| `/mnp reset` | Reset transport counters/history only; an active packet capture is independent |

## Configuration

```yaml
profiler:
  enabled: true
  sampling-interval-ms: 1000
  history-duration-seconds: 900
http:
  enabled: true
  host: "127.0.0.1"
  port: 8765
  allowed-hosts: ["127.0.0.1", "localhost", "[::1]"]
  allowed-origins: []
  max-websocket-clients: 16
  public-base-url: ""
traffic:
  enabled: true
  track-inbound: true
  track-outbound: true
  channel-provider: "native"
captures:
  enabled: true
  max-duration-seconds: 900
  max-reports: 50
  retention-days: 7
  max-attribution-rows: 10000
  max-timeline-entries: 10000
  max-timeline-samples: 3602
statistics:
  enabled: true
  interval-seconds: 30
  max-connections: 10000
  max-servers: 1000
  disconnected-retention-seconds: 900
```

- The MVP accepts a **1,000 ms** sampling interval. Actual elapsed time, not an assumed second, determines the reported rate.
- History accepts 1–86,400 seconds; default 900. Both sample capacity and elapsed retention are bounded. No backfilled/fabricated samples after scheduler delays.
- Unknown keys, duplicate YAML keys, unsafe YAML tags, invalid types and out-of-range values are rejected.
- Disabled directions have `available: false` and null values, not misleading zero values. Combined totals are unavailable when either direction is disabled.
- `http.enabled: false` leaves collection active without a dashboard/API.
- An incompatible integration is explicitly unhealthy; inspect console logs and `/api/v1/status` when available.
- Capture duration accepts 1–86,400 seconds; saved report count 1–1,000; retention 1–365 days. Reports live in `plugins/networkprofiler/reports/` as versioned JSON, at most 32 MiB each. Count/age limits also apply to disk files and active checkpoints. Attribution is capped at 1–20,000 dimension/type rows; hitting the cap preserves capture totals but explicitly marks attribution incomplete.
- Lightweight statistics count protocol bytes continuously and publish interval averages every 30 seconds by default (configurable 1–300). Limits: 1–100,000 active/recent connections, 1–10,000 server identities, and 0–86,400 seconds of disconnected retention. Closed connections are published one final time before pruning. Limits or observation failures mark coverage incomplete rather than inventing data. These statistics are in memory, not automatic persisted reports.
- Capture timelines sample every second, plus the final partial interval. They retain at most `max-timeline-samples` points (2–86,402) and `max-timeline-entries` sparse dimension/population entries (1–20,000). Reaching a limit marks history incomplete without stopping cumulative counters. Missing coverage is shown as unknown/gaps, never fabricated zero traffic. Unchanged population maps are stored once and carried forward.
- `http.public-base-url`, for example `https://metrics.example.com`, creates clickable in-game report links. It must be an HTTP(S) origin without credentials, query or subpath. Empty means a relative dashboard path is printed, **not a misleading remotely accessible localhost link**. This option neither exposes the listener nor configures authentication.

## What IN and OUT mean

**Scope:** only accepted Minecraft **client ↔ Velocity** TCP connections. Backend connections and this plugin's HTTP/WebSocket traffic are not counted. Status/ping, pre-login and logged-in connections are included when installed at startup.

The plugin adds one pass-through Netty handler **before Minecraft framing, compression and encryption codecs**. This observes their transport representation, including Minecraft framing and compressed/encrypted bytes. With Velocity PROXY protocol enabled, the incoming PROXY header is also counted because the counter precedes its decoder.

- **IN:** readable bytes observed arriving from the client's transport.
- **OUT:** readable bytes belonging to writes whose Netty promise completes successfully. Each write is counted once, including originally void promises.
- A successful write is **not** proof that the remote client received or acknowledged the data.
- Failed/cancelled writes do not increment OUT. A partial transport write followed by failure can therefore undercount bytes that reached the socket; the plugin does not claim kernel-level byte accounting.
- The counters exclude TCP/IP/link headers, ACKs, retransmissions and backend traffic. They are **not NIC bandwidth counters**.
- Netty read/write events are not Minecraft packet counts. Multiple packets can share a read or write.
- Totals start at the current profiler session; pre-installation traffic cannot be recovered.

Rates use decimal B/s, KB/s, MB/s, GB/s or bps, Kbps, Mbps, Gbps. Totals use binary KiB, MiB and GiB. The dashboard preserves integer totals without JavaScript floating-point rounding.

## Dashboard

Overview includes current IN/OUT, cumulative totals, peaks, combined transferred bytes, uptime and a real-time Recharts graph. Global player counts use the proxy's actual player API, not tracked/pre-login TCP connections, and have an independent graph axis. Controls provide 1/5/15-minute windows, bytes/bits, individual series visibility, dark/light theme and visualization pause.

Pause freezes presentation only; collection, history and the single shared WebSocket continue. Reconnection uses exponential backoff with jitter, recovers HTTP history and merges by session/sequence. Disconnected values are marked stale. Servers and Connections provide lightweight protocol-byte tables, interval rates/shares and clickable Details; player details are linked from connections.

**Settings** persists separate optional IN/OUT bandwidth capacities in `plugins/networkprofiler/dashboard-settings.json`. Empty disables a reference; values are decimal Mbps, correctly converted for bytes/bits display. The chart extends its scale to show references and traffic, including over-capacity values. These settings are **visual only**, never throttling traffic or altering counters. They apply to Overview and report graphs and are shared by dashboard users; other open clients refresh settings within 30 seconds. Saving uses atomic replacement where supported and does not rewrite YAML. All users allowed through the reverse proxy can edit settings; there is no built-in authentication or read-only role.

Install the external Velocity PacketEvents 2.14.0 plugin, then press **Start analysis** or run `/mnp start`. All controls share **one simultaneous capture**, either global or scoped to a server, player UUID or physical connection. Detail pages start a capture for that entity; the Overview starts global analysis. `/reports/{id}` shows live packet totals once per second while recording, then an immutable report after **Stop & save report** or `/mnp stop`. `/reports` lists retained captures; direct links work after refresh and restart. Permanent statistics do not classify packet types; detailed type/dimension counters exist only during a capture.

Reports group observations by associated server, connection and player, with percentages, filters and Details links. A server transfer creates a new association; previously collected bytes never move to the new server. Player captures follow UUID across transfers and reconnects until stopped or timed out; connection captures stop on socket closure. Only one scope is collected, not an unrelated global capture filtered later. Older schema-1 reports remain readable without fabricated attribution.

Live and saved schema-3 reports include a one-second IN/OUT graph and **current/last interval, mean, minimum and maximum** rates for IN, OUT and combined traffic. Mean is cumulative selected bytes divided by the full actual capture duration; min/max include measured idle zeros and describe interval averages, not microbursts. Server/player/connection filters update packet totals, curves and rate statistics together; packet-name search affects the table only. Graph windows do not shorten the statistics' capture-period denominator. Final fractional intervals are retained; unavailable/incomplete history cannot supply a trustworthy min/max. Schema-1/2 reports remain readable with a clear absence of historical graphs.

Report graphs also show global proxy players and, for a server scope/filter, that server's players. Player/connection scopes follow the **current server at each sample**, including historical transfers. Unassigned or disconnected local identities show unavailable, not zero. Counts are point-in-time observations, not interval averages or a claim that players caused the measured traffic. Report pause stops visualization only; basic statistics still publish every 30 seconds independently.

**Server attribution is association, not packet origin:** only the existing client ↔ proxy observation is measured. Proxy-generated packets cannot reliably be distinguished from backend-originated packets here. Unassigned pre-login observations have an explicit bucket. The associated server changes only after a successful `ServerConnectedEvent`, not a requested/failed transfer. Connection totals can span previous servers; use report attribution for historical breakdowns. Published statistics refresh every 30 seconds and can lag connection/transfer state by one interval.

Tabs **BOTH**, **Outbound**, **Inbound** show logical packet type + protocol state + direction, accumulated bytes, percentage, packet count and mean size. Percentages use bytes for the selected direction (combined for BOTH), not packet counts. Search does not change the denominator. Rows are sorted by bytes; the table is paginated and responsive.

These are **protocol observations**, not the transport metric above: PacketEvents MONITOR sees the encoded packet ID and payload, before compression/encryption and possible later re-encoding. OUT observations are not proof of successful transmission. Packets cancelled at observation are counted separately and excluded from byte/count percentages; pending wrapper re-encodes and observation errors are exposed. Same-priority/later changes can occur, so this is not a final-pipeline audit. Full buffers, fields and payload contents are never saved.

Graceful shutdown finalizes an active capture as `INTERRUPTED`. A partial checkpoint is saved at start and every five seconds; after an unclean shutdown it is recovered as explicitly partial `INTERRUPTED` data, never resumed or presented as complete. Data since the last checkpoint is lost. Save failures appear as `SAVE_FAILED` (memory-only) rather than claiming successful persistence.

## API

| Route | Purpose |
| --- | --- |
| `GET /api/v1/status` | State, detail, platform/version, session, uptime and active capabilities |
| `GET /api/v1/traffic/current` | Latest immutable snapshot |
| `GET /api/v1/traffic/history` | Bounded current-session history |
| `GET /api/v1/health` | HTTP 200 healthy, 503 unavailable/disabled/stale |
| `WS /api/v1/live` | Initial snapshot, then new snapshots once per second |
| `GET /api/v1/captures` | Availability, limits, CSRF token and report summaries |
| `GET /api/v1/captures/{id}` | Live capture or retained immutable report |
| `GET /api/v1/servers[/{id}]` | Published server statistics |
| `GET /api/v1/connections[/{id}]` | Active/recent physical connection statistics |
| `GET /api/v1/players/{uuid}` | Retained connections for a player UUID |
| `GET /api/v1/settings` | Shared visual bandwidth settings, persistence flag and CSRF token |
| `POST /api/v1/settings` | Persist independent optional IN/OUT capacities in decimal Mbps |
| `POST /api/v1/captures` | Start a capture; optional JSON `durationSeconds` and `scope` |
| `POST /api/v1/captures/{id}/stop` | Stop/finalize the matching capture |

See [the versioned contract](docs/api-v1.md). Capture actions and settings changes require same-origin/allowlisted Origin, JSON and `X-MNP-CSRF`; this is **not authentication**. This service has **no built-in authentication**.

## Security / reverse proxy

Keep HTTP bound to loopback. Do not expose the metrics port directly. For remote access, authenticate **all** dashboard/API/WebSocket routes at a TLS reverse proxy. See [the nginx example](docs/reverse-proxy.md).

Host allowlisting mitigates browser DNS rebinding. Browser WebSocket origins must match the requested authority or an explicit allowed origin. Non-browser read-only clients may omit Origin; capture/settings mutations must supply it and the CSRF token. These checks are not authentication: an HTTP client can fetch a token. Everyone allowed through your authenticated reverse proxy can operate captures and edit settings; there is no separate web read-only role. CORS is not opened by default. Client counts, incoming message sizes, idle time and outbound queues are bounded.

## PacketEvents integration

The default `native` provider needs no PacketEvents. To use `channel-provider: "packetevents"`, install the external Velocity PacketEvents 2.14.0 plugin and restart the proxy. Exactly one discovery provider is selected. Both providers use the **same raw-byte Netty counter**.

PacketEvents is not bundled, globally reconfigured, terminated or deshaded by this profiler. Its events are not summed as transport bytes. Packet analysis and lightweight statistics work with either transport discovery provider and check our own client-channel marker to exclude backends. The supported observation boundary is validated specifically against PacketEvents 2.14.0 on Velocity 4.3.0; other PacketEvents versions are rejected until validated. Independent backend traffic, sending-plugin attribution and custom-payload channel breakdown are not implemented. No IP addresses are collected in attribution metadata.

## Architecture and performance

```text
profiler-api                 platform-free contracts
    ↑
profiler-core               LongAdder counters, immutable samples, sessions, ring buffer
    ↑                 ↑
profiler-netty         profiler-server → bundled profiler-web
    ↑                 ↑
profiler-platforms/velocity
```

No buffers are copied, retained or inspected for packet content by the counting handler. Per-event collection does not acquire global locks. Sampling happens on a separate scheduler. OUT uses promise completion listeners with size/session only. Shutdown removes our handlers and listeners, stops our schedulers/server and never closes proxy-owned channels or loops.

Each WebSocket client has at most one active frame and one replaceable pending frame. Slow sends time out. A single permanent PacketEvents listener updates lightweight direction counters when statistics are enabled, without packet-type lookup. Active captures additionally use a bounded type catalog, dimension counters and per-thread stop gates, not packet objects. No strings, JSON, disk IO, wrappers or buffer copies are created per observed packet by our listener. First-use dimension/thread bookkeeping and LongAdder contention cells can allocate; first-use dimension registration is synchronized, steady-state counter updates are not globally locked. PacketEvents itself has baseline processing/copying overhead. Checkpoints and final serialization run on a separate worker. No overhead percentage is promised without representative benchmarks.

See [instrumentation and limitations](docs/instrumentation.md). `TOTAL_TRAFFIC` is available for supported transport collection; `PACKET_TRAFFIC` is declared when capture analysis is available; `CONNECTION_TRAFFIC`, `PLAYER_TRAFFIC` and `SERVER_ATTRIBUTION` describe enabled, healthy protocol attribution. `BACKEND_TRAFFIC` is deliberately not declared. BungeeCord, Paper, Folia/Pinac, Fabric and NeoForge are architectural targets, **not supported integrations yet**.

Timeline/population aggregation also runs on the independent capture worker, not per packet. Disk delays can lengthen sampling intervals; rates use actual elapsed time without inventing missed one-second samples. Temporal state and persisted files have explicit bounds; the 32 MiB file limit is not a JVM memory/overhead guarantee.

## Development and tests

Run the installed plugin, then:

```sh
cd profiler-web
npm ci
npm run dev
```

Vite binds to loopback and proxies `/api` HTTP/WebSocket to `127.0.0.1:8765`. Production always uses the embedded server and compiled assets.

```sh
./gradlew check
./gradlew :profiler-netty:test
./gradlew :profiler-core:benchmarkCapture
```

For the actual shaded JAR against the pinned Velocity distribution:

```sh
python3 tools/smoke_velocity.py \
  --velocity-jar /path/to/velocity-4.3.0-44.jar \
  --plugin profiler-platforms/velocity/build/libs/minecraft-network-profiler-velocity-0.1.0.jar
```

The script verifies the Velocity checksum, starts an isolated loopback proxy under `/tmp/opencode`, exchanges real Minecraft status/ping packets, compares exact IN/OUT byte totals and checks clean shutdown. Add `--proxy-protocol` or `--packetevents /path/to/packetevents-velocity-2.14.0.jar` to exercise those paths. Add `--packet-capture` with PacketEvents to validate exact per-type bytes/counts, HTTP-start/command-stop and command-start/HTTP-stop, immutable reports and disk persistence. `--channel-provider native` tests native discovery with external PacketEvents installed. It does not accept a Minecraft server EULA or start a backend.

`tools/smoke_attribution.py` accepts the same three JAR arguments and exercises actual offline login, a successful lobby → survival transfer, scoped exclusion, physical disconnect finalization and player reconnect tracking against two minimal protocol backend peers. It does not start full Minecraft servers or accept an EULA; modern online-mode/gameplay compatibility still requires manual testing.

`benchmarkCapture` compares lightweight statistics alone with statistics + attributed capture, using one/eight threads. It is an indicative warmed core timing/allocation probe, not total PacketEvents/proxy overhead, TPS, UI/disk work or thousands of connections. Profile representative plugin combinations and gameplay workloads before making production performance claims.

Login, movement, encryption/compression and chunk transfer against a full backend require the additional manual scenarios in [the validation checklist](docs/validation.md). A successful status smoke test is not a substitute for those scenarios.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). Frontend component patterns derived from shadcn/ui retain their MIT notice in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Dependencies retain their own licenses.
