"""Tests for the CreatorHub built-in skill integration.

Covers: SKILL.md parsing (always-on built-in), the skill installer (symlink +
bin shim), and the CLI config resolution / ``opc-creatorhub config`` output.
Does NOT start the real sidecar.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from opc.layer5_memory.skill_library import SkillLibrary
from opc.layer3_agent import skill_installer
from opc.skills_assets import creatorhub as skill_pkg  # noqa: F401  (ensures package importable)


SKILL_MD = Path(__file__).resolve().parents[1] / "opc" / "skills_assets" / "creatorhub" / "SKILL.md"
SCHEMA = Path(__file__).resolve().parents[1] / "opc" / "skills_assets" / "creatorhub" / "config.schema.json"


def test_skill_md_is_always_on_builtin():
    skill = SkillLibrary(Path(".pytest_opc_home"))._parse_skill_file(SKILL_MD)
    assert skill.name == "creatorhub"
    assert skill.always is True
    assert skill.modes == []  # visible everywhere
    assert "opc-creatorhub open" in skill.content


def test_config_schema_is_valid_json_with_expected_keys():
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    props = schema["properties"]
    for key in (
        "host",
        "port",
        "data_root",
        "platform",
        "xhs_browser_mode",
        "browser",
        "headless",
        "auto_launch",
        "open_page",
        "auto_stop_on_exit",
        "log_level",
    ):
        assert key in props
    assert props["platform"]["enum"] == ["xhs"]
    assert props["xhs_browser_mode"]["enum"] == ["auto", "cdp"]
    assert props["log_level"]["enum"] == ["DEBUG", "INFO", "WARNING", "ERROR"]


def test_installer_symlinks_skill_and_bin(tmp_path):
    home = tmp_path / "agent_home"
    skill_dir = skill_installer.install_creatorhub_skill(home)
    assert (skill_dir / "SKILL.md").exists()
    assert (skill_dir / "config.default.yaml").exists()
    assert (skill_dir / "config.schema.json").exists()

    bin_dir = skill_installer.ensure_creatorhub_bin(tmp_path)
    assert (bin_dir / "opc-creatorhub").exists()
    # Windows also gets a .cmd shim
    import os

    if os.name == "nt":
        assert (bin_dir / "opc-creatorhub.cmd").exists()


def test_resolve_config_merges_defaults_and_overrides(tmp_path):
    from opc.cli_creatorhub import resolve_config

    cfg = resolve_config(tmp_path, overrides={"port": 8100, "open_page": False})
    assert cfg["port"] == 8100
    assert cfg["open_page"] is False
    assert cfg["host"] == "127.0.0.1"
    assert cfg["platform"] == "xhs"
    # empty data_root resolves to an opc_home-relative default
    assert str(cfg["data_root"]) == str(tmp_path / "integrations" / "creatorhub")


def test_resolve_config_reads_user_file(tmp_path):
    from opc.cli_creatorhub import resolve_config

    user_cfg = tmp_path / "config" / "creatorhub.yaml"
    user_cfg.parent.mkdir(parents=True, exist_ok=True)
    user_cfg.write_text("port: 9999\nbrowser: chrome\n", encoding="utf-8")

    cfg = resolve_config(tmp_path)
    assert cfg["port"] == 9999
    assert cfg["browser"] == "chrome"
    # untouched keys keep their defaults
    assert cfg["auto_launch"] is True


def test_cli_config_command_prints_effective_config(tmp_path, capsys, monkeypatch):
    import opc.cli_creatorhub as mod

    monkeypatch.setattr(mod, "get_opc_home", lambda: tmp_path)
    rc = mod.main(["config"])
    assert rc == 0
    out = capsys.readouterr().out
    parsed = json.loads(out)
    assert parsed["port"] == 8000
    assert parsed["platform"] == "xhs"


def test_discover_office_ui_port_prefers_port_file(tmp_path, monkeypatch):
    import opc.cli_creatorhub as mod

    monkeypatch.setattr(mod, "get_opc_home", lambda: tmp_path)
    # 1) explicit port file wins
    (tmp_path / "office_ui.port").write_text("9123", encoding="utf-8")
    assert mod._discover_office_ui_port(tmp_path) == 9123

    # 2) no file -> falls back to SAFEOPC_PORT env
    (tmp_path / "office_ui.port").unlink()
    monkeypatch.setenv("SAFEOPC_PORT", "7777")
    assert mod._discover_office_ui_port(tmp_path) == 7777

    # 3) neither -> default 8765
    monkeypatch.delenv("SAFEOPC_PORT", raising=False)
    assert mod._discover_office_ui_port(tmp_path) == 8765


def test_open_in_app_browser_returns_false_when_no_server(tmp_path, monkeypatch):
    import opc.cli_creatorhub as mod

    monkeypatch.setattr(mod, "get_opc_home", lambda: tmp_path)
    # No office_ui.port and nothing listening -> graceful fallback (False).
    assert mod._open_in_app_browser("http://127.0.0.1:8000", "CreatorHub", tmp_path) is False

