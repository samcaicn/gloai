"""DSH ``.dshpreset`` package lifecycle (faithful port of dsh-desktop).

``.dshpreset`` is a ZIP whose root holds ``manifest.json`` (format v1) plus a
``preset/`` directory (typically ``preset/agent.cordis.yml`` + assets). This
module mirrors dsh-desktop's two guarded operations:

  * :func:`validate_preset_package` -- the **import-preview** step. It refuses
    unsafe or malformed packages (missing manifest, wrong format/version,
    missing ``preset/agent.cordis.yml``, oversized) and flags *warnings*
    (possible secrets, absolute-path references, DSH version drift) without
    aborting, exactly like ``POST /api/agent-preset.import`` preview.
  * :func:`build_dsh_preset` -- the **export** step (``GET
    /api/agent-preset.export``). It serializes an installed agent plugin back
    into a ``.dshpreset`` so it can be re-imported on another machine.

The Cordis plugin engine itself lives upstream in ``@deepseek-ai/dsh`` and is
Node-only; what is faithfully portable to SafeOPC's Python profile is this
package contract + its safety envelope, which is what this module implements.
"""

from __future__ import annotations

import datetime
import io
import json
import re
import zipfile
from pathlib import Path
from typing import Any

from .errors import PluginError

# dsh-desktop rejects oversized packages; 50 MiB is a comfortable default.
PRESET_MAX_BYTES = 50 * 1024 * 1024

# The DSH version a SafeOPC agent runtime is compatible with. Mismatches only
# produce a warning (the agent may still work), never a hard failure.
SAFEOPC_DSH_COMPAT_VERSION = "0.1.0-rc.6"

_MANIFEST_VERSION = 1
_MANIFEST_FORMAT = "dsh-preset"
_REQUIRED_PRESET_FILE = "preset/agent.cordis.yml"

# Secret-looking patterns surfaced as "possible-secrets" warnings. Matching is
# advisory only -- it never blocks an import, it just warns the operator.
_SECRET_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("openai", re.compile(r"sk-[A-Za-z0-9_\-]{20,}")),
    ("github", re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}")),
    ("aws", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("google", re.compile(r"AIza[0-9A-Za-z_\-]{35}")),
    ("google_oauth", re.compile(r"ya29\.[A-Za-z0-9_\-]{20,}")),
    ("slack", re.compile(r"xox[baprs]-[A-Za-z0-9\-]{10,}")),
    ("private_key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("generic_token", re.compile(r"(?i)(?:api[_-]?key|secret|token|password)\s*[:=]\s*['\"]?[^\s'\"]{8,}")),
]

# Absolute / drive-path references surfaced as "absolute-paths" warnings.
_ABS_PATH_PATTERN = re.compile(r"(?:/home/|/Users/|/root/|/etc/|[A-Za-z]:\\\\)")


def _scan_secrets(text: str) -> list[str]:
    hits: list[str] = []
    for label, pat in _SECRET_PATTERNS:
        if pat.search(text):
            hits.append(label)
    return hits


def _scan_absolute_paths(text: str) -> list[str]:
    return bool(_ABS_PATH_PATTERN.search(text))


