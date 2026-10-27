# HTTPS ingress for the hosted Node service

This ingress is for the **hosted Hono backend**, not Minecraft. The plugin has no HTTP listener. Keep Node at `127.0.0.1:3000`, set `PUBLIC_ORIGIN=https://networkprofiler.vegui.dev` and restrict direct access to that port. Cloudflare deployments use their hosted endpoint instead. A different domain requires a plugin built for that origin.

```nginx
# In nginx's http context:
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}

server {
    listen 443 ssl;
    server_name networkprofiler.vegui.dev;
    ssl_certificate /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 90s;
        proxy_buffering off;
    }
}
```

Preserve browser Origin, cookies, `X-VNP-CSRF` and plugin Authorization. Do not enable permissive CORS or cache `/api`. Browser assets, API and sockets share this origin and retain complete dashboard path prefixes. Standard access logs must not include secrets, query/body data or full socket frames. Add registration/viewer rate limits and infrastructure quotas before public use; this minimal example does not configure abuse protection.

Browser approval is still required through `/vnp trustSession`; optional SSO/access gateways are an additional boundary. Gateways that require interactive browser authentication on the plugin's registration/WebSocket paths need a separately configured noninteractive policy; the plugin does not implement a gateway login flow. Never publish a rule that bypasses the relay's own UUID/token or viewer-cookie checks.

See [deployment and retention](hosting.md).
