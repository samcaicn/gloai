---
name: raphael-web2api
description: >-
  Generate images through Raphael AI (raphael.app) — the free, no-login AI image
  generator — via a local web2api proxy. The proxy keeps a persistent Playwright
  browser session, clears Cloudflare Turnstile, and exposes an OpenAI-compatible
  POST /v1/images/generations endpoint. Use this built-in skill whenever the user
  wants AI-generated images from Raphael / raphael.app, or says "open raphael" /
  "用 raphael 生图" / "生成 raphael 图片".
always: true
metadata:
  kind: builtin-integration
  cli: opc-raphael-web2api
  page_url_template: "http://{host}:{port}/health"
  config_schema: opc/skills_assets/raphael-web2api/config.schema.json
  config_defaults: opc/skills_assets/raphael-web2api/config.default.yaml
  user_config_path: "<opc_home>/config/raphael-web2api.yaml"
---

# raphael-web2api — Raphael AI (raphael.app) image generation

This built-in skill turns **Raphael AI** (https://raphael.app — the free,
no-login AI image generator) into a local OpenAI-compatible image API. SafeOPC
ships it as a **built-in integration** so the runtime (and every spawned external
agent) can call it through the `opc-raphael-web2api` CLI.

The proxy is a small **Python + Playwright + FastAPI** service. Raphael gates
generation behind **Cloudflare Turnstile**, so the proxy drives a real
(headless) Chromium session, lets it clear Cloudflare, and captures the generated
image URLs from the streamed `/api/generate-image` response (by hooking
`window.fetch` and reading a copy of the NDJSON stream).

## When to use

- The user wants AI-generated **images** (text-to-image) from Raphael / raphael.app.
- The user says "open raphael" / "打开 raphael" / "用 raphael 生图" / "生成 raphael 图片".

Do NOT use this skill for general chat, file edits, or code — those go through
your native tools.

## How to ensure the proxy is running (the main action)

```bash
opc-raphael-web2api status   # is the proxy up?
opc-raphael-web2api start    # setup venv (first time) + launch + wait for health
opc-raphael-web2api stop     # stop the background proxy
opc-raphael-web2api config   # print the effective, merged configuration (JSON)
```

`start` is idempotent: on first run it creates a virtualenv, installs
`fastapi` / `uvicorn` / `playwright`, then launches `server.py` in the
background and waits for the port to answer. Subsequent `start` calls reuse the
existing install.

If `auto_launch` is true (default), `opc-raphael-web2api start` launches the
proxy automatically and opens its health page in **SafeOPC's built-in browser**
(via the office-UI `/api/ui/open-browser` endpoint), falling back to the OS
default browser when the desktop app is not running. Pass `--no-browser` to skip
auto-open.

## Authentication / raising the limit

Raphael is usable anonymously, but the free tier caps **~2 generations per day
per source IP** (the limit is keyed on the egress IP, *not* on a session cookie —
verified: brand-new browser contexts on the same egress IP all hit
`ANON_DAILY_LIMIT` with `used:2`, so clearing cookies does nothing). There are
two ways to get more throughput:

1. **Proxy pool (egress-IP rotation) — the real bypass, stays anonymous.**
   Supply a pool of upstream proxies, each a distinct egress IP. The proxy
   round-robins over them and automatically retries on `ANON_DAILY_LIMIT` by
   switching to the next proxy. Each distinct egress IP carries its own ~2/day
   quota, so N proxies ≈ N×2 images/day. See the next section.
2. **Logged-in account cookies (recommended companion).** Log into
   raphael.app, export its cookies (DevTools → Application → Cookies →
   raphael.app, or a cookie-export extension) as a JSON array of `{name, value,
   domain, path, ...}` objects, save to a file, and set `cookies_path` in
   `<opc_home>/config/raphael-web2api.yaml`. The proxy loads them on startup and
   uses the account quota as a fallback when every proxy is exhausted.

## Bypassing the anonymous daily limit (proxy pool)

The anonymous cap is enforced **per source IP**, so the only effective bypass is
to rotate the egress IP. Configure a pool of proxies and the server handles the
rest:

