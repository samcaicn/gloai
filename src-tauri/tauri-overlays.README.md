# tauri.*.conf.json

These are **brand / OS overlays** passed to `tauri-action` via
`--config tauri.conf.json --config tauri.<brand>.conf.json --config <os>`,
deep-merged on top of the base `tauri.conf.json` at build time.

## Files

| File | Purpose |
|---|---|
| `tauri.tupai.conf.json` | Brand override for the tupai (tupai-demo / general) brand. The base `tauri.conf.json` already has tupai-shaped defaults, but CI passes this file explicitly so the brand is part of the build matrix (mirrors the safeopc flow) and so local `tauri dev` works the same way as the release build. Updater endpoint matches the `GET /api/update/{brand}/{target}/{arch}/{current_version}` contract from `update/server.py`. |
| `tauri.safeopc.conf.json` | Brand override for the Safeopc (safeopc-prod) OEM. Pass via `tauri --config tauri.conf.json --config tauri.safeopc.conf.json` so the bundle and updater endpoint get the Safeopc productName/identifier. The base `tauri.conf.json` still ships tupai-shaped defaults (it's also what local `tauri dev` falls back to), so don't break that path — only override here what is actually brand-specific. |
| `tauri.safeopc.conf.json` | (macOS + Windows settings inlined, no separate OS overlay needed) |

## Why no `_comment` field in the JSON

`tauri-build` 2.5.6+ rejects unknown top-level fields in tauri config files.
Earlier versions tolerated `_comment` as a JSON-comment convention; current
versions are strict. **Do not add `_comment` (or any other non-spec field)
back to these files** — the build will fail with "unknown field _comment,
expected one of ...". Inline notes belong in this README, in commit messages,
or in the matching line comment in `release.yml` / `ci.yml`.

## Update endpoint placeholder

`https://update.example.com/...` is the **default placeholder** used when
no real update server URL is configured. `release.yml` has a
fail-fast `grep` step that errors out the release build if any of these
files still has `update.example.com` in it — replace the URL in the
matching config file before cutting a release tag.
