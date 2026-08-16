"""Plugin entry-point loading + tool registration.

When a plugin's ``entry`` (``module:attr``) is loaded, we call
``attr(ctx)`` where ``ctx`` is a :class:`PluginContext`. The plugin uses
``ctx.register_tool(...)`` to expose capabilities that the runtime merges into
its layer-4 tool registry (the "everything is a plugin" model).
"""

from __future__ import annotations

import importlib
import sys
import threading
from pathlib import Path
from typing import Any, Callable

from .manifest import PluginState

# module-level aggregator: plugin_id -> {callable, spec}
PLUGIN_TOOLS: dict[str, dict[str, Any]] = {}
_LOCK = threading.Lock()


class PluginContext:
    """Handle passed to a plugin's ``register(ctx)`` callable."""

    def __init__(self, opc_home: Path, state: PluginState, config: dict[str, Any]) -> None:
        self.opc_home = Path(opc_home)
        self.state = state
        self.config = config

    def register_tool(
        self,
        name: str,
        fn: Callable,
        spec: dict[str, Any] | None = None,
    ) -> None:
        """Register a callable as a runtime tool under ``name``."""
        spec = dict(spec or {})
        spec.setdefault("name", name)
        spec.setdefault("plugin_id", getattr(self.state, "id", ""))
        with _LOCK:
            PLUGIN_TOOLS[name] = {
                "callable": fn,
                "spec": spec,
                "plugin_id": getattr(self.state, "id", ""),
            }


def collect_plugin_tools() -> dict[str, dict[str, Any]]:
    """Return the merged plugin tool table ``{name: {callable, spec}}``."""
    with _LOCK:
        return {
            name: {"callable": v["callable"], "spec": v["spec"]}
            for name, v in PLUGIN_TOOLS.items()
        }


def clear_plugin_tools() -> None:
    with _LOCK:
        PLUGIN_TOOLS.clear()


def load_entry(
    state: PluginState,
    opc_home: Path,
    plugin_dir: Path | None = None,
) -> dict[str, Any]:
    """Import and execute a single plugin's entry point.

    Returns a result dict: ``{"loaded": bool, "reason": str}``.
    """
    if not state.entry:
        return {"loaded": False, "reason": "no_entry"}
    entry = state.entry.strip()
    if ":" in entry:
        module_name, attr = entry.split(":", 1)
    else:
        module_name, attr = entry, "register"
    module_name = module_name.strip()
    attr = attr.strip()
    # Make the plugin directory importable so `entry: module:attr` resolves
    # even when the plugin is a plain directory copied under <opc_home>/plugins.
    if plugin_dir is not None and str(plugin_dir) not in sys.path:
        sys.path.insert(0, str(plugin_dir))
    try:
        module = importlib.import_module(module_name)
        fn = getattr(module, attr, None)
        if fn is None:
            return {"loaded": False, "reason": f"entry_attr_missing:{attr}"}
        ctx = PluginContext(opc_home=opc_home, state=state, config=dict(state.config))
        fn(ctx)
        return {"loaded": True}
    except Exception as exc:  # noqa: BLE001 - surface as a load failure
        return {"loaded": False, "reason": f"{type(exc).__name__}: {exc}"}
