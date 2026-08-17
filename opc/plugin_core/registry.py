"""Plugin registry: the single source of truth for the plugin profile.

Owns ``<opc_home>/config/plugins_config.yaml``. Loads manifests, validates
config against each plugin's ``config_schema``, resolves dependency ordering,
and loads enabled plugin entry points into the runtime.
"""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Any

import yaml

from .errors import PluginError
from .installer import install_plugin
from .loader import clear_plugin_tools, collect_plugin_tools, load_entry
from .manifest import PluginState, PluginsConfig

DEFAULT_CONFIG_NAME = "plugins_config.yaml"


class PluginRegistry:
    def __init__(
        self,
        opc_home: Path,
        plugin_dir_name: str = "plugins",
        config_name: str = DEFAULT_CONFIG_NAME,
    ) -> None:
        self.opc_home = Path(opc_home)
        self.plugin_dir_name = plugin_dir_name
        self.config_path = self.opc_home / "config" / config_name
        self._config = PluginsConfig()

    # ---- persistence ----------------------------------------------------
    def load(self) -> PluginsConfig:
        if self.config_path.exists():
            try:
                with open(self.config_path, encoding="utf-8") as f:
                    data = yaml.safe_load(f) or {}
                self._config = PluginsConfig.model_validate(data)
            except Exception as exc:  # noqa: BLE001 - never crash on a bad profile
                raise PluginError(
                    "profile_corrupt",
                    f"Failed to load plugin profile {self.config_path}: {exc}",
                ) from exc
        return self._config

    def save(self) -> None:
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self._config.plugin_dir = self.plugin_dir_name
        with open(self.config_path, "w", encoding="utf-8") as f:
            yaml.safe_dump(
                self._config.model_dump(),
                f,
                sort_keys=False,
                allow_unicode=True,
            )

    # ---- queries --------------------------------------------------------
    @property
    def config(self) -> PluginsConfig:
        return self._config

    def list_plugins(self) -> list[PluginState]:
        return list(self._config.plugins)

    def get_plugin(self, plugin_id: str) -> PluginState | None:
        return next((p for p in self._config.plugins if p.id == plugin_id), None)

    def _index(self, plugin_id: str) -> int:
        for i, p in enumerate(self._config.plugins):
            if p.id == plugin_id:
                return i
        return -1

    # ---- mutations ------------------------------------------------------
    def add(self, state: PluginState) -> PluginState:
        i = self._index(state.id)
        if i >= 0:
            self._config.plugins[i] = state
        else:
            self._config.plugins.append(state)
        self.save()
        return state

    def install(self, source: str) -> PluginState:
        # Materialize first (writes the plugin dir, resolves the manifest id).
        state = install_plugin(source, self.opc_home, self.plugin_dir_name)
        # Genuine ID collision: a *different* source already owns this id. dsh
        # refuses to overwrite an existing preset identifier -- the operator
        # must choose a new id. A re-pull from the same source is idempotent
        # and allowed. Check before persisting so the profile stays clean.
        norm = lambda s: (s or "").strip().lower().replace(".git", "").rstrip("/")  # noqa: E731
        existing = self.get_plugin(state.id)
        if existing is not None and existing.source and norm(existing.source) != norm(source):
            # Revert the just-written dir; nothing was saved to the profile.
            target = self.opc_home / self.plugin_dir_name / state.id
            if target.exists():
                shutil.rmtree(target, ignore_errors=True)
            raise PluginError(
                "plugin_id_conflict",
                f"A plugin '{state.id}' is already installed from a different "
                f"source ({existing.source}). Choose a different id or remove it first.",
            )
        return self.add(state)

    def remove(self, plugin_id: str) -> None:
        i = self._index(plugin_id)
        if i < 0:
            raise PluginError("plugin_not_found", f"Plugin '{plugin_id}' is not installed")
        self._config.plugins.pop(i)
        self.save()
        target = self.opc_home / self.plugin_dir_name / plugin_id
        if target.exists():
            shutil.rmtree(target, ignore_errors=True)

    def set_enabled(self, plugin_id: str, enabled: bool) -> PluginState:
        p = self.get_plugin(plugin_id)
        if p is None:
            raise PluginError("plugin_not_found", f"Plugin '{plugin_id}' is not installed")
        p.enabled = enabled
        self.save()
        return p

    def get_config(self, plugin_id: str) -> dict[str, Any]:
        p = self.get_plugin(plugin_id)
        if p is None:
            raise PluginError("plugin_not_found", f"Plugin '{plugin_id}' is not installed")
        return dict(p.config)

    def set_config(self, plugin_id: str, config: dict[str, Any]) -> PluginState:
        p = self.get_plugin(plugin_id)
        if p is None:
            raise PluginError("plugin_not_found", f"Plugin '{plugin_id}' is not installed")
        self._validate_config(p, config)
        p.config = dict(config)
        self.save()
        return p

    def _validate_config(self, p: PluginState, config: dict[str, Any]) -> None:
        schema = p.config_schema
        if not schema:
            return
        try:
            import jsonschema  # type: ignore
        except ImportError:
            return  # no validator available; accept as-is
        try:
            jsonschema.validate(instance=config, schema=schema)
        except Exception as exc:  # noqa: BLE001
            raise PluginError(
                "config_invalid",
                f"Plugin '{p.id}' config failed schema validation: {exc}",
                {"errors": str(exc)},
            ) from exc

    # ---- dependency ordering -------------------------------------------
    def resolve_order(self) -> list[PluginState]:
        enabled = [p for p in self._config.plugins if p.enabled]
        by_id = {p.id: p for p in enabled}
        ordered: list[PluginState] = []
        visited: set[str] = set()

        def visit(p: PluginState) -> None:
            if p.id in visited:
                return
            visited.add(p.id)
            for dep in p.dependencies:
                if dep in by_id:
                    visit(by_id[dep])
            ordered.append(p)

        for p in enabled:
            visit(p)
        return ordered

    # ---- loading entries ------------------------------------------------
    def load_all(self) -> dict[str, Any]:
        """Load every enabled plugin (in dependency order) into the runtime."""
        clear_plugin_tools()
        results: dict[str, Any] = {}
        for p in self.resolve_order():
            plugin_dir = self.opc_home / self.plugin_dir_name / p.id
            results[p.id] = load_entry(p, self.opc_home, plugin_dir)
        return results

    def collect_tools(self) -> dict[str, Any]:
        return collect_plugin_tools()

    def refresh(self) -> dict[str, Any]:
        """Reload the profile from disk and re-load every enabled plugin entry.

        Used by the runtime "install → refresh" path so newly installed (or
        enabled/disabled) plugins take effect without a process restart.
        """
        self.load()
        return self.load_all()

    # ---- Cordis-style layered override cascade ---------------------------
    def resolve_effective(self) -> dict:
        """Resolve the layered override cascade (bundle→preset→profile→home→cli).

        Joins the merged cascade tree with the installed plugin universe and
        returns the effective per-plugin enabled/config plus a per-layer trace
        (the CSS-cascade view). See :mod:`opc.plugin_core.cascade`.
        """
        from .cascade import CascadeResolver

        resolver = CascadeResolver(self.opc_home, registry=self)
        return resolver.resolve_effective(self)

    def sync_effective(self) -> dict:
        """Re-resolve the cascade and write resolved enabled/config back into the
        flat profile so the runtime loader honors the overrides.

        The flat profile stays the *installation universe + base settings*; the
        cascade layers are *overrides* layered on top and re-synced here on
        every refresh / install / override change.
        """
        data = self.resolve_effective()
        for eff in data.get("plugins", []):
            if eff.get("missing"):
                continue
            p = self.get_plugin(eff["id"])
            if p is None:
                continue
            p.enabled = bool(eff["enabled"])
            if eff.get("config") is not None:
                p.config = dict(eff["config"])
        self.save()
        return data
