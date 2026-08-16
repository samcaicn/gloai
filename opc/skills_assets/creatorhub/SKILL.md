---
name: creatorhub
description: >-
  Publish and manage Xiaohongshu (小红书 / RED) content through the CreatorHub
  sidecar. Launch the CreatorHub service and open its web page to log in, draft,
  and publish notes; inspect published content and accounts. Use this built-in
  skill whenever the user wants to create, schedule, or publish Xiaohongshu
  posts, or manage CreatorHub accounts/profiles.
always: true
metadata:
  kind: builtin-integration
  cli: opc-creatorhub
  page_url_template: "http://{host}:{port}"
  config_schema: opc/skills_assets/creatorhub/config.schema.json
  config_defaults: opc/skills_assets/creatorhub/config.default.yaml
  user_config_path: "<opc_home>/config/creatorhub.yaml"
---

# CreatorHub — Xiaohongshu publishing sidecar

CreatorHub is a FastAPI sidecar that drives a **system browser** (Brave / Chrome,
no bundled browser) to publish notes on Xiaohongshu. SafeOPC ships it as a
**built-in skill** so you can open its page and operate it directly. The skill
is always available (`always: true`) and is installed automatically on `opc init`
and for every external agent SafeOPC spawns.

## When to use

- The user wants to **publish / draft / schedule a Xiaohongshu (小红书) note**.
- The user wants to **log into a Xiaohongshu account**, manage profiles, or
  check published content.
- The user says "open CreatorHub" / "打开 CreatorHub".

## How to open the page (the main action)

Run the bundled CLI (on `PATH` via the `opc-creatorhub` shim):

```bash
opc-creatorhub open
```

This command:

1. Resolves the effective config (defaults → user file → CLI flags).
2. If the sidecar is not running and `auto_launch` is true, sets up the isolated
   venv (no browser download) and starts the FastAPI service on
   `http://{host}:{port}`.
3. Waits for `/health`.
4. Opens `http://{host}:{port}` in **SafeOPC's built-in (in-app) browser** when the
   desktop app is running; otherwise falls back to the system default browser.
   Skipped with `--no-browser`.

On Windows external-agent runs, prefer not relying on auto-open — write the URL
out and open it yourself:

```bash
opc-creatorhub open --no-browser
# then open the printed URL in the driven browser
```

## Other commands

- `opc-creatorhub setup` — create the isolated venv + write config (no browser).
- `opc-creatorhub start` — start the sidecar in the background.
- `opc-creatorhub status` — probe `/health` and print the result.
- `opc-creatorhub stop` — stop the running sidecar.
- `opc-creatorhub config` — print the effective, merged configuration (JSON).
- `opc-creatorhub open [--no-browser] [--host H] [--port P]` — launch + open page.

## Configuration

Config items (designed defaults in `config.default.yaml`; JSON Schema in
`config.schema.json`). Effective config = **defaults → user file
`<opc_home>/config/creatorhub.yaml` → CLI flags**.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `host` | string | `127.0.0.1` | Sidecar bind host. |
| `port` | integer | `8000` | Sidecar bind port. |
| `data_root` | string | `<opc_home>/integrations/creatorhub` | Profiles / media / db root. Empty = default. |
| `platform` | enum `xhs` | `xhs` | Target platform (only `xhs` today). |
| `xhs_browser_mode` | enum `auto`,`cdp` | `auto` | `auto` = system Brave/Chrome; `cdp` = controlled launch. |
| `browser` | enum `bravenative`,`chrome` | `bravenative` | Advisory: which system browser CreatorHub drives (Brave preferred). |
| `headless` | bool | `false` | Advisory: run the driven browser headless. |
| `auto_launch` | bool | `true` | Launch the sidecar automatically on `open`. |
| `open_page` | bool | `true` | Open the web page in the browser after launch. |
| `auto_stop_on_exit` | bool | `false` | Stop the sidecar when SafeOPC exits. |
| `log_level` | enum `DEBUG`,`INFO`,`WARNING`,`ERROR` | `INFO` | Service log level. |

User override example (`<opc_home>/config/creatorhub.yaml`):

```yaml
port: 8100
browser: chrome
open_page: false
```

## Notes / limits

- Browser-gated endpoints (login / collect / publish) require a **real, manually
  logged-in Xiaohongshu session** in the driven browser. The skill opens the
  page; the QR / login step is done by the user in the browser.
- CreatorHub prefers the **system Brave/Chrome**; it is **not** bundled with a
  browser binary.
- Published-content reads need `account_id` (a logged-in Xiaohongshu account).
- The CLI is the single entry point — do not start uvicorn manually; it would
  bypass the merged config and write data into the repo.
