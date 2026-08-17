"""Plugin management service (Settings UI backend).

Mirrors :class:`MarketService` but operates on the plugin profile owned by
:class:`opc.plugin_core.PluginRegistry`. All mutations persist to
``<opc_home>/config/plugins_config.yaml``.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from loguru import logger
from opc.core.config import get_opc_home
from opc.plugin_core import PluginError, PluginRegistry

from .context import OfficeServiceContext
from .models import ServiceError, ServiceEvent, ServiceResult


def _norm_source(s: str) -> str:
    """Normalize a git/source URL for comparison (lowercase, drop .git + trailing /)."""
    s = (s or "").strip().lower()
    if s.endswith(".git"):
        s = s[:-4]
    return s.rstrip("/")


class PluginService:
    def __init__(self, context: OfficeServiceContext) -> None:
        self.context = context

    def _registry(self) -> PluginRegistry:
        home = getattr(self.context, "opc_home", None) or Path(get_opc_home())
        r = PluginRegistry(home)
        r.load()
        return r

    @staticmethod
    def _translate(e: PluginError) -> ServiceError:
        return ServiceError(e.code, e.message, e.payload)

    @staticmethod
    def _refresh_event(r: PluginRegistry) -> ServiceEvent:
        """Broadcast the full plugin list so every UI client stays in sync."""
        plugins = [p.model_dump() for p in r.list_plugins()]
        return ServiceEvent("plugin_list", {"plugins": plugins})

    async def list(self) -> ServiceResult:
        plugins = [p.model_dump() for p in self._registry().list_plugins()]
        return ServiceResult({"plugins": plugins})

    # ------------------------------------------------------------------
    # Web-wide discovery (the "search the whole network" half of the UI)
    # ------------------------------------------------------------------
    async def discover(self, query: str, provider: str = "github") -> ServiceResult:
        from opc.plugin_core.discovery import discover_plugins

        result = discover_plugins(query, provider=provider)
        # Match by normalized source URL, not manifest id: the discovery
        # candidate id is the repo full_name (e.g. "acme/my-plugin") whereas an
        # installed plugin's id comes from its manifest. Their `source` (the git
        # URL) is the stable join key, so install/remove flips the badge.
        installed_sources = {
            _norm_source(p.source) for p in self._registry().list_plugins() if p.source
        }
        for cand in result.get("candidates", []):
            cand["installed"] = _norm_source(cand.get("source")) in installed_sources
        return ServiceResult(result)

    # ------------------------------------------------------------------
    # Preset lifecycle (faithful port of dsh-desktop .dshpreset handling)
    # ------------------------------------------------------------------
    async def preview(self, source: str) -> ServiceResult:
        """Validate a source without installing it (import-preview step)."""
        from opc.plugin_core.installer import preview_install

        report = preview_install(source, Path(get_opc_home()))
        return ServiceResult(report)

    async def export(self, plugin_id: str) -> ServiceResult:
        """Export an installed agent plugin as a ``.dshpreset`` (downloadable)."""
        import base64

        from opc.plugin_core.preset import build_dsh_preset

        r = self._registry()
        st = r.get_plugin(plugin_id)
        if st is None:
            raise PluginError("plugin_not_found", f"Plugin '{plugin_id}' is not installed")
        plugin_dir = Path(get_opc_home()) / r.plugin_dir_name / plugin_id
        if not plugin_dir.is_dir():
            raise PluginError("plugin_dir_missing", f"Plugin directory missing: {plugin_dir}")
        blob = build_dsh_preset(st, plugin_dir)
        filename = f"{plugin_id}.dshpreset"
        return ServiceResult(
            {
                "plugin_id": plugin_id,
                "filename": filename,
                "size": len(blob),
                "data_base64": base64.b64encode(blob).decode("ascii"),
            }
        )

    # ------------------------------------------------------------------
    # Runtime refresh — re-load plugins into the live tool registry so a
    # freshly installed plugin is executable on the next turn (no restart).
    # ------------------------------------------------------------------
    def _propagate_to_runtime(self) -> int:
        engine = getattr(self.context, "engine", None)
        if engine is None or not hasattr(engine, "refresh_plugin_tools"):
            return 0
        try:
            return int(engine.refresh_plugin_tools() or 0)
        except Exception as exc:  # noqa: BLE001
            logger.warning(f"runtime plugin refresh failed: {exc}")
            return 0

    async def refresh(self) -> ServiceResult:
        r = self._registry()
        # Re-apply the layered override cascade (bundle→preset→profile→home→cli)
        # into the flat profile so the runtime honors preset composition and
        # user overrides, then reload + re-load entries.
        r.sync_effective()
        r.refresh()
        runtime_added = self._propagate_to_runtime()
        return ServiceResult(
            {"ok": True, "action": "plugin_refreshed", "runtime_tools_added": runtime_added},
            events=[self._refresh_event(r)],
        )

    async def add(self, *, source: str, enabled: bool = True) -> ServiceResult:
        r = self._registry()
        try:
            st = r.install(source)
            st.enabled = enabled
            r.add(st)
            # A freshly installed DSH preset composes the runtime via its
            # preset/agent.cordis.yml — re-sync the cascade so composed
            # plugins are enabled/loaded immediately (no restart).
            r.sync_effective()
        except PluginError as e:
            raise self._translate(e)
        # Reload the plugin entry into the live runtime tool registry so the
        # new plugin is immediately usable (install -> refresh -> execute).
        self._propagate_to_runtime()
        return ServiceResult(
            {"ok": True, "action": "plugin_added", "plugin": st.model_dump()},
            events=[self._refresh_event(r)],
        )

    async def remove(self, plugin_id: str) -> ServiceResult:
        r = self._registry()
        try:
            r.remove(plugin_id)
            # A removed agent preset drops its composition from the cascade.
            r.sync_effective()
        except PluginError as e:
            raise self._translate(e)
        return ServiceResult(
            {"ok": True, "action": "plugin_removed", "plugin_id": plugin_id},
            events=[self._refresh_event(r)],
        )

    # ------------------------------------------------------------------
    # Cordis-style layered override cascade (faithful port of
    # cordis.patch.yml layering: bundle/preset/profile/home/cli).
    # ------------------------------------------------------------------
    async def cascade_get(self) -> ServiceResult:
        r = self._registry()
        return ServiceResult(r.resolve_effective())

    async def cascade_patch(self, tree: dict, layer: str = "home") -> ServiceResult:
        """Write an override into the user-writable ``home`` layer.

        Only ``home`` is user-writable; bundle/preset/profile/cli are managed.
        The override is deep-merged into the existing home layer, then the
        cascade is re-synced into the flat profile and the runtime refreshes.
        """
        if layer != "home":
            raise PluginError(
                "cascade_layer_readonly",
                f"Only the 'home' layer is user-writable; '{layer}' is managed.",
            )
        if not isinstance(tree, dict) or "plugins" not in tree:
            raise PluginError(
                "cascade_bad_tree",
                "Override tree must be a dict with a 'plugins' list, e.g. "
                "{'plugins': [{'id': 'x', 'enabled': false}]}.",
            )
        from opc.plugin_core.cascade import CascadeResolver

        r = self._registry()
        resolver = CascadeResolver(Path(get_opc_home()), registry=r)
        resolver.write_home_layer(tree)
        r.sync_effective()
        self._propagate_to_runtime()
        return ServiceResult(
            {"ok": True, "action": "cascade_patched", "cascade": r.resolve_effective()},
            events=[self._refresh_event(r)],
        )

    async def cascade_reset(self, layer: str = "home") -> ServiceResult:
        if layer != "home":
            raise PluginError(
                "cascade_layer_readonly",
                f"Only the 'home' layer is user-writable; '{layer}' is managed.",
            )
        from opc.plugin_core.cascade import CascadeResolver

        r = self._registry()
        resolver = CascadeResolver(Path(get_opc_home()), registry=r)
        resolver.reset_home_layer()
        r.sync_effective()
        self._propagate_to_runtime()
        return ServiceResult(
            {"ok": True, "action": "cascade_reset", "cascade": r.resolve_effective()},
            events=[self._refresh_event(r)],
        )

    async def enable(self, plugin_id: str) -> ServiceResult:
        r = self._registry()
        try:
            p = r.set_enabled(plugin_id, True)
        except PluginError as e:
            raise self._translate(e)
        return ServiceResult(
            {"ok": True, "action": "plugin_enabled", "plugin": p.model_dump()},
            events=[self._refresh_event(r)],
        )

    async def disable(self, plugin_id: str) -> ServiceResult:
        r = self._registry()
        try:
            p = r.set_enabled(plugin_id, False)
        except PluginError as e:
            raise self._translate(e)
        return ServiceResult(
            {"ok": True, "action": "plugin_disabled", "plugin": p.model_dump()},
            events=[self._refresh_event(r)],
        )

    async def get_config(self, plugin_id: str) -> ServiceResult:
        try:
            c = self._registry().get_config(plugin_id)
        except PluginError as e:
            raise self._translate(e)
        # Re-read schema from the manifest so the editor can render fields.
        schema = self._registry().get_plugin(plugin_id)
        config_schema = schema.config_schema if schema else None
        return ServiceResult(
            {"plugin_id": plugin_id, "config": c, "config_schema": config_schema}
        )

    async def set_config(self, plugin_id: str, config: dict[str, Any]) -> ServiceResult:
        r = self._registry()
        try:
            p = r.set_config(plugin_id, config or {})
        except PluginError as e:
            raise self._translate(e)
        return ServiceResult(
            {"ok": True, "action": "plugin_config_updated", "plugin": p.model_dump()},
            events=[self._refresh_event(r)],
        )
