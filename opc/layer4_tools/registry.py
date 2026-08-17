"""Layer 4 tool registry — single discovery point for the runtime.

NOTE: the project is mid-migration. Sibling modules referenced elsewhere
(``opc.layer4_tools.collaboration_rpc`` / ``collaboration_dispatch``) are not
present on disk yet; this registry only aggregates what is implemented. The
runtime should resolve a tool *name* to its *callable* via
:func:`collect_layer4_tools`, and installed plugin tools are merged in through
:mod:`opc.plugin_core.loader`.

"""

__all__ = [
    "collect_layer4_tools",
    "collect_layer4_specs",
    "LAYER4_TOOL_SPECS",
    "ToolDefinition",
    "ToolRegistry",
]


def collect_layer4_tools() -> dict:
    """Return {tool_name: callable} across all implemented layer-4 tools."""
    tools: dict = {}
    # Plugin-provided tools (registered via opc.plugin_core loader)
    try:
        from opc.plugin_core.loader import collect_plugin_tools

        tools.update(
            {name: spec["callable"] for name, spec in collect_plugin_tools().items()}
        )
    except Exception:
        pass
    # Future: browser (CDP/Playwright), shell, file, git, web_search, mcp ...
    return tools


def collect_layer4_specs() -> list:
    """Return tool specifications across all implemented layer-4 tools."""
    specs: list = []
    return specs


# Convenience alias mirroring the spec list above.
LAYER4_TOOL_SPECS = collect_layer4_specs()


# ---------------------------------------------------------------------------
# Runtime tool definition + registry
# ---------------------------------------------------------------------------

from dataclasses import dataclass, field  # noqa: E402
from typing import Any, Awaitable, Callable  # noqa: E402

import inspect  # noqa: E402

from opc.layer4_tools.output_budget import budget_tool_output  # noqa: E402

# Maximum serialized tool output size (characters). Outputs exceeding this
# limit are previewed before being returned to the agent loop; recoverable
# tools persist full output to disk.
_OUTPUT_LIMIT = 20_000


@dataclass
class ToolDefinition:
    """Canonical runtime tool descriptor.

    Consumed by :class:`ToolRegistry`, the native runtime planner
    (:mod:`opc.layer3_agent.runtime_v2.tool_planner`), and the capability
    manager. ``func`` is the coroutine/sync callable invoked by
    ``ToolRegistry.execute``; it receives the tool arguments plus, when its
    signature accepts them, ``task`` and ``on_progress``.
    """

    name: str
    description: str = ""
    parameters: dict[str, Any] = field(default_factory=dict)
    func: Callable[..., Any] | None = None
    category: str = "general"
    requires_confirmation: bool = False
    concurrency_safe: bool | None = None
    read_only: bool | None = None
    runtime_managed: bool = False
    plugin_id: str = ""
    extra: dict[str, Any] = field(default_factory=dict)
    # Output-budget tuning (consumed by ToolRegistry.execute via
    # budget_tool_output). Kept on the descriptor so per-tool limits survive
    # plugin refresh and the native planner can read them.
    max_result_chars: int = _OUTPUT_LIMIT
    persist_large_results: bool = True
    self_bounded_output: bool = False
    preview_chars: int | None = None

    def to_schema(self) -> dict[str, Any]:
        """OpenAI-style function-calling schema used by the LLM layer."""
        return {
            "name": self.name,
            "description": self.description,
            "parameters": self.parameters or {"type": "object", "properties": {}},
        }


async def _invoke_tool(
    func: Callable[..., Any] | None,
    args: dict[str, Any],
    *,
    task: Any = None,
    on_progress: Any = None,
) -> Any:
    """Call a tool callable, injecting ``task``/``on_progress`` only when accepted.

    Handles both sync and async coroutine functions and tolerates callables
    that accept neither auxiliary argument.
    """
    if func is None:
        raise RuntimeError("tool has no callable")
    kwargs: dict[str, Any] = dict(args or {})
    try:
        params = inspect.signature(func).parameters
        if "task" in params:
            kwargs["task"] = task
        if "on_progress" in params:
            kwargs["on_progress"] = on_progress
    except (TypeError, ValueError):
        pass
    result = func(**kwargs)
    if inspect.isawaitable(result):
        result = await result
    return result


