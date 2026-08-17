"""Offline tests for the DSH ``.dshpreset`` lifecycle port (no network / build).

Covers:
  * ``_convert_dsh_preset`` maps the real manifest v1 schema (id/name/description).
  * ``validate_preset_package`` refuses missing composition / bad format / version.
  * advisory warnings fire for possible-secrets and absolute-path references.
  * ``build_dsh_preset`` round-trips: export -> re-validate passes.
"""

import base64
import io
import json
import zipfile
from pathlib import Path

from opc.plugin_core.manifest import PluginManifest, PluginState
from opc.plugin_core.installer import _convert_dsh_preset, preview_install
from opc.plugin_core.preset import (
    PRESET_MAX_BYTES,
    build_dsh_preset,
    validate_preset_package,
)


def _write_preset(root: Path, *, manifest: dict, cordis: str = "plugins:\n  - id: x\n",
                  extra: dict | None = None) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    preset_dir = root / "preset"
    preset_dir.mkdir(exist_ok=True)
    (preset_dir / "agent.cordis.yml").write_text(cordis, encoding="utf-8")
    if extra:
        for name, content in extra.items():
            (preset_dir / name).write_text(content, encoding="utf-8")


def test_convert_dsh_preset_maps_v1_fields():
    root = Path(__file__).parent / "_tmp_preset_src"
    if root.exists():
        import shutil
        shutil.rmtree(root)
    manifest = {
        "format": "dsh-preset",
        "version": 1,
        "id": "My-Agent",
        "name": "My Agent",
        "description": "Does things.",
        "sourceDshVersion": "0.1.0-rc.6",
        "exportedAt": "2026-08-14T12:00:00.000Z",
    }
    _write_preset(root, manifest=manifest)

    dest = Path(__file__).parent / "_tmp_preset_dest"
    if dest.exists():
        import shutil
        shutil.rmtree(dest)
    dest.mkdir(parents=True, exist_ok=True)

    _convert_dsh_preset(root, dest)

    py = dest / "plugin.yaml"
    assert py.exists(), "plugin.yaml should be written"
    import yaml
    data = yaml.safe_load(py.read_text(encoding="utf-8"))
    # id is lowercased; name/description come straight from v1 manifest.
    assert data["id"] == "my-agent", data
    assert data["name"] == "My Agent", data
    assert data["description"] == "Does things.", data
    assert data["kind"] == "agent"
    # DSH metadata preserved verbatim in dsh_manifest.json + sidecar meta.
    dsh = json.loads((dest / "dsh_manifest.json").read_text(encoding="utf-8"))
    assert dsh["sourceDshVersion"] == "0.1.0-rc.6"
    meta = json.loads((dest / ".dsh_meta.json").read_text(encoding="utf-8"))
    assert meta["sourceDshVersion"] == "0.1.0-rc.6"
    assert meta["format"] == "dsh-preset"
    # Build a PluginState from the converted manifest to confirm meta attaches.
    m = PluginManifest.model_validate(data)
    st = PluginState.from_manifest(m, source="https://github.com/x/My-Agent.git", meta=meta)
    assert st.meta["sourceDshVersion"] == "0.1.0-rc.6"


def test_validate_refuses_missing_composition():
    root = Path(__file__).parent / "_tmp_preset_nocomp"
    if root.exists():
        import shutil
        shutil.rmtree(root)
    root.mkdir(parents=True, exist_ok=True)
    (root / "manifest.json").write_text(
        json.dumps({"format": "dsh-preset", "version": 1, "id": "x", "name": "x"}),
        encoding="utf-8",
    )
    # No preset/agent.cordis.yml
    report = validate_preset_package(root)
    assert report["ok"] is False
    codes = {e["code"] for e in report["errors"]}
    assert "missing_composition" in codes


def test_validate_refuses_bad_format_and_version():
    root = Path(__file__).parent / "_tmp_preset_badfmt"
    if root.exists():
        import shutil
        shutil.rmtree(root)
    _write_preset(root, manifest={"format": "nope", "version": 2, "id": "x", "name": "x"})
    report = validate_preset_package(root)
    assert report["ok"] is False
    codes = {e["code"] for e in report["errors"]}
    assert "unsupported_format" in codes
    assert "unsupported_version" in codes


def test_validate_warns_on_secrets_and_absolute_paths():
    root = Path(__file__).parent / "_tmp_preset_warn"
    if root.exists():
        import shutil
        shutil.rmtree(root)
    manifest = {"format": "dsh-preset", "version": 1, "id": "x", "name": "x"}
    _write_preset(
        root,
        manifest=manifest,
        extra={
            "config.env": "export API_KEY=sk-abcdefghijklmnopqrstuvwx\n",
            "note.txt": "see /Users/me/secret.txt for details\n",
        },
    )
    report = validate_preset_package(root)
    assert report["ok"] is True  # warnings never block
    assert "openai" in report["warnings"]["possible_secrets"]
    assert len(report["warnings"]["absolute_paths"]) > 0


def test_export_roundtrip_revalidates():
    # Build a PluginState + plugin dir, export, then re-validate the package.
    plugin_dir = Path(__file__).parent / "_tmp_plugin_dir"
    if plugin_dir.exists():
        import shutil
        shutil.rmtree(plugin_dir)
    _write_preset(plugin_dir, manifest={"format": "dsh-preset", "version": 1, "id": "round", "name": "Round Trip"})

    st = PluginState(
        id="round", name="Round Trip", kind="agent", source="https://github.com/x/round.git",
        meta={"format": "dsh-preset", "sourceDshVersion": "0.1.0-rc.6"},
    )
    blob = build_dsh_preset(st, plugin_dir)
    assert isinstance(blob, (bytes, bytearray))

    # Re-import the produced package into a temp dir and validate it.
    reimport = Path(__file__).parent / "_tmp_reimport"
    if reimport.exists():
        import shutil
        shutil.rmtree(reimport)
    reimport.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        zf.extractall(reimport)
    report = validate_preset_package(reimport)
    assert report["ok"] is True, report
    assert report["preset_id"] == "round"


def test_preview_install_local_preset():
    root = Path(__file__).parent / "_tmp_preview_src"
    if root.exists():
        import shutil
        shutil.rmtree(root)
    _write_preset(root, manifest={"format": "dsh-preset", "version": 1, "id": "pv", "name": "PV"})
    report = preview_install(str(root), Path(__file__).parent / "_tmp_home")
    assert report["ok"] is True
    assert report["preset"] is True
    assert report["preset_id"] == "pv"


if __name__ == "__main__":
    test_convert_dsh_preset_maps_v1_fields()
    test_validate_refuses_missing_composition()
    test_validate_refuses_bad_format_and_version()
    test_validate_warns_on_secrets_and_absolute_paths()
    test_export_roundtrip_revalidates()
    test_preview_install_local_preset()
    print("ALL PLUGIN PRESET TESTS PASSED")
