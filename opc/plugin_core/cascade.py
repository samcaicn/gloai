"""Cordis-style layered override cascade ("everything is a plugin").

Faithful port of DeepSeek-Harness ``cordis.patch.yml`` layering. In DSH the
plugin/agent composition is declared in ``cordis.yml`` and then *layered*
overrides are applied like a CSS cascade across:

    bundle  ->  preset  ->  profile  ->  home  ->  cli

where each later layer wins. Each layer is a YAML tree of the same shape as
the effective config::

    plugins:
      - id: some-plugin          # Cordis uses `name`; we accept both
        enabled: true
        config:
          key: value

Merging rules (mirroring Cordis patch semantics):

* dicts recurse; later-layer keys override earlier ones, nested dicts merge.
* lists of plugin entries are keyed by ``id``/``name`` and merged by key
  (a higher layer only overrides the fields it actually declares).
* scalars / unkeyed lists: the higher layer replaces the lower one.
* A plugin may be *disabled* from any layer by setting ``enabled: false``.

The ``preset`` layer is auto-derived from installed ``agent`` plugins: each
installed DSH preset carries ``preset/agent.cordis.yml`` whose plugin lines
compose the runtime — so installing a preset *composes* the plugin set without
editing anything by hand (the DSH "composition" model).

The ``home`` layer (``<opc_home>/config/plugins_override.yaml``) is the
user-writable surface exposed by the UI; ``cascade_patch`` writes to it.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from .errors import PluginError

# Priority order: earlier = base, later = wins.
LAYER_ORDER = ["bundle", "preset", "profile", "home", "cli"]


def _load_yaml(path: Path) -> Any:
    """Read a YAML layer file; return {} / [] when missing (non-fatal)."""
    if not path or not path.exists():
        return {}
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - a corrupt layer must not crash boot
        raise PluginError(
            "cascade_layer_corrupt",
            f"Failed to load cascade layer {path}: {exc}",
        ) from exc
    if data is None:
        return {}
    if not isinstance(data, (dict, list)):
        return {}
    return data


def _plugin_key(el: Any) -> Any:
    """Extract the join key (id or name) from a plugin-list entry."""
    if isinstance(el, dict):
        return el.get("id") or el.get("name")
    return None


def _merge_list(base: list, patch: list) -> list:
    """Keyed merge for plugin-like lists; plain replace otherwise."""
    base_keyed = {_plugin_key(e) for e in base if _plugin_key(e) is not None}
    patch_keyed = {_plugin_key(e) for e in patch if _plugin_key(e) is not None}
    if base_keyed or patch_keyed:
        merged: dict[Any, Any] = {
            _plugin_key(e): e for e in base if _plugin_key(e) is not None
        }
        for e in patch:
            k = _plugin_key(e)
            if k is None:
                continue
            merged[k] = deep_merge(merged[k], e) if k in merged else e
        return list(merged.values())
    return list(patch)


def deep_merge(base: Any, patch: Any) -> Any:
    """Recursive deep-merge; later (patch) wins on conflict.

    dicts recurse, plugin-lists merge by key, everything else is replaced.
    """
    if isinstance(base, dict) and isinstance(patch, dict):
        out = dict(base)
        for k, v in patch.items():
            out[k] = deep_merge(out[k], v) if k in out else v
        return out
    if isinstance(base, list) and isinstance(patch, list):
        return _merge_list(base, patch)
    return patch


def _normalize_cordis(data: Any) -> dict:
    """Normalize a Cordis doc (tree form or patch list) into a merge tree.

    Accepts ``{plugins: [...]}`` directly, or a patch list
    ``[{apply: {...}, value: {...}}, ...]`` (DSH ``cordis.patch.yml`` shape).
    Maps Cordis ``name`` -> our ``id``.
    """
    if isinstance(data, list):
        tree: dict[str, Any] = {"plugins": []}
        for item in data:
            if not isinstance(item, dict):
                continue
            value = item.get("value", item)
            if not isinstance(value, dict):
                continue
            tree = deep_merge(tree, _normalize_cordis(value))
        return tree
    if not isinstance(data, dict):
        return {"plugins": []}
    plugins = data.get("plugins")
    if not isinstance(plugins, list):
        return {"plugins": []}
    normed = []
    for p in plugins:
        if not isinstance(p, dict):
            continue
        entry = dict(p)
        if "name" in entry and "id" not in entry:
            entry["id"] = entry.pop("name")
        normed.append(entry)
    return {"plugins": normed}


@dataclass
class CascadeLayer:
    name: str
    source: str
    tree: dict
    priority: int = 0


class CascadeResolver:
    """Loads the layered config and resolves the effective plugin set."""

    def __init__(
        self,
        opc_home: Path,
        registry: Any = None,
        cli_overrides: dict | None = None,
    ) -> None:
        self.opc_home = Path(opc_home)
        self._registry = registry
        self.cli_overrides = cli_overrides or {}
        self._bundle_base = Path(__file__).parent / "data" / "bundle.cordis.yml"
        self._plugins_dir = self.opc_home / "plugins"

    # ---- layer loaders ---------------------------------------------------
    def _collect_preset_tree(self) -> dict:
        """Compose the ``preset`` layer from installed agent plugins."""
        tree: dict[str, Any] = {"plugins": []}
        if self._registry is None:
            return tree
        agent_plugins = [p for p in self._registry.list_plugins() if p.kind == "agent"]
        for p in sorted(agent_plugins, key=lambda x: x.id):
            cordis = self._plugins_dir / p.id / "preset" / "agent.cordis.yml"
            if not cordis.exists():
                continue
            tree = deep_merge(tree, _normalize_cordis(_load_yaml(cordis)))
        return tree

    def load_layers(self) -> list[CascadeLayer]:
        layers: list[CascadeLayer] = []

        # bundle: shipped defaults (may be empty).
        layers.append(
            CascadeLayer(
                name="bundle",
                source=str(self._bundle_base),
                tree=_normalize_cordis(_load_yaml(self._bundle_base)),
            )
        )

        # preset: auto-composed from installed agent presets.
        layers.append(
            CascadeLayer(
                name="preset",
                source="installed agent presets (preset/agent.cordis.yml)",
                tree=self._collect_preset_tree(),
            )
        )

        # profile: DSH-style cordis.patch.yml (tree or patch list).
        profile_path = self.opc_home / "config" / "cordis.patch.yml"
        layers.append(
            CascadeLayer(
                name="profile",
                source=str(profile_path),
                tree=_normalize_cordis(_load_yaml(profile_path)),
            )
        )

        # home: user-writable overrides.
        home_path = self.opc_home / "config" / "plugins_override.yaml"
        layers.append(
            CascadeLayer(
                name="home",
                source=str(home_path),
                tree=_normalize_cordis(_load_yaml(home_path)),
            )
        )

        # cli: runtime session overrides (in-memory).
        layers.append(
            CascadeLayer(
                name="cli",
                source="runtime session overrides",
                tree=self.cli_overrides,
            )
        )
        return layers

    # ---- resolution ------------------------------------------------------
    def resolve(self) -> tuple[dict, list[CascadeLayer]]:
        """Merge all layers in LAYER_ORDER -> effective tree + raw layers."""
        layers = self.load_layers()
        merged: dict[str, Any] = {}
        for name in LAYER_ORDER:
            layer = next((l for l in layers if l.name == name), None)
            if layer and layer.tree:
                merged = deep_merge(merged, layer.tree)
        return merged, layers

    def resolve_effective(self, registry: Any) -> dict:
        """Join the merged cascade tree with the installed plugin universe.

        Returns the layers, the merged tree, and the effective per-plugin
        resolution including a ``trace`` of which layer last set each field
        (the CSS-cascade view), plus ``missing`` for plugins the cascade wants
        but are not installed.
        """
        merged, layers = self.resolve()
        installed = {p.id: p for p in registry.list_plugins()}

        # Per-plugin contribution trace across layers (base -> top).
        traced: dict[str, list[dict]] = {}
        for name in LAYER_ORDER:
            layer = next((l for l in layers if l.name == name), None)
            if not layer:
                continue
            for entry in layer.tree.get("plugins", []) or []:
                pid = entry.get("id") or entry.get("name")
                if not pid:
                    continue
                eff = traced.setdefault(pid, [])
                eff.append(
                    {
                        "layer": name,
                        "enabled": entry.get("enabled"),
                        "config": entry.get("config"),
                    }
                )

        out: list[dict] = []
        for p in registry.list_plugins():
            pid = p.id
            entry = next(
                (
                    e
                    for e in merged.get("plugins", []) or []
                    if (e.get("id") or e.get("name")) == pid
                ),
                None,
            )
            enabled = p.enabled
            config = dict(p.config)
            if entry:
                if entry.get("enabled") is not None:
                    enabled = bool(entry["enabled"])
                if entry.get("config"):
                    config = deep_merge(config, entry["config"])
            out.append(
                {
                    "id": pid,
                    "name": p.name,
                    "version": p.version,
                    "kind": p.kind,
                    "source": p.source,
                    "enabled": enabled,
                    "config": config,
                    "overridden": bool(traced.get(pid)),
                    "trace": traced.get(pid, []),
                    "missing": False,
                }
            )

        # Plugins the cascade wants but are not installed -> missing.
        for e in merged.get("plugins", []) or []:
            pid = e.get("id") or e.get("name")
            if not pid or pid in installed:
                continue
            out.append(
                {
                    "id": pid,
                    "name": pid,
                    "version": "",
                    "kind": e.get("kind", ""),
                    "source": e.get("source", ""),
                    "enabled": bool(e.get("enabled", False)),
                    "config": e.get("config", {}) or {},
                    "overridden": True,
                    "trace": traced.get(pid, []),
                    "missing": True,
                }
            )

        return {
            "layers": [
                {"name": l.name, "source": l.source, "tree": l.tree} for l in layers
            ],
            "effective": merged,
            "plugins": out,
        }

    # ---- mutations (home layer is the user-writable surface) --------------
    def write_home_layer(self, tree: dict) -> None:
        """Merge ``tree`` into the home override layer and persist it."""
        home_path = self.opc_home / "config" / "plugins_override.yaml"
        home_path.parent.mkdir(parents=True, exist_ok=True)
        existing = _load_yaml(home_path) or {}
        merged = deep_merge(_normalize_cordis(existing), _normalize_cordis(tree))
        home_path.write_text(
            yaml.safe_dump(merged, sort_keys=False, allow_unicode=True),
            encoding="utf-8",
        )

    def reset_home_layer(self) -> None:
        home_path = self.opc_home / "config" / "plugins_override.yaml"
        if home_path.exists():
            home_path.unlink()
