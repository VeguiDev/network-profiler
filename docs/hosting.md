# Hosted dashboard deployment

The plugin is an **outgoing client**, not a web server. Deploy Hono and the Astro static build on one HTTPS origin. Official JARs embed `https://networkprofiler.vegui.dev`. Hosting on another origin requires a custom plugin build; URL overrides are not accepted at Minecraft runtime.

## Node (single process)

Requirements: Node 24.15+, npm, persistent private disk. From the repository:

```sh
npm run install:apps
npm run build
cd profiler-backend
PUBLIC_ORIGIN=https://networkprofiler.vegui.dev HOST=127.0.0.1 PORT=3000 DATA_DIRECTORY=/var/lib/vnp npm start
```

Keep production dependencies used by `npm start` available, including `tsx` (currently a dev dependency); do not use `npm ci --omit=dev` with this source-run command. A service manager should restart the process and set the working directory. `WEB_DIRECTORY` defaults to `../profiler-web/dist`; override with an absolute asset directory if moving the runtime.

Terminate TLS in nginx or your existing ingress. Forward all paths, preserve Host/Origin, and support WebSocket upgrades. The browser-visible origin **must exactly match `PUBLIC_ORIGIN`**. Do not cache `/api`, strip cookies, rewrite the dashboard path prefix or log request bodies/Authorization/Cookie headers. See [nginx example](reverse-proxy.md).

`DATA_DIRECTORY` holds private UUID-named atomic JSON state files, created with restrictive permissions. They contain token hashes, viewer authorization/expiry metadata and bounded temporary statistics. Preserve this directory across process restarts, keep backups private and apply your own backup retention policy. The process loads/sweeps saved shards on startup; expiry is based on stored timestamps, not fresh timers. File storage and socket coordination support **one Node process only**. Do not point multiple replicas at the directory or assume sticky sessions make this highly available. Use a shared transactional coordinator before adding replicas.

Local development may use `PUBLIC_ORIGIN=http://127.0.0.1:3000` and a plugin **built** with `VNP_RELAY_URL=http://127.0.0.1:3000`; insecure non-loopback origins are rejected. Astro dev by itself does not implement the API.

## Cloudflare Workers + Durable Objects

The same business logic runs in a Worker with one `ServerRelay` Durable Object per registered server. Ordinary Worker memory is not the session database. Native hibernatable sockets restore role/boot attachments, durable storage retains authorization/deadlines and bounded samples, and alarms enforce cleanup even without an incoming request. Every operation also checks expiry; delayed/repeated alarms do not grant extra access.

`wrangler.jsonc` at the repository root is configured for the Worker `network-profiler`, account `1320cf26a557b8a80feb8a3f7349c6e5`, custom domain `networkprofiler.vegui.dev` and matching `vars.PUBLIC_ORIGIN`. Its `main` and `ASSETS` paths are relative to the repository root. The domain's zone must be active in that account and Wrangler must be authenticated with deployment permissions. For another deployment, change those values and compile a matching plugin. Keep the `SERVERS` binding, SQLite Durable Object migration and `ASSETS` binding/SPA fallback. From the repository root:

```sh
npm run build:cloudflare
# Only when you intend to deploy, with Wrangler authentication available:
npm run deploy:cloudflare
```

`build:cloudflare` installs web/backend dependencies using their committed lockfiles, builds Astro, type-checks Hono and runs Wrangler's dry-run packaging. It includes dev dependencies even if `NODE_ENV=production`, because Astro, TypeScript and Wrangler are build tools. It needs Node/npm, not a JDK or Gradle. It never deploys. `deploy:cloudflare` changes your Cloudflare account and uses the already built assets; it intentionally does not repeat the build. API paths run the Worker first; other paths serve Astro assets with SPA fallback, including deep report/settings links.

### Cloudflare Git CI (Workers Builds)

In **Workers & Pages**, connect the Git repository `VeguiDev/network-profiler` to the Worker **`network-profiler`**. Use Workers Builds, not a Pages project. If framework detection suggests Astro/Pages, override it with this Worker configuration:

