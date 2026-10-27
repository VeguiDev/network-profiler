# Remote access with nginx

Keep the plugin at `127.0.0.1:8765`. Restrict this port at the host firewall. Configure TLS and an authentication provider; this example uses nginx basic auth over TLS. Generate `/etc/nginx/network-profiler.htpasswd` with your normal credential-management tooling. Do not commit credentials.

Add the public hostname to the plugin configuration:

```yaml
http:
  host: "127.0.0.1"
  port: 8765
  allowed-hosts: ["127.0.0.1", "localhost", "profiler.example.com"]
  public-base-url: "https://profiler.example.com"
```

In nginx's `http` context:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}

server {
    listen 443 ssl;
    server_name profiler.example.com;
    ssl_certificate /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    # Protect every route, including /api and WebSocket upgrades.
    auth_basic "Network Profiler";
    auth_basic_user_file /etc/nginx/network-profiler.htpasswd;

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 60s;
        proxy_buffering off;
    }
}
```

Serve the dashboard at the origin root. The application uses absolute `/api/v1/...` paths. WebSocket Origin and the forwarded Host must identify the same authority; use `allowed-origins` only for explicitly trusted alternate origins, not `*`. The application does not trust forwarded headers as an authentication mechanism.

Capture start/stop and settings POSTs also validate Origin and `X-MNP-CSRF`. Preserve browser Origin and the normal custom header; do not strip authentication from `/api/v1/captures` or `/api/v1/settings`. Origin/CSRF validation does not replace authentication. Basic auth does not provide individual profiler accounts or authorization roles: anyone permitted through this proxy can start/stop captures and edit shared visual bandwidth settings. For stronger control use your existing SSO/access gateway with WebSocket support. For personal access an SSH tunnel avoids exposing the service at all.
