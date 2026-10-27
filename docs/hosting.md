# Hosted dashboard deployment

The plugin is an **outgoing client**, not a web server. Deploy Hono and the Astro static build on one HTTPS origin. `https://vnp.vegui.dev` in the examples is a configurable deployment name, not a service supplied by the plugin.

## Node (single process)

Requirements: Node 24.15+, npm, persistent private disk. From the repository:

```sh
npm ci --prefix profiler-web
npm run build --prefix profiler-web
npm ci --prefix profiler-backend
npm run build --prefix profiler-backend
cd profiler-backend
PUBLIC_ORIGIN=https://vnp.vegui.dev HOST=127.0.0.1 PORT=3000 DATA_DIRECTORY=/var/lib/vnp npm start
```

Keep production dependencies used by `npm start` available, including `tsx` (currently a dev dependency); do not use `npm ci --omit=dev` with this source-run command. A service manager should restart the process and set the working directory. `WEB_DIRECTORY` defaults to `../profiler-web/dist`; override with an absolute asset directory if moving the runtime.

Terminate TLS in nginx or your existing ingress. Forward all paths, preserve Host/Origin, and support WebSocket upgrades. The browser-visible origin **must exactly match `PUBLIC_ORIGIN`**. Do not cache `/api`, strip cookies, rewrite the dashboard path prefix or log request bodies/Authorization/Cookie headers. See [nginx example](reverse-proxy.md).

`DATA_DIRECTORY` holds private UUID-named atomic JSON state files, created with restrictive permissions. They contain token hashes, viewer authorization/expiry metadata and bounded temporary statistics. Preserve this directory across process restarts, keep backups private and apply your own backup retention policy. The process loads/sweeps saved shards on startup; expiry is based on stored timestamps, not fresh timers. File storage and socket coordination support **one Node process only**. Do not point multiple replicas at the directory or assume sticky sessions make this highly available. Use a shared transactional coordinator before adding replicas.

Local development may use `PUBLIC_ORIGIN=http://127.0.0.1:3000` and matching plugin relay URLs; insecure non-loopback origins are rejected. Astro dev by itself does not implement the API.

## Cloudflare Workers + Durable Objects

The same business logic runs in a Worker with one `ServerRelay` Durable Object per registered server. Ordinary Worker memory is not the session database. Native hibernatable sockets restore role/boot attachments, durable storage retains authorization/deadlines and bounded samples, and alarms enforce cleanup even without an incoming request. Every operation also checks expiry; delayed/repeated alarms do not grant extra access.

Build Astro and install backend dependencies as above. Set `vars.PUBLIC_ORIGIN` in `profiler-backend/wrangler.jsonc` to the actual deployed HTTPS origin and configure your desired Cloudflare route/custom domain. Keep its `SERVERS` binding, SQLite Durable Object migration and `ASSETS` binding/SPA fallback. Then, when you choose to deploy:

```sh
cd profiler-backend
npm run check:cloudflare
npm run deploy:cloudflare
```

The check is a dry run; deploy changes your Cloudflare account and is not performed by ordinary Gradle builds. Wrangler authentication/account setup belongs to the deployment operator. API paths run the Worker first; other paths serve Astro assets with SPA fallback, including deep report/settings links.

For isolated local testing:

```sh
cd profiler-backend
npm run dev:cloudflare -- --ip 127.0.0.1 --port 8787 --var PUBLIC_ORIGIN:http://127.0.0.1:8787
```

Use `http://127.0.0.1:8787` in both plugin relay URLs for this local test only. Local Wrangler validation does not establish production account limits, global routing or billing behavior. Active sample/heartbeat persistence can incur Durable Object requests/storage costs; hibernatable sockets do not make an actively streaming session free.

## Plugin setup and migration

```yaml
relay:
  enabled: true
  endpoint: "https://vnp.vegui.dev"
  public-web-url: "https://vnp.vegui.dev"
```

Old `http` configuration is ignored; no port 8765 is opened. Existing local `reports/` and `dashboard-settings.json` remain usable. The plugin asynchronously registers only if its separate private `relay-identity.json` is absent. That file contains the stable UUID/token and endpoint; it is never copied to browsers or embedded in links. A corrupt file, endpoint mismatch or rejected credentials is an explicit error, not an invitation to silently create another identity. Relay failure does not stop transport sampling, local captures or local commands.

Run `/vnp dashboard`, open its link and run the exact `/vnp trustSession <code>` displayed by that browser. The admin permission `vnp.admin` restricts these commands. Do not approve codes sent by strangers: approval grants full reads and capture/settings mutations. Links alone grant no metric access; each browser has its own approval. Lost credentials/cookies require a fresh approval rather than recovering authority from the URL.

## Security and retention operations

- Require HTTPS in production; the plugin uses a Bearer header on its outgoing WebSocket, never a query token. Browser credentials are private HttpOnly/SameSite cookies, Secure on HTTPS; writes additionally require exact Origin and viewer-specific CSRF.
- The relay operator is trusted: this is not end-to-end encryption against the hosted backend. Optional access-gateway authentication is defense in depth, not a substitute for per-browser approval. There is no separate web read-only role.
- Add edge rate limits/bot protection for public registration and viewer joins, request/concurrent-connection quotas and storage/CPU budgets. Per-server bounds do not cap the number of anonymous server registrations across the deployment. Registration is intentionally unauthenticated to support first startup.
- Do not record tokens, cookies, complete socket frames, report bodies or samples in access/application logs. Default application messages do not log these secrets; audit your ingress/platform observability separately.
- A viewer/server disconnect starts forty minutes of grace; only both reconnecting cancels it. Heartbeat leases detect unclean loss in up to 45 seconds. Approval codes separately expire after ten minutes. Other viewers do not renew an absent browser.
- Definitive reports and Settings stay on Minecraft. The relay stores at most two boot series, 900 samples each and 2 MiB combined per server. Last-dashboard expiry removes temporary samples; minimal server identity metadata remains for future authentication. Platform backups may outlive application cleanup; configure backup retention accordingly.
- No dashboard expiry stops a capture or deletes local files. If a mutation response is lost, reconcile its state before manually retrying; automatic replay is prohibited.

See [API/protocol](api-v1.md) for exact routing, authentication, limits and boot/transport-stream separation.