class ToolRegistry:
    """In-memory registry of runtime tools.

    The engine populates it once at startup via :meth:`register` (built-in
    tools) and :meth:`refresh_plugins` (installed plugin tools). The native
    runtime re-reads :meth:`list_tools` per task, so a :meth:`refresh_plugins`
    call after a plugin install makes new tools executable on the next turn —
    no process restart required.
    """

    def __init__(self) -> None:
        self._tools: dict[str, ToolDefinition] = {}
        self._approval_callback: (
            Callable[[ToolDefinition, dict[str, Any], Any, Any], Awaitable[tuple[bool, Any]]]
            | None
        ) = None

    # ---- mutators --------------------------------------------------------
    def register(self, tool: ToolDefinition) -> None:
        if not isinstance(tool, ToolDefinition):
            raise TypeError("ToolRegistry.register expects a ToolDefinition")
        if not tool.name:
            raise ValueError("ToolDefinition.name is required")
        self._tools[tool.name] = tool

    def register_many(self, tools: list[ToolDefinition]) -> None:
        for tool in tools:
            self.register(tool)

    def clear(self) -> None:
        self._tools.clear()

    # ---- queries ---------------------------------------------------------
    def get(self, name: str) -> ToolDefinition | None:
        return self._tools.get(name)

    def list_tools(self) -> list[ToolDefinition]:
        return list(self._tools.values())

    def get_schemas(self, allowed: list[str] | None = None) -> list[dict[str, Any]]:
        """Return LLM function-calling schemas, optionally filtered by name."""
        allowed_set = set(allowed) if allowed else None
        out: list[dict[str, Any]] = []
        for tool in self._tools.values():
            if allowed_set is not None and tool.name not in allowed_set:
                continue
            out.append(tool.to_schema())
        return out

    def set_approval_callback(
        self,
        cb: Callable[[ToolDefinition, dict[str, Any], Any, Any], Awaitable[tuple[bool, Any]]]
        | None,
    ) -> None:
        self._approval_callback = cb

    # ---- execution -------------------------------------------------------
    async def execute(
        self,
        tool_name: str,
        args: dict[str, Any],
        *,
        task: Any = None,
        on_progress: Any = None,
        skip_approval: bool = False,
    ) -> dict[str, Any]:
        """Invoke a registered tool and return a normalized result dict.

        The result dict always carries at least ``success``; tool-specific
        payloads live under ``result`` (or as the whole dict when the tool
        already returns a result-shaped mapping).
        """
        tool = self.get(tool_name)
        if tool is None:
            return {"success": False, "error": f"unknown tool: {tool_name}"}
        if (
            tool.requires_confirmation
            and not skip_approval
            and self._approval_callback is not None
        ):
            try:
                allowed, decision = await self._approval_callback(
                    tool, args or {}, task, on_progress
                )
            except Exception as exc:  # noqa: BLE001 - never crash the executor
                return {"success": False, "error": f"approval callback error: {exc}"}
            if not allowed:
                return {
                    "success": False,
                    "error": "tool requires approval",
                    "blocked": True,
                    "approval": decision,
                }
        try:
            result = await _invoke_tool(
                tool.func, args or {}, task=task, on_progress=on_progress
            )
        except Exception as exc:  # noqa: BLE001
            return {"success": False, "error": f"{type(exc).__name__}: {exc}"}

        # Recoverable registry-level output budget (per-tool tuning). The
        # budget helper expects the canonical {"result": <payload>, "success":
        # True} envelope, so wrap non-enveloped tool returns before budgeting.
        envelope = {"result": result, "success": True}
        return budget_tool_output(
            envelope,
            tool_name=tool.name,
            task=task,
            max_chars=int(tool.max_result_chars or _OUTPUT_LIMIT),
            preview_chars=tool.preview_chars,
            persist_large_results=bool(tool.persist_large_results),
            self_bounded_output=bool(tool.self_bounded_output),
        )

    # ---- plugin integration ---------------------------------------------
    def refresh_plugins(self) -> int:
        """Merge installed plugin tools (from ``opc.plugin_core.loader``).

        Returns the number of newly registered plugin tools. Existing
        built-in tools are never overwritten.
        """
        try:
            from opc.plugin_core.loader import collect_plugin_tools
        except Exception:  # noqa: BLE001 - plugin_core may be absent in some envs
            return 0
        added = 0
        for name, spec in collect_plugin_tools().items():
            if name in self._tools:
                continue
            meta = spec.get("spec") or {}
            self.register(
                ToolDefinition(
                    name=name,
                    description=meta.get("description", f"Plugin tool: {name}"),
                    parameters=meta.get(
                        "parameters", {"type": "object", "properties": {}}
                    ),
                    func=spec.get("callable"),
                    category=meta.get("category", "plugin"),
                    requires_confirmation=bool(meta.get("requires_confirmation", False)),
                    concurrency_safe=meta.get("concurrency_safe"),
                    read_only=meta.get("read_only"),
                    plugin_id=spec.get("plugin_id", ""),
                )
            )
            added += 1
        return added
