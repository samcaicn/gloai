"""Plugin manifest + profile config models (deepseek-harness style).

A plugin is a self-contained package stored under ``<opc_home>/plugins/<id>/``.
Its ``plugin.yaml`` (or ``plugin.json``) manifest declares metadata, an entry
point, dependencies, requested permissions, and a JSON-Schema for its own
per-plugin configuration. The profile (``<opc_home>/config/plugins_config.yaml``)
records which plugins are installed, enabled, and their stored config overrides.
"""

from __future__ import annotations

import datetime
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


# JSON-Schema describing a valid plugin manifest. The Settings UI uses this to
# render an "add plugin" form and to validate manifests; ``dsh plugin add`` and
# the installer validate with it too.
MANIFEST_JSON_SCHEMA: dict[str, Any] = {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "title": "SafeOPC Plugin Manifest",
    "type": "object",
    "additionalProperties": False,
    "required": ["id"],
    "properties": {
        "id": {
            "type": "string",
            "pattern": "^[a-z0-9][a-z0-9_-]*$",
            "description": "Unique plugin id (used as install directory name).",
        },
        "name": {"type": "string", "description": "Human-readable name."},
        "version": {"type": "string", "description": "Semver-ish version string."},
        "description": {"type": "string"},
        "author": {"type": "string"},
        "homepage": {"type": "string"},
        "license": {"type": "string"},
        "kind": {
            "type": "string",
            "enum": ["tool", "integration", "agent", "skill", "ui"],
            "description": "What the plugin extends.",
        },
        "entry": {
            "type": "string",
            "description": "Importable callable: 'module.path:attr' (default attr 'register').",
        },
        "dependencies": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Plugin ids this plugin depends on (load order is resolved).",
        },
        "permissions": {
            "type": "object",
            "description": "Capability requests, e.g. network/filesystem/exec.",
            "properties": {
                "network": {"type": "boolean"},
                "filesystem": {"type": "array", "items": {"type": "string"}},
                "exec": {"type": "boolean"},
                "env": {"type": "array", "items": {"type": "string"}},
            },
        },
        "config_schema": {
            "type": "object",
            "description": "JSON-Schema (draft-07) for the plugin's own config section.",
        },
    },
}


class PluginManifest(BaseModel):
    """Validated on-disk manifest of a single plugin."""

    model_config = ConfigDict(extra="ignore")

    id: str = Field(..., pattern=r"^[a-z0-9][a-z0-9_-]*$")
    name: str = ""
    version: str = "0.0.0"
    description: str = ""
    author: str = ""
    homepage: str = ""
    license: str = ""
    kind: str = Field(default="tool", pattern=r"^(tool|integration|agent|skill|ui)$")
    entry: str = ""
    dependencies: list[str] = Field(default_factory=list)
    permissions: dict[str, Any] = Field(default_factory=dict)
    config_schema: dict[str, Any] = Field(default_factory=dict)


class PluginState(BaseModel):
    """Installed-plugin record persisted in the profile (plugins_config.yaml)."""

    model_config = ConfigDict(extra="ignore")

    id: str
    name: str = ""
    version: str = "0.0.0"
    enabled: bool = True
    source: str = ""  # install source (git url / local path)
    kind: str = "tool"
    entry: str = ""
    description: str = ""
    author: str = ""
    homepage: str = ""
    license: str = ""
    dependencies: list[str] = Field(default_factory=list)
    permissions: dict[str, Any] = Field(default_factory=dict)
    config_schema: dict[str, Any] = Field(default_factory=dict)
    config: dict[str, Any] = Field(default_factory=dict)
    installed_at: str = ""

    @classmethod
    def from_manifest(cls, manifest: PluginManifest, *, source: str) -> "PluginState":
        return cls(
            id=manifest.id,
            name=manifest.name or manifest.id,
            version=manifest.version,
            enabled=True,
            source=source,
            kind=manifest.kind,
            entry=manifest.entry,
            description=manifest.description,
            author=manifest.author,
            homepage=manifest.homepage,
            license=manifest.license,
            dependencies=list(manifest.dependencies),
            permissions=dict(manifest.permissions),
            config_schema=dict(manifest.config_schema),
            installed_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        )


class PluginsConfig(BaseModel):
    """The plugin profile: global settings + the installed plugin list."""

    model_config = ConfigDict(extra="ignore")

    auto_load: bool = True
    plugin_dir: str = "plugins"  # relative to opc_home
    plugins: list[PluginState] = Field(default_factory=list)
