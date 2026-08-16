"""Plugin errors (standalone — no dependency on the office UI service layer)."""

from __future__ import annotations

from typing import Any


class PluginError(Exception):
    """Expected business error raised by the plugin core."""

    def __init__(self, code: str, message: str, payload: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.payload = dict(payload or {})

    def to_payload(self) -> dict[str, Any]:
        cleaned = {
            k: v for k, v in self.payload.items() if k not in {"ok", "error", "code"}
        }
        cleaned.update({"error": self.message, "code": self.code})
        return cleaned
