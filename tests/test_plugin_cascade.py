"""Offline tests for the Cordis-style layered override cascade.

Covers deep_merge semantics, Cordis tree normalization (name->id, patch
lists), the bundle/preset/profile/home/cli layer resolution, preset
composition from an installed agent plugin, the home-layer override writer,
and registry.resolve_effective / sync_effective.
"""

import tempfile
from pathlib import Path

from opc.plugin_core.cascade import (
    CascadeResolver,
    _normalize_cordis,
    deep_merge,
)
from opc.plugin_core.manifest import PluginState
from opc.plugin_core.registry import PluginRegistry


def _state(pid, *, kind="tool", enabled=True, config=None):
    return PluginState(
        id=pid,
        name=pid,
        version="1.0.0",
        enabled=enabled,
        source=f"https://example.com/{pid}.git",
        kind=kind,
        config=config or {},
    )


def _make_home():
    home = Path(tempfile.mkdtemp(prefix="cascade_test_"))
    reg = PluginRegistry(home)
    for st in (
        _state("core-a", enabled=True, config={"timeout": 10}),
        _state("core-b", enabled=False),
        _state("preset-x", kind="agent", enabled=True),
    ):
        reg.add(st)
    # An agent preset that composes the runtime via its Cordis declaration.
    cordis_dir = home / "plugins" / "preset-x" / "preset"
    cordis_dir.mkdir(parents=True)
    (cordis_dir / "agent.cordis.yml").write_text(
        "plugins:\n"
        "  - name: core-b\n"
        "    enabled: true\n"
        "  - name: core-a\n"
        "    config:\n"
        "      timeout: 30\n"
        "      mode: fast\n",
        encoding="utf-8",
    )
    return home


def test_deep_merge_dict_and_scalar():
    assert deep_merge({"a": {"x": 1}}, {"a": {"y": 2}}) == {"a": {"x": 1, "y": 2}}
    assert deep_merge({"x": 1}, {"x": 2}) == {"x": 2}


def test_deep_merge_keyed_list():
    base = {"plugins": [{"id": "a", "v": 1}, {"id": "b", "v": 2}]}
    patch = {"plugins": [{"id": "a", "v": 9}]}
    assert deep_merge(base, patch) == {
        "plugins": [{"id": "a", "v": 9}, {"id": "b", "v": 2}]
    }


def test_normalize_cordis_name_to_id():
    assert _normalize_cordis({"plugins": [{"name": "foo"}]}) == {
        "plugins": [{"id": "foo"}]
    }


def test_normalize_cordis_patch_list():
    out = _normalize_cordis([{"value": {"plugins": [{"id": "bar", "enabled": True}]}}])
    assert out == {"plugins": [{"id": "bar", "enabled": True}]}


def test_resolve_effective_preset_composition():
    home = _make_home()
    reg = PluginRegistry(home)
    reg.load()
    data = reg.resolve_effective()

    by_id = {p["id"]: p for p in data["plugins"]}

    # core-a: base config merged with preset config; enabled untouched.
    a = by_id["core-a"]
    assert a["enabled"] is True
    assert a["config"]["timeout"] == 30 and a["config"]["mode"] == "fast"
    assert a["overridden"] is True
    assert any(tr["layer"] == "preset" for tr in a["trace"])

    # core-b: preset enables it (base disabled).
    b = by_id["core-b"]
    assert b["enabled"] is True
    assert b["overridden"] is True

    # preset-x: agent plugin, not referenced by any cascade layer.
    x = by_id["preset-x"]
    assert x["enabled"] is True
    assert x["overridden"] is False

    # Layer stack present and ordered.
    names = [l["name"] for l in data["layers"]]
    assert names == ["bundle", "preset", "profile", "home", "cli"]


def test_home_override_wins_and_missing_flagged():
    home = _make_home()
    reg = PluginRegistry(home)
    reg.load()
    resolver = CascadeResolver(home, registry=reg)
    # home layer disables core-a and introduces a not-installed plugin.
    resolver.write_home_layer(
        {"plugins": [{"id": "core-a", "enabled": False}, {"id": "ghost", "enabled": True}]}
    )
    data = reg.resolve_effective()
    by_id = {p["id"]: p for p in data["plugins"]}
    assert by_id["core-a"]["enabled"] is False  # home beats preset/base
    assert by_id["ghost"]["missing"] is True
    assert by_id["ghost"]["enabled"] is True


def test_reset_home_layer():
    home = _make_home()
    reg = PluginRegistry(home)
    reg.load()
    resolver = CascadeResolver(home, registry=reg)
    resolver.write_home_layer({"plugins": [{"id": "core-a", "enabled": False}]})
    assert (home / "config" / "plugins_override.yaml").exists()
    resolver.reset_home_layer()
    assert not (home / "config" / "plugins_override.yaml").exists()
    # After reset, core-a returns to its base enabled state.
    assert reg.resolve_effective()["plugins"][0]["id"]  # sanity
    by_id = {p["id"]: p for p in reg.resolve_effective()["plugins"]}
    assert by_id["core-a"]["enabled"] is True


def test_sync_effective_writes_back():
    home = _make_home()
    reg = PluginRegistry(home)
    reg.load()
    resolver = CascadeResolver(home, registry=reg)
    resolver.write_home_layer({"plugins": [{"id": "core-a", "enabled": False}]})
    reg.sync_effective()
    # The flat profile now reflects the override.
    assert reg.get_plugin("core-a").enabled is False


if __name__ == "__main__":
    test_deep_merge_dict_and_scalar()
    test_deep_merge_keyed_list()
    test_normalize_cordis_name_to_id()
    test_normalize_cordis_patch_list()
    test_resolve_effective_preset_composition()
    test_home_override_wins_and_missing_flagged()
    test_reset_home_layer()
    test_sync_effective_writes_back()
    print("ALL CASCADE TESTS PASSED")
