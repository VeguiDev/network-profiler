# Minecraft Network Profiler

A modular, open-source network profiler for Minecraft. This first release measures **real client ↔ proxy transport traffic** on Velocity and serves an embedded live dashboard. No database, packet capture, or synthetic production data.

## Compatibility

| Component | Requirement |
| --- | --- |
| Velocity plugin | **Velocity 4.3.0**, tested against build 44; **Java 25+** |
| Shared Java modules | Java 21+ |
| Build | JDK 21 and JDK 25 installed; Gradle Wrapper included |
| Frontend development/build | Node.js 24.15+ and npm |
| PacketEvents | Optional external plugin, integration targets 2.14.0 |

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

The plugin creates `plugins/networkprofiler/config.yml`. Changes require a proxy restart. `/networkprofiler status` reports collection state. `/networkprofiler reset` starts a new counter/history session. Both require `networkprofiler.admin` (or the Velocity console).

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
traffic:
  enabled: true
  track-inbound: true
  track-outbound: true
  channel-provider: "native"
```

- The MVP accepts a **1,000 ms** sampling interval. Actual elapsed time, not an assumed second, determines the reported rate.
- History accepts 1–86,400 seconds; default 900. Both sample capacity and elapsed retention are bounded. No backfilled/fabricated samples after scheduler delays.
- Unknown keys, duplicate YAML keys, unsafe YAML tags, invalid types and out-of-range values are rejected.
- Disabled directions have `available: false` and null values, not misleading zero values. Combined totals are unavailable when either direction is disabled.
- `http.enabled: false` leaves collection active without a dashboard/API.
- An incompatible integration is explicitly unhealthy; inspect console logs and `/api/v1/status` when available.

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

Overview includes current IN/OUT, cumulative totals, peaks, combined transferred bytes, uptime and a real-time Recharts graph. Controls provide 1/5/15-minute windows, bytes/bits, individual series visibility, dark/light theme and visualization pause.

Pause freezes presentation only; collection, history and the single shared WebSocket continue. Reconnection uses exponential backoff with jitter, recovers HTTP history and merges by session/sequence. Disconnected values are marked stale. Connections, Packets, Servers and Settings are disabled future navigation, not fake screens.

## API

| Route | Purpose |
| --- | --- |
| `GET /api/v1/status` | State, detail, platform/version, session, uptime and active capabilities |
| `GET /api/v1/traffic/current` | Latest immutable snapshot |
| `GET /api/v1/traffic/history` | Bounded current-session history |
| `GET /api/v1/health` | HTTP 200 healthy, 503 unavailable/disabled/stale |
| `WS /api/v1/live` | Initial snapshot, then new snapshots once per second |

See [the versioned contract](docs/api-v1.md). No administrative HTTP endpoints exist. This service has **no built-in authentication**.

## Security / reverse proxy

Keep HTTP bound to loopback. Do not expose the metrics port directly. For remote access, authenticate **all** dashboard/API/WebSocket routes at a TLS reverse proxy. See [the nginx example](docs/reverse-proxy.md).

Host allowlisting mitigates browser DNS rebinding. Browser WebSocket origins must match the requested authority or an explicit allowed origin. Origin validation is not authentication: non-browser clients can omit Origin. CORS is not opened by default. Client counts, incoming message sizes, idle time and outbound queues are bounded.

## PacketEvents and future analysis

The default `native` provider needs no PacketEvents. To use `channel-provider: "packetevents"`, install the external Velocity PacketEvents 2.14.0 plugin and restart the proxy. Exactly one discovery provider is selected. Both providers use the **same raw-byte Netty counter**.

PacketEvents is not bundled, globally reconfigured, terminated or deshaded by this profiler. Its events are not summed as transport bytes. The bridge currently uses connection lifecycle only; packet types, directions, custom-payload channels and user/session metadata are reserved for a later phase. PacketEvents does not automatically identify the sending plugin or instrument every proxy backend.

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

Each WebSocket client has at most one active frame and one replaceable pending frame. Slow sends time out. No overhead percentage is promised without benchmarks.

See [instrumentation and limitations](docs/instrumentation.md). Platform capabilities reserve connection/player/backend/packet/plugin metrics, but only `TOTAL_TRAFFIC` is enabled in this release. BungeeCord, Paper, Folia/Pinac, Fabric and NeoForge are architectural targets, **not supported integrations yet**.

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
```

For the actual shaded JAR against the pinned Velocity distribution:

```sh
python3 tools/smoke_velocity.py \
  --velocity-jar /path/to/velocity-4.3.0-44.jar \
  --plugin profiler-platforms/velocity/build/libs/minecraft-network-profiler-velocity-0.1.0.jar
```

The script verifies the Velocity checksum, starts an isolated loopback proxy under `/tmp/opencode`, exchanges real Minecraft status/ping packets, compares exact IN/OUT byte totals and checks clean shutdown. Add `--proxy-protocol` or `--packetevents /path/to/packetevents-velocity-2.14.0.jar` to exercise those paths. It does not accept a Minecraft server EULA or start a backend.

Login, movement, encryption/compression and chunk transfer against a full backend require the additional manual scenarios in [the validation checklist](docs/validation.md). A successful status smoke test is not a substitute for those scenarios.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). Frontend component patterns derived from shadcn/ui retain their MIT notice in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Dependencies retain their own licenses.
