"""SafeOPC ↔ CreatorHub integration adapter.

Exposes:
* :class:`client.CreatorHubClient` — async HTTP client for a CreatorHub sidecar.
* :func:`tools.create_creatorhub_tools` — SafeOPC ``ToolDefinition`` list.
* :mod:`mcp_server` — FastMCP server for native ``mcp_servers`` registration.
* :class:`launcher.CreatorHubLauncher` — venv + config + uvicorn sidecar.

Browser strategy: CreatorHub already prefers the system stable Chrome
(``app/browser/manager.py`` ``_detect_chrome_major``). The launcher installs
dependencies but never runs ``patchright install chromium``, so no browser
binary is bundled.
"""
from .client import (
    CreatorHubAPIError,
    CreatorHubClient,
    CreatorHubConnectionError,
    CreatorHubError,
    CreatorHubTimeoutError,
    DEFAULT_BASE_URL,
)
from .launcher import CreatorHubLauncher
from .tools import create_creatorhub_tools

__all__ = [
    "CreatorHubClient",
    "CreatorHubError",
    "CreatorHubConnectionError",
    "CreatorHubTimeoutError",
    "CreatorHubAPIError",
    "DEFAULT_BASE_URL",
    "CreatorHubLauncher",
    "create_creatorhub_tools",
]
