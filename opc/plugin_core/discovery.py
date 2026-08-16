"""Web-wide plugin discovery (the "search the whole network" half of the UI).

Uses the public GitHub repository search API (no auth token required; it is
rate-limited but sufficient for interactive discovery). Two provider flavors:

  * ``github`` -- SafeOPC/dst-style plugins: repos that actually contain a
    ``plugin.yaml`` / ``plugin.json`` manifest, or are tagged with a plugin
    topic. These install directly via the git path.
  * ``dsh``    -- DSH Desktop presets: repos containing a ``manifest.json``
    (DSH ``.dshpreset`` source) or tagged ``dsh`` / ``dsh-preset``.

Each returned candidate is normalized into an installable record whose
``source`` is a git URL the installer can clone.
"""

from __future__ import annotations

import json
import urllib.parse
import urllib.request
from typing import Any

_GITHUB_SEARCH_URL = "https://api.github.com/search/repositories"
_USER_AGENT = "SafeOPC-Plugin-Discovery/1.0"

_PROVIDER_QUERIES: dict[str, str] = {
    "github": "(filename:plugin.yaml OR filename:plugin.json OR topic:safeopc-plugin OR topic:opc-plugin)",
    "dsh": "(filename:manifest.json OR topic:dsh OR topic:dsh-preset OR dsh-preset)",
}


def _http_get_json(url: str) -> tuple[dict[str, Any] | None, str | None]:
    try:
        req = urllib.request.Request(
            url, headers={"User-Agent": _USER_AGENT, "Accept": "application/vnd.github+json"}
        )
        with urllib.request.urlopen(req, timeout=20) as resp:  # noqa: S310 - public GitHub API
            return json.loads(resp.read().decode("utf-8")), None
    except urllib.error.HTTPError as exc:
        if exc.code == 403:
            return None, "GitHub API rate limit reached — try again in a minute."
        return None, f"GitHub API error {exc.code}"
    except Exception as exc:  # noqa: BLE001
        return None, f"discovery request failed: {exc}"


def _normalize(item: dict[str, Any], provider: str) -> dict[str, Any]:
    full_name = str(item.get("full_name", "") or "")
    owner = full_name.split("/")[0] if "/" in full_name else ""
    return {
        "id": full_name or str(item.get("name", "")),
        "name": str(item.get("name", "") or full_name),
        "description": str(item.get("description") or "").strip(),
        "source": f"https://github.com/{full_name}.git" if full_name else "",
        "html_url": str(item.get("html_url", "") or ""),
        "homepage": str(item.get("homepage") or ""),
        "stars": int(item.get("stargazers_count") or 0),
        "owner": owner,
        "default_branch": str(item.get("default_branch") or "main"),
        "language": str(item.get("language") or ""),
        "provider": provider,
        "kind": "agent" if provider == "dsh" else "tool",
    }


def discover_plugins(
    query: str,
    provider: str = "github",
    limit: int = 20,
) -> dict[str, Any]:
    """Search the network for installable plugins/presets.

    Returns ``{"candidates": [...], "error": str|None, "provider": str, "query": str}``.
    Never raises — network/API failures surface in ``error`` so the UI can show
    a friendly message.
    """
    query = (query or "").strip()
    provider = (provider or "github").strip().lower()
    if provider not in _PROVIDER_QUERIES:
        provider = "github"
    if not query:
        return {"candidates": [], "error": "empty query", "provider": provider, "query": query}

    qualifier = _PROVIDER_QUERIES[provider]
    q = f"{query} {qualifier}"
    params = urllib.parse.urlencode({"q": q, "sort": "stars", "order": "desc", "per_page": str(limit)})
    url = f"{_GITHUB_SEARCH_URL}?{params}"
    data, err = _http_get_json(url)
    if err is not None:
        return {"candidates": [], "error": err, "provider": provider, "query": query}
    items = (data or {}).get("items", []) or []
    candidates = [_normalize(it, provider) for it in items if it.get("full_name")]
    # De-duplicate by id while preserving order.
    seen: set[str] = set()
    deduped: list[dict[str, Any]] = []
    for c in candidates:
        if c["id"] in seen:
            continue
        seen.add(c["id"])
        deduped.append(c)
    return {"candidates": deduped, "error": None, "provider": provider, "query": query}