| Build setting | Value |
| --- | --- |
| Production branch | `master` |
| Root directory | Repository root (`/`), not `profiler-web` or `profiler-backend` |
| Build command | `npm run build:cloudflare` |
| Deploy command | `npm run deploy:cloudflare` |
| Node version | `.node-version` pins `24.18.0` (the Workers Builds default); remove conflicting `NODE_VERSION` overrides or use the same version |

Root npm scripts enter `profiler-backend` for its pinned Wrangler binary, and the backend scripts pass `--config ../wrangler.jsonc`. The config lives at the repository root so Workers Builds discovers the same file directly; `main` and asset paths are root-relative. No global `npx wrangler` download or duplicate config is needed. Root dependency auto-installation is harmless: the root package has no dependencies, and the build script explicitly installs both apps. Keep the root `package-lock.json` and both app lockfiles committed. Do not set `ignore-scripts` for app installation; native build tooling may require installation hooks.

Allow Workers Builds to create/select its deployment API token with access to the configured account, Worker, Durable Objects and custom domain. Do not put credentials in npm scripts or Git. No GitHub Actions secret is needed for Cloudflare's own Git integration. Keep `PUBLIC_ORIGIN` in Wrangler's runtime configuration; plugin `VNP_*` build variables are not needed for the web/API build.

Disable non-production branch/preview deployments initially. Browser Origin checks and the official plugin target the production domain; previews need separate routing/origin/state design and are not configured by these scripts. If setting optional build watch paths, include `profiler-web/**`, `profiler-backend/**`, `wrangler.jsonc`, root `package.json`, `package-lock.json` and `.node-version`. Changes limited to Java do not require a web deployment.

For local authentication before a manual deployment, run `npx wrangler login` and `npx wrangler whoami` from `profiler-backend`. Workers Builds supplies its own authentication; do not use interactive login in CI. A successful dry run validates packaging, not Cloudflare account permissions or live routing.

For isolated local testing:

```sh
cd profiler-backend
npm run dev:cloudflare -- --ip 127.0.0.1 --port 8787 --var PUBLIC_ORIGIN:http://127.0.0.1:8787
```

Build the test plugin with `VNP_RELAY_URL=http://127.0.0.1:8787` for this local test only. Local Wrangler validation does not establish production account limits, global routing or billing behavior. Active sample/heartbeat persistence can incur Durable Object requests/storage costs; hibernatable sockets do not make an actively streaming session free.

## Build-time plugin origins

From the repository root, for a custom deployment:

```sh
VNP_RELAY_URL=https://your-relay.example.com \
VNP_PUBLIC_WEB_URL=https://your-relay.example.com \
  ./gradlew :profiler-platforms:velocity:shadowJar --no-daemon
```

Both values are validated origins and embedded in `vnp-relay.properties` inside the JAR. `VNP_RELAY_URL` defaults to `https://networkprofiler.vegui.dev`; `VNP_PUBLIC_WEB_URL` defaults to the selected relay URL. Gradle tracks them as task inputs, so switching build environments regenerates the resource without requiring a clean build. Runtime environment variables do not override the embedded values. Official CI explicitly pins both to `https://networkprofiler.vegui.dev`. The current browser API requires the dashboard and relay to share an origin; do not use these variables to split them across unrelated hosts.

## Plugin setup and migration

```yaml
relay:
  enabled: true
```

Relay is enabled when this key/section is omitted. Set `enabled: false` to opt out without stopping local collection/commands. Remove older `relay.endpoint` and `relay.public-web-url` keys: they are now rejected, even when relay is disabled. Old known `http` configuration is ignored; no port 8765 is opened. Existing local `reports/` and `dashboard-settings.json` remain usable. The plugin asynchronously registers only if its separate private `relay-identity.json` is absent. That file contains the stable UUID/token and endpoint; it is never copied to browsers or embedded in links. A corrupt file, endpoint mismatch after changing JARs or rejected credentials is an explicit error, not an invitation to silently create another identity. For an intentional migration, stop the proxy, privately archive the old identity and provision a new one for the new endpoint; do not discard local reports/settings. Relay failure does not stop transport sampling, local captures or local commands.

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
