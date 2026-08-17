"""Plugin CLI — ``opc plugin ...`` and the ``dsh`` alias.

Mirrors deepseek-harness' ``dsh plugin add`` surface: install a plugin from a
git URL or local path, list/show, enable/disable, and edit per-plugin config.
All state lives in the profile (``<opc_home>/config/plugins_config.yaml``).
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

import typer

from opc.core.config import get_opc_home
from opc.plugin_core import PluginError, PluginRegistry

plugin_app = typer.Typer(help="Manage SafeOPC plugins (deepseek-harness style).")
dsh_app = typer.Typer(help="DeepSeek-Harness compatible plugin CLI.")


def _registry() -> PluginRegistry:
    home = Path(os.environ.get("OPC_HOME") or get_opc_home())
    r = PluginRegistry(home)
    r.load()
    return r


def _fail(code: str, msg: str) -> None:
    typer.echo(f"error[{code}]: {msg}", err=True)
    raise typer.Exit(code=1)


def _parse_kv(pairs: list[str]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for kv in pairs:
        if "=" not in kv:
            _fail("bad_arg", f"expected key=value, got {kv!r}")
        k, v = kv.split("=", 1)
        try:
            out[k] = json.loads(v)
        except Exception:
            out[k] = v
    return out


@plugin_app.command("add")
def add(
    source: str = typer.Argument(..., help="git URL or local directory path"),
    enable: bool = typer.Option(True, "--enable/--disable", help="Enable after install"),
) -> None:
    """Install a plugin from a git URL or local path."""
    r = _registry()
    try:
        st = r.install(source)
        st.enabled = enable
        r.add(st)
    except PluginError as e:
        _fail(e.code, e.message)
    typer.echo(f"added '{st.id}' v{st.version} ({st.kind}) from {source}")


@plugin_app.command("remove")
def remove(plugin_id: str = typer.Argument(...)) -> None:
    """Uninstall a plugin (removes files + profile entry)."""
    r = _registry()
    try:
        r.remove(plugin_id)
    except PluginError as e:
        _fail(e.code, e.message)
    typer.echo(f"removed '{plugin_id}'")


@plugin_app.command("list")
def list_cmd() -> None:
    """List installed plugins."""
    r = _registry()
    plugins = r.list_plugins()
    if not plugins:
        typer.echo("(no plugins installed)")
        return
    for p in plugins:
        flag = "on " if p.enabled else "off"
        typer.echo(f"[{flag}] {p.id}  v{p.version}  ({p.kind})  {p.description}")


@plugin_app.command("show")
def show(plugin_id: str = typer.Argument(...)) -> None:
    """Show full state + config of one plugin (JSON)."""
    r = _registry()
    p = r.get_plugin(plugin_id)
    if p is None:
        _fail("plugin_not_found", f"Plugin '{plugin_id}' is not installed")
    typer.echo(p.model_dump_json(indent=2))


@plugin_app.command("enable")
def enable(plugin_id: str = typer.Argument(...)) -> None:
    """Enable a plugin."""
    r = _registry()
    try:
        p = r.set_enabled(plugin_id, True)
    except PluginError as e:
        _fail(e.code, e.message)
    typer.echo(f"enabled '{p.id}'")


@plugin_app.command("disable")
def disable(plugin_id: str = typer.Argument(...)) -> None:
    """Disable a plugin."""
    r = _registry()
    try:
        p = r.set_enabled(plugin_id, False)
    except PluginError as e:
        _fail(e.code, e.message)
    typer.echo(f"disabled '{p.id}'")


@plugin_app.command("config")
def config(
    plugin_id: str = typer.Argument(...),
    key_value: list[str] = typer.Argument(None, help="key=value pairs to set (omit to view)"),
) -> None:
    """View or update a plugin's config (JSON values parsed)."""
    r = _registry()
    if not key_value:
        try:
            c = r.get_config(plugin_id)
        except PluginError as e:
            _fail(e.code, e.message)
        typer.echo(json.dumps(c, indent=2, ensure_ascii=False))
        return
    updates = _parse_kv(key_value)
    try:
        p = r.set_config(plugin_id, {**r.get_config(plugin_id), **updates})
    except PluginError as e:
        _fail(e.code, e.message)
    typer.echo(f"updated config for '{p.id}': {json.dumps(p.config, ensure_ascii=False)}")


@plugin_app.command("load")
def load_cmd() -> None:
    """Load all enabled plugins and report registered tools (runtime check)."""
    r = _registry()
    res = r.load_all()
    tools = r.collect_tools()
    loaded = sum(1 for v in res.values() if v.get("loaded"))
    typer.echo(f"loaded {loaded}/{len(res)} plugins; registered tools: {list(tools.keys())}")


def main() -> None:
    plugin_app()


def dsh_main() -> None:
    """``dsh`` binary — exposes ``dsh plugin add|list|...``."""
    dsh_app.add_typer(plugin_app, name="plugin")
    dsh_app()


if __name__ == "__main__":
    main()
