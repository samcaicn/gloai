"""CreatorHub HTTP client.

Thin async wrapper around the CreatorHub FastAPI service
(``integrations/creatorhub``, run as a sidecar). It only depends on
``httpx`` and never imports Patchright / FastAPI, so it is safe to ship
inside the SafeOPC package and to unit-test against a stub server.

All methods return the parsed JSON body (``dict`` or ``list``). Network
failures and non-2xx responses are turned into typed exceptions so the
caller (SafeOPC tool layer) can surface them cleanly.
"""
from __future__ import annotations

from typing import Any, Mapping, Optional

import httpx

DEFAULT_BASE_URL = "http://127.0.0.1:8000"
DEFAULT_TIMEOUT = 30.0


class CreatorHubError(Exception):
    """Base class for all adapter errors."""

    def __init__(self, message: str, *, status: Optional[int] = None, detail: Any = None):
        super().__init__(message)
        self.status = status
        self.detail = detail


class CreatorHubConnectionError(CreatorHubError):
    """Server unreachable / refused / DNS failure (transport level)."""


class CreatorHubTimeoutError(CreatorHubError):
    """Request exceeded the configured timeout."""


class CreatorHubAPIError(CreatorHubError):
    """Server responded with a non-2xx status."""


def _coerce_base_url(base_url: str) -> str:
    return base_url.rstrip("/")


class CreatorHubClient:
    """Async client for a running CreatorHub sidecar."""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
        token: Optional[str] = None,
    ) -> None:
        self.base_url = _coerce_base_url(base_url)
        self.timeout = float(timeout)
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=httpx.Timeout(self.timeout),
            headers=headers,
            follow_redirects=True,
        )

    # ── lifecycle ───────────────────────────────────────────────
    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> "CreatorHubClient":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()

    # ── core request ────────────────────────────────────────────
    async def _request(
        self,
        method: str,
        path: str,
        *,
        params: Optional[Mapping[str, Any]] = None,
        json: Optional[Any] = None,
    ) -> Any:
        try:
            resp = await self._client.request(method, path, params=params, json=json)
        except (httpx.ConnectError, httpx.ConnectTimeout) as exc:
            # A refused/dead port surfaces as ConnectTimeout on some platforms;
            # it is still a connection failure, not a request timeout.
            raise CreatorHubConnectionError(
                f"无法连接 CreatorHub（{self.base_url}）：{exc}"
            ) from exc
        except httpx.TimeoutException as exc:
            raise CreatorHubTimeoutError(
                f"请求 CreatorHub 超时（{path}）：{exc}"
            ) from exc
        except httpx.HTTPError as exc:
            raise CreatorHubConnectionError(
                f"请求 CreatorHub 失败（{path}）：{exc}"
            ) from exc

        if resp.status_code >= 400:
            raise CreatorHubAPIError(
                f"CreatorHub 返回 HTTP {resp.status_code}：{path}",
                status=resp.status_code,
                detail=self._safe_text(resp),
            )

        if resp.headers.get("content-type", "").startswith("application/json"):
            try:
                return resp.json()
            except ValueError:
                return {"status_code": resp.status_code, "text": resp.text[:500]}
        # Non-JSON (e.g. xlsx reports) — surface raw text + status.
        return {"status_code": resp.status_code, "text": resp.text[:500]}

    @staticmethod
    def _safe_text(resp: httpx.Response) -> str:
        try:
            return resp.text[:1000]
        except Exception:  # pragma: no cover - defensive
            return ""

    # ── health / liveness ───────────────────────────────────────
    async def health(self) -> dict:
        return await self._request("GET", "/health")

    # ── accounts ────────────────────────────────────────────────
    async def list_accounts(self, platform: Optional[str] = None) -> list:
        data = await self._request("GET", "/api/accounts", params={"platform": platform} if platform else None)
        if isinstance(data, list):
            return data
        return data.get("accounts", []) if isinstance(data, dict) else []

    async def account_environment(self, account_id: int) -> dict:
        return await self._request("GET", f"/api/accounts/{account_id}/environment")

    # ── share-link download (no login / no browser required) ────
    async def parse_share_links(self, share_text: str, limit: int = 10) -> dict:
        return await self._request(
            "POST", "/api/share-download/links",
            json={"share_text": share_text, "limit": max(1, min(int(limit), 20))},
        )

    async def share_download(
        self,
        share_text: str,
        *,
        link_index: int = 0,
        all_links: bool = False,
        max_filesize_mb: int = 0,
        output_dir: Optional[str] = None,
    ) -> dict:
        return await self._request(
            "POST", "/api/share-download",
            json={
                "share_text": share_text,
                "link_index": link_index,
                "all_links": all_links,
                "max_filesize_mb": max_filesize_mb,
                "output_dir": output_dir or "",
            },
        )

    async def share_download_history(self) -> list:
        data = await self._request("GET", "/api/share-download/history")
        if isinstance(data, list):
            return data
        return data.get("records", []) if isinstance(data, dict) else []

    # ── collections (采集任务) ───────────────────────────────────
    async def list_collections(self) -> list:
        data = await self._request("GET", "/api/collections")
        if isinstance(data, list):
            return data
        return data.get("jobs", []) if isinstance(data, dict) else []

    async def create_collection(self, **kwargs: Any) -> dict:
        return await self._request("POST", "/api/collections", json=kwargs)

    async def get_collection(self, job_id: str) -> dict:
        return await self._request("GET", f"/api/collections/{job_id}")

    async def list_collection_contents(self, job_id: str) -> list:
        data = await self._request("GET", f"/api/collections/{job_id}/contents")
        if isinstance(data, list):
            return data
        return data.get("contents", []) if isinstance(data, dict) else []

    async def cancel_collection(self, job_id: str) -> dict:
        return await self._request("POST", f"/api/collections/{job_id}/cancel")

    # ── monitors (监控目标) ─────────────────────────────────────
    async def list_monitors(self) -> list:
        data = await self._request("GET", "/api/monitors")
        if isinstance(data, list):
            return data
        return data.get("targets", []) if isinstance(data, dict) else []

    async def create_monitor(self, **kwargs: Any) -> dict:
        return await self._request("POST", "/api/monitors", json=kwargs)

    async def get_monitor(self, tid: str) -> dict:
        return await self._request("GET", f"/api/monitors/{tid}")

    # ── contents (已采集作品) ───────────────────────────────────
    async def list_contents(self, **params: Any) -> list:
        data = await self._request("GET", "/api/contents", params=params or None)
        if isinstance(data, list):
            return data
        return data.get("contents", []) if isinstance(data, dict) else []

    # ── publish (发布/已发布) ───────────────────────────────────
    async def list_published(self, account_id: int) -> list:
        # 真实端点 ``GET /api/publish/published`` 要求必填 query 参数
        # ``account_id``（且无默认值），缺省会触发 FastAPI 422。该端点还
        # 依赖浏览器与已登录的小红书账号，属于「需登录/浏览器」端点。
        data = await self._request(
            "GET", "/api/publish/published",
            params={"account_id": int(account_id)},
        )
        if isinstance(data, list):
            return data
        return data.get("items", []) if isinstance(data, dict) else []
