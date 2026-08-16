"""Plugin installer: materialize a plugin from a git URL, local path, or archive.

Sources handled:
  * ``git``   -- ``https://.../repo.git`` / ``git@host:org/repo.git`` (cloned)
  * ``local`` -- a directory path on disk (copied)
  * ``url``   -- a ``.zip`` / GitHub archive / ``.dshpreset`` download (extracted)

A ``.dshpreset`` is DSH Desktop's packaged preset (a zip whose root holds
``manifest.json`` + ``preset/``). It is converted into a SafeOPC plugin
manifest (``plugin.yaml``) so it shows up in the plugin profile and benefits
from the same install/refresh lifecycle. Cordis agent execution is a separate
integration; here we install the artifact and register it as an ``agent``
plugin with no Python entry point yet.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile
from pathlib import Path

import yaml

from .errors import PluginError
from .manifest import PluginManifest, PluginState

_DSHPRESET_SUFFIX = ".dshpreset"


def _detect_source_kind(source: str) -> str:
    s = (source or "").strip()
    if s.startswith(("http://", "https://")):
        if s.endswith(".git"):
            return "git"
        return "url"
    if s.startswith("git@"):
        return "git"
    p = Path(s)
    if p.exists():
        return "local"
    return "unknown"


def _read_manifest(plugin_dir: Path) -> PluginManifest:
    for name in ("plugin.yaml", "plugin.yml", "manifest.yaml", "plugin.json", "manifest.json"):
        fp = plugin_dir / name
        if not fp.exists():
            continue
        text = fp.read_text(encoding="utf-8")
        if fp.suffix == ".json":
            data = json.loads(text)
        else:
            data = yaml.safe_load(text) or {}
        return PluginManifest.model_validate(data)
    raise PluginError("manifest_missing", f"No plugin manifest found in {plugin_dir}")


def _safe_extract(zip_path: Path, dest: Path) -> None:
    """Extract a zip, defending against path traversal (zip-slip)."""
    dest = dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path, "r") as zf:
        for member in zf.infolist():
            target = (dest / member.filename).resolve()
            if target != dest and dest not in target.parents:
                raise PluginError(
                    "archive_unsafe", f"Refusing unsafe archive entry: {member.filename}"
                )
            zf.extract(member, dest)


def _download(url: str, dest: Path) -> None:
    try:
        req = urllib.request.Request(
            url, headers={"User-Agent": "SafeOPC-Plugin-Installer/1.0"}
        )
        with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310 - user-provided source
            data = resp.read()
    except Exception as exc:  # noqa: BLE001
        raise PluginError("download_failed", f"Failed to download {url}: {exc}") from exc
    dest.write_bytes(data)


def _find_plugin_root(extracted: Path) -> Path:
    """Locate the directory that contains a plugin/preset manifest."""
    for name in ("plugin.yaml", "plugin.yml", "manifest.yaml", "plugin.json", "manifest.json"):
        if (extracted / name).exists():
            return extracted
    for child in sorted(extracted.iterdir()):
        if not child.is_dir():
            continue
        for name in ("plugin.yaml", "plugin.yml", "manifest.yaml", "plugin.json", "manifest.json"):
            if (child / name).exists():
                return child
    raise PluginError("manifest_missing", f"No plugin manifest found under {extracted}")


def _convert_dsh_preset(preset_root: Path, dest_plugin_dir: Path) -> None:
    """Convert a DSH ``.dshpreset`` (manifest.json + preset/) into a plugin.

    Writes a ``plugin.yaml`` capturing the preset metadata and keeps the
    original ``preset/`` directory so the artifact is installed and inspectable.
    """
    manifest_path = preset_root / "manifest.json"
    if not manifest_path.exists():
        raise PluginError("dsh_manifest_missing", f"No manifest.json in preset: {preset_root}")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))

    def _pick(key: str, fallback: str = "") -> str:
        v = data.get(key)
        if isinstance(v, dict):
            return str(next(iter(v.values()), fallback))
        return str(v or fallback)

    plugin_manifest = {
        "id": str(data.get("slug") or preset_root.name).strip().lower(),
        "name": _pick("title_key", _pick("title", preset_root.name)),
        "version": str(data.get("version") or "0.0.0"),
        "description": _pick("description_key", _pick("description", "")),
        "author": _pick("author", ""),
        "homepage": str(data.get("homepage") or data.get("repository") or ""),
        "kind": "agent",
        "entry": "",
        "dependencies": [],
        "permissions": {},
        "config_schema": {},
    }
    if not plugin_manifest["id"] or not plugin_manifest["id"][0].isalnum():
        plugin_manifest["id"] = "dsh-" + preset_root.name.lower()
    (dest_plugin_dir / "dsh_manifest.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    preset_src = preset_root / "preset"
    if preset_src.is_dir():
        shutil.copytree(preset_src, dest_plugin_dir / "preset", dirs_exist_ok=True)
    (dest_plugin_dir / "plugin.yaml").write_text(
        yaml.safe_dump(plugin_manifest, sort_keys=False, allow_unicode=True),
        encoding="utf-8",
    )


def _install_from_archive(source: str, opc_home: Path, plugin_dir_name: str) -> PluginState:
    dest_root = Path(opc_home) / plugin_dir_name
    dest_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        td_path = Path(td)
        is_dsh = source.strip().lower().endswith(_DSHPRESET_SUFFIX)
        archive = td_path / ("preset.dshpreset" if is_dsh else "archive.zip")
        _download(source, archive)
        extracted = td_path / "extracted"
        _safe_extract(archive, extracted)

        staged = td_path / "staged"
        staged.mkdir(parents=True, exist_ok=True)
        if is_dsh or (extracted / "manifest.json").exists():
            preset_root = _find_plugin_root(extracted)
            has_plugin_manifest = (preset_root / "plugin.yaml").exists() or (
                preset_root / "plugin.json"
            ).exists()
            if has_plugin_manifest:
                shutil.copytree(preset_root, staged, dirs_exist_ok=True)
                manifest = _read_manifest(staged)
            else:
                _convert_dsh_preset(preset_root, staged)
                manifest = _read_manifest(staged)
        else:
            plugin_root = _find_plugin_root(extracted)
            shutil.copytree(plugin_root, staged, dirs_exist_ok=True)
            manifest = _read_manifest(staged)

        dest = dest_root / manifest.id
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(staged, dest)
        return PluginState.from_manifest(manifest, source=source)


def install_plugin(
    source: str,
    opc_home: Path,
    plugin_dir_name: str = "plugins",
) -> PluginState:
    """Materialize a plugin into ``<opc_home>/<plugin_dir_name>/<id>`` and return its state."""
    kind = _detect_source_kind(source)
    if kind == "unknown":
        raise PluginError(
            "unsupported_source",
            f"Cannot install from '{source}'. Use a git URL, a local directory, "
            "or a .zip / .dshpreset download link.",
        )

    if kind == "local":
        src = Path(source).resolve()
        if src.is_file():
            src = src.parent
        if not src.is_dir():
            raise PluginError("source_not_dir", f"Local source is not a directory: {src}")
        manifest = _read_manifest(src)
        dest_root = Path(opc_home) / plugin_dir_name
        dest_root.mkdir(parents=True, exist_ok=True)
        dest = dest_root / manifest.id
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(src, dest)
        return PluginState.from_manifest(manifest, source=source)

    if kind == "git":
        with tempfile.TemporaryDirectory() as td:
            try:
                subprocess.run(
                    ["git", "clone", "--depth", "1", source.strip(), td],
                    check=True,
                    capture_output=True,
                )
            except subprocess.CalledProcessError as exc:
                stderr = exc.stderr.decode("utf-8", "replace") if exc.stderr else ""
                raise PluginError("git_clone_failed", f"git clone failed: {stderr}") from exc
            manifest = _read_manifest(Path(td))
            dest_root = Path(opc_home) / plugin_dir_name
            dest_root.mkdir(parents=True, exist_ok=True)
            dest = dest_root / manifest.id
            if dest.exists():
                shutil.rmtree(dest)
            shutil.copytree(Path(td), dest)
            return PluginState.from_manifest(manifest, source=source)

    # kind == "url"
    return _install_from_archive(source, opc_home, plugin_dir_name)