```yaml
# <opc_home>/config/raphael-web2api.yaml
proxies:
  - "http://user:pass@1.2.3.4:8080"
  - "http://user:pass@5.6.7.8:8080"
  - "socks5://10.0.0.1:1080"
  - "direct"          # a direct connection (no proxy) counts as one egress
rotate_proxies: true
max_proxy_retries: 8    # attempts across proxies before giving up
```

Equivalent via environment variable (newline- or comma-separated):

```bash
export RAPHAEL_PROXIES="http://1.2.3.4:8080,http://5.6.7.8:8080,direct"
```

The server launches one Chromium per proxy (lazily, on first use), round-robins
generation requests across the non-exhausted ones, and on `ANON_DAILY_LIMIT`
marks that egress exhausted and moves to the next. When all anonymous proxies
are spent, it falls back to the account-cookie session if configured.

## Generate an image

```bash
opc-raphael-web2api gen-image \
  --prompt "a red cat on a windowsill, cinematic lighting" \
  --aspect-ratio 1:1 \
  --n 1
```

- Endpoint: `POST /v1/images/generations`
- Supported `aspect_ratio`: `1:1`, `16:9`, `9:16`, `4:3`, `3:4`, `21:9`, `2:3`, `3:2`.
- The returned image URLs are printed as JSON on stdout.

Example response:

```json
{ "created": 1690000000, "data": [ { "url": "https://cdn.raphael.app/..." } ] }
```

## Configuration

Effective config = packaged defaults → user file
`<opc_home>/config/raphael-web2api.yaml` → CLI flags.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `host` | string | `127.0.0.1` | Proxy bind host. |
| `port` | integer | `8771` | Proxy bind port. |
| `install_dir` | string | `<opc_home>/integrations/raphael-web2api` | Where the venv + runtime files live. |
| `headless` | bool | `true` | Run Chromium headless. Set `false` if Turnstile blocks headless. |
| `cookies_path` | string | `""` | JSON cookies of a logged-in session (account quota fallback). |
| `pw_browsers_path` | string | `""` | `PLAYWRIGHT_BROWSERS_PATH` override (defaults to the hermes ms-playwright build). |
| `proxies` | list[string] | `[]` | Upstream proxy pool for egress-IP rotation (the anon-limit bypass). `"direct"` = no proxy. Empty = system proxy (single egress). |
| `rotate_proxies` | bool | `true` | Round-robin over the pool + auto-retry on `ANON_DAILY_LIMIT` by switching egress. |
| `max_proxy_retries` | int | `8` | Max generation attempts across proxies before giving up. |
| `auto_launch` | bool | `true` | Launch the proxy automatically on `start`. |
| `open_page` | bool | `true` | Open the health page in the browser after launch. |
| `log_level` | string | `INFO` | CLI log level. |

User override example (`<opc_home>/config/raphael-web2api.yaml`):

```yaml
port: 8771
cookies_path: "C:/Users/User/raphael_cookies.json"
proxies:
  - "http://user:pass@1.2.3.4:8080"
  - "http://user:pass@5.6.7.8:8080"
  - "direct"
open_page: false
```

## Notes / limits

- The proxy needs Python 3.11+ and an outbound network path through the
  environment proxy. On first `start` it creates a venv and `pip install`s the
  dependencies (Playwright downloads its Chromium if not already present).
- The proxy is a **web reverse-proxy** for Raphael AI: it uses a real browser
  session (optionally a logged-in cookie jar), not an official vendor API key.
  Keep the cookies file local; do not commit it.
- **Raphael has no public API and its Terms flag reverse-engineering as
  unauthorized.** This skill is for personal, non-commercial, research/educational
  use. Respect their ToS and do not hammer their servers.
- The CLI is the single entry point — do not launch `server.py` manually; it
  would bypass the merged config and the pidfile used by `stop`.
- Requests rotate across the configured proxy pool (one Chromium per egress);
  for even higher throughput, run multiple `opc-raphael-web2api` instances on
  different ports behind a load balancer.
- The anonymous ~2/day cap is **per egress IP**. Without a proxy pool (or with a
  single egress), you are still limited to ~2 images/day. The proxy pool is what
  removes that ceiling.
