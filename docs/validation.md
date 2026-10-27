# Validation checklist

## Automated build

Run `./gradlew clean build` with JDK 21, JDK 25 and Node 24.15+. This runs core/concurrency/history/session tests, Netty EmbeddedChannel accounting tests, HTTP/WebSocket integration tests, configuration tests, frontend unit/component tests, TypeScript checking and production packaging.

## Real proxy smoke

`tools/smoke_velocity.py` starts the actual pinned Velocity distribution with the shaded plugin, makes twelve real Minecraft status/ping exchanges and compares the sent/received byte counts exactly against the API counters. It also checks static dashboard resources, history, proxy exit and HTTP port closure. Run native, PROXY protocol, PacketEvents, and PacketEvents + PROXY protocol variants.

The smoke creates isolated files under `/tmp/opencode` and preserves logs for inspection. It never opens public listeners, accepts an EULA or starts a backend. It requires the locally supplied Velocity distribution with the pinned checksum. `--hold-seconds` keeps generating real status traffic for browser inspection before automatic shutdown.

### Recorded validation — 2026-10-27

`./gradlew clean build --no-daemon` passed on Linux with JDK 21/25 and Node 24.21.0: **21 Java tests and 7 frontend tests**, with no failures. The shaded JAR contains the dashboard, has no duplicate archive paths, and does not bundle host Netty, Velocity API or SLF4J classes. The final artifact was tested on Velocity 4.3.0 build 44, including the optional external PacketEvents 2.14.0 plugin:

| Discovery provider | PROXY protocol | Twelve exchanges IN | Twelve exchanges OUT | Result |
| --- | --- | --- | --- | --- |
| Native | Off | 348 B | 2,136 B | Exact match; clean shutdown |
| Native | On | 876 B | 2,136 B | Exact match; clean shutdown |
| PacketEvents | Off | 348 B | 2,136 B | Exact match; clean shutdown |
| PacketEvents | On | 876 B | 2,136 B | Exact match; clean shutdown |

The additional incoming bytes are the actual PROXY headers. There was no backend traffic to include in these tests.

Browser checks against the real embedded dashboard verified a connected stream, growing totals, two chart series, HTTP history, bytes/bits, dark/light, individual series hiding and pause/resume while API sequences continued increasing. At 390 px viewport width, the page had no horizontal overflow. These checks do not establish full gameplay compatibility or performance at scale. Windows/macOS runtime/build validation is not yet recorded.

## Full gameplay acceptance — separate manual requirement

Use a permitted Minecraft backend and an actual client; status/ping cannot establish these results:

- [ ] Login completes with online-mode encryption.
- [ ] Movement and chunk loading increase both totals and produce live rates.
- [ ] Compression enabled/disabled does not remove or duplicate the transport counter.
- [ ] Backend transfers do not add a second copy of client traffic.
- [ ] Disconnect/reconnect cleans up handlers and preserves session totals.
- [ ] Reset changes session/history while pending writes cannot contaminate the new session.
- [ ] Dashboard reconnects after proxy restart and discards the previous session.
- [ ] A slow dashboard cannot cause unlimited pending messages.
- [ ] Shutdown leaves no profiler threads or listening socket.
- [ ] Required plugin combinations preserve the initializer and pipeline position.

Do not mark unexecuted scenarios as passed. No overhead percentage or thousand-connection performance claim is made without representative benchmarks.