def _read_preset_manifest(preset_root: Path) -> dict[str, Any]:
    manifest_path = preset_root / "manifest.json"
    if not manifest_path.exists():
        raise PluginError(
            "preset_manifest_missing",
            f"A .dshpreset must contain manifest.json at its root (missing in {preset_root}).",
        )
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        raise PluginError("preset_manifest_invalid", f"manifest.json is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise PluginError("preset_manifest_invalid", "manifest.json must be a JSON object.")
    return data


def validate_preset_package(preset_root: Path) -> dict[str, Any]:
    """Validate a staged ``.dshpreset`` directory (the import-preview step).

    Returns a dict::

        {
          "ok": bool,
          "preset_id": str,
          "name": str,
          "manifest": dict,
          "warnings": {"possible_secrets": [...], "absolute_paths": [...], "version_note": str|None},
          "errors": [{"code": str, "message": str}, ...],
        }

    Hard errors (missing manifest, wrong format/version, missing
    ``preset/agent.cordis.yml``, oversized) set ``ok=False``; the caller should
    refuse the install. Warnings never block.
    """
    errors: list[dict[str, str]] = []
    warnings: dict[str, Any] = {"possible_secrets": [], "absolute_paths": [], "version_note": None}

    try:
        data = _read_preset_manifest(preset_root)
    except PluginError as e:
        return {
            "ok": False,
            "preset_id": "",
            "name": "",
            "manifest": {},
            "warnings": warnings,
            "errors": [{"code": e.code, "message": e.message}],
        }

    fmt = data.get("format")
    ver = data.get("version")
    if fmt != _MANIFEST_FORMAT:
        errors.append(
            {"code": "unsupported_format", "message": f"manifest.format must be '{_MANIFEST_FORMAT}', got {fmt!r}."}
        )
    if ver != _MANIFEST_VERSION:
        errors.append(
            {"code": "unsupported_version", "message": f"manifest.version must be {_MANIFEST_VERSION}, got {ver!r}."}
        )

    preset_id = str(data.get("id") or preset_root.name)
    name = str(data.get("name") or preset_root.name)

    # Required composition: preset/agent.cordis.yml.
    if not (preset_root / _REQUIRED_PRESET_FILE).exists():
        errors.append(
            {
                "code": "missing_composition",
                "message": f"A .dshpreset must contain '{_REQUIRED_PRESET_FILE}'.",
            }
        )

    # Size guard (sum of staged file bytes).
    total = 0
    for fp in preset_root.rglob("*"):
        if fp.is_file():
            try:
                total += fp.stat().st_size
            except OSError:
                pass
    if total > PRESET_MAX_BYTES:
        errors.append(
            {
                "code": "package_too_large",
                "message": f"Preset is {total} bytes; limit is {PRESET_MAX_BYTES} bytes.",
            }
        )

    # Advisory scans over preset/* text files.
    for fp in sorted(preset_root.rglob("*")):
        if not fp.is_file():
            continue
        # Symlinks / absolute entries are rejected outright (zip-slip already
        # blocks these at extract time; this is a second defensive check).
        if fp.is_symlink():
            errors.append({"code": "unsafe_entry", "message": f"Refusing symlink entry: {fp.relative_to(preset_root)}."})
            continue
        try:
            text = fp.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue  # binary asset; not scannable
        for label in _scan_secrets(text):
            if label not in warnings["possible_secrets"]:
                warnings["possible_secrets"].append(label)
        if _scan_absolute_paths(text) and fp.relative_to(preset_root).as_posix() not in (
            "manifest.json",
        ):
            warnings["absolute_paths"].append(fp.relative_to(preset_root).as_posix())

    # Version-drift note.
    src_ver = str(data.get("sourceDshVersion") or "")
    if src_ver and src_ver != SAFEOPC_DSH_COMPAT_VERSION:
        warnings["version_note"] = (
            f"Preset built for DSH {src_ver}; SafeOPC is compatible with {SAFEOPC_DSH_COMPAT_VERSION}. "
            "Behavior may differ."
        )

    return {
        "ok": len(errors) == 0,
        "preset_id": preset_id,
        "name": name,
        "manifest": data,
        "warnings": warnings,
        "errors": errors,
    }


def build_dsh_preset(state: Any, plugin_dir: Path) -> bytes:
    """Export an installed agent plugin as a ``.dshpreset`` ZIP (in memory).

    Layout mirrors dsh-desktop: root ``manifest.json`` (format v1) + a
    ``preset/`` directory holding the plugin's files and a synthesized
    ``preset/agent.cordis.yml`` when one is not already present, so the package
    passes :func:`validate_preset_package` on re-import (round-trip safe).
    """
    manifest = {
        "format": "dsh-preset",
        "version": _MANIFEST_VERSION,
        "id": state.id,
        "name": state.name or state.id,
        "description": state.description or "",
        "sourceDshVersion": str((state.meta or {}).get("sourceDshVersion") or SAFEOPC_DSH_COMPAT_VERSION),
        "exportedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))

        preset_rel = Path("preset")
        has_cordis = False
        for fp in sorted(plugin_dir.rglob("*")):
            if not fp.is_file():
                continue
            rel = fp.relative_to(plugin_dir)
            if rel.as_posix() == "manifest.json":
                continue  # never pack the export manifest back in
            target = preset_rel / rel
            if rel.as_posix() == "agent.cordis.yml":
                has_cordis = True
            zf.writestr(target.as_posix(), fp.read_bytes())

        if not has_cordis:
            cordis = (
                f"# Auto-generated by SafeOPC for preset round-trip export\n"
                f"plugins:\n"
                f"  - id: {state.id}\n"
                f"    name: {state.name or state.id}\n"
            )
            zf.writestr(f"{preset_rel.as_posix()}/agent.cordis.yml", cordis)

    return buf.getvalue()
