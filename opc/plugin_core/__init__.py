"""SafeOPC plugin core — deepseek-harness style plugin mechanism.

A plugin is a self-contained package under ``<opc_home>/plugins/<id>/`` with a
``plugin.yaml``/``plugin.json`` manifest. The profile
(``<opc_home>/config/plugins_config.yaml``) records installed plugins, their
enabled flag, and per-plugin config. ``dsh plugin add <source>`` installs one.
"""

from .errors import PluginError
from .installer import install_plugin
from .loader import PluginContext, clear_plugin_tools, collect_plugin_tools, load_entry
from .manifest import MANIFEST_JSON_SCHEMA, PluginManifest, PluginState, PluginsConfig
from .registry import DEFAULT_CONFIG_NAME, PluginRegistry

__all__ = [
    "PluginError",
    "PluginManifest",
    "PluginState",
    "PluginsConfig",
    "PluginRegistry",
    "PluginContext",
    "install_plugin",
    "load_entry",
    "collect_plugin_tools",
    "clear_plugin_tools",
    "MANIFEST_JSON_SCHEMA",
    "DEFAULT_CONFIG_NAME",
]
