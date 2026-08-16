---
name: jimeng2api
description: >-
  Generate images and videos through the Jimeng (即梦 / Dreamina) API proxy
  "jimeng2api". Launch the local proxy, open its admin page to add a Jimeng
  sessionid and create an API key, then generate text-to-image / image-to-image
  and text-to-video / image-to-video assets via the OpenAI-compatible endpoints.
  Use this built-in skill whenever the user wants AI-generated images or videos
  from Jimeng / Dreamina, or says "open jimeng" / "生成即梦图片" / "生成即梦视频".
always: true
metadata:
  kind: builtin-integration
  cli: opc-jimeng2api
  page_url_template: "http://{host}:{port}"
  config_schema: opc/skills_assets/jimeng2api/config.schema.json
  config_defaults: opc/skills_assets/jimeng2api/config.default.yaml
  user_config_path: "<opc_home>/config/jimeng2api.yaml"
---

# jimeng2api — Jimeng (即梦 / Dreamina) image & video generation

[jimeng2api](https://github.com/carzygod/jimeng2api) is a local reverse-proxy
that exposes Jimeng / Dreamina's web image and video generation as
OpenAI-compatible HTTP endpoints. SafeOPC ships it as a **built-in skill** so
the runtime (and every spawned external agent) can call it through the
`opc-jimeng2api` CLI.

The proxy runs as a **Node.js 18+ service on port 5100**. Auth has two halves:
the proxy holds one or more Jimeng **sessionid** tokens (added through its admin
page), and every API call carries a proxy-issued **API key** in the
`Authorization: Bearer` header.

## When to use

- The user wants AI-generated **images** (text-to-image or image-to-image) from
  Jimeng / 即梦.
- The user wants AI-generated **videos** (text-to-video or image-to-video) from
  Jimeng / 即梦 (Seedance / Jimeng video models).
- The user says "open jimeng" / "打开即梦" / "生成即梦图片" / "生成即梦视频".

Do NOT use this skill for general chat, file edits, or code — those go through
your native tools.

## How to ensure the proxy is running (the main action)

```bash
opc-jimeng2api status   # is the proxy up?
opc-jimeng2api start    # clone/install (first time) + launch + wait for health
opc-jimeng2api stop     # stop the background proxy
opc-jimeng2api config   # print the effective, merged configuration (JSON)
```

`start` is idempotent: on first run it clones the repo, runs `npm install` and
`npm run build`, then launches `npm run start` in the background and waits for
the port to answer. Subsequent `start` calls reuse the existing install.

If `auto_launch` is true (default), `opc-jimeng2api start` launches the proxy
automatically and opens its admin page in **SafeOPC's built-in browser** (via
the office-UI `/api/ui/open-browser` endpoint). When the desktop app is not
running the CLI falls back to the OS default browser. Pass `--no-browser` to
skip auto-open and open the printed URL manually.

## Authentication (the one manual step)

The proxy does NOT use an official API key. You must:

1. Open the admin page: `http://{host}:{port}` (run `opc-jimeng2api start`, which
   opens it in SafeOPC's built-in browser and prints the URL).
2. Add a Jimeng / Dreamina **sessionid** token in the admin UI (this is the web
   account credential the proxy generates media from).
3. Create an **API key** in the admin UI.
4. Write that API key into the user config
   `<opc_home>/config/jimeng2api.yaml` under `api_key:`, then re-run
   `opc-jimeng2api config` to confirm it is picked up.

Until `api_key` is set, `gen-image` / `gen-video` return an auth error.

## Generate an image

```bash
opc-jimeng2api gen-image \
  --model jimeng-image-4.6 \
  --prompt "a serene lake at sunrise, cinematic lighting" \
  --ratio 16:9 \
  --output ./deliverables/lake.png
```

- Endpoint: `POST /v1/images/generations`
- Common image models: `jimeng-image-5.0`, `jimeng-image-4.6`,
  `jimeng-image-4.5`, `jimeng-image-3.1`, `nanobanana`, `nanobananapro`.
- The result URL (or saved file path) is printed as JSON on stdout.

## Generate a video

```bash
opc-jimeng2api gen-video \
  --model seedance-2.0-fast \
  --prompt "two people sparring in a cinematic scene" \
  --duration 5 \
  --resolution 720p \
  --ratio 16:9
```

- Endpoint: `POST /v1/video/generations`. Video generation is async — the call
  returns a `task_id`.
- Poll until done:

```bash
opc-jimeng2api poll --task-id <task_id> --output ./deliverables/clip.mp4
```

- Common video models: `seedance-2.0`, `seedance-2.0-fast`,
  `seedance-2.0-mini`, `jimeng-video-3.5-pro`, `jimeng-video-3.0-pro`.
- `poll` blocks (bounded) and prints the final video URL / saved path as JSON.

## Configuration

Effective config = packaged defaults → user file
`<opc_home>/config/jimeng2api.yaml` → CLI flags.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `host` | string | `127.0.0.1` | Proxy bind host. |
| `port` | integer | `5100` | Proxy bind port. |
| `install_dir` | string | `<opc_home>/integrations/jimeng2api` | Where the repo is cloned / run from. |
| `repo_url` | string | `https://github.com/carzygod/jimeng2api.git` | Source repo to clone on first `start`. |
| `admin_key` | string | auto-generated | `JIMENG_ADMIN_KEY` for the proxy (empty = random on first run). |
| `api_key` | string | `""` | Proxy-issued API key used in `Authorization: Bearer` for gen-* calls. |
| `auto_launch` | bool | `true` | Launch the proxy automatically on `start`. |
| `open_page` | bool | `true` | Open the admin page in SafeOPC's built-in browser after launch. |
| `log_level` | string | `INFO` | CLI log level. |

User override example (`<opc_home>/config/jimeng2api.yaml`):

```yaml
port: 5100
api_key: "jk-xxxxxxxxxxxxxxxx"
open_page: false
```

## Notes / limits

- The proxy needs **Node.js 18+** and `git` on `PATH` for the first-time
  `start` (clone + npm install + build). Subsequent starts skip the build.
- The proxy is a **web reverse-proxy** for Jimeng / Dreamina: it uses a Jimeng
  **sessionid**, not an official vendor API key. Keep the sessionid and the
  proxy API key in the admin UI / config file; do not commit them.
- Generation is billed against the linked Jimeng account's quota; respect
  Jimeng / Dreamina's terms of service and rate limits.
- The CLI is the single entry point — do not start `npm run start` manually; it
  would bypass the merged config and not write the pidfile used by `stop`.
- Video generation is async; always `poll` the returned `task_id`.
