"""BrowserSkill backend for OpenOPC (layer 4 tool).

COMPLEMENTARY, NOT A REPLACEMENT.
--------------------------------
This module is intentionally *parallel* to the project's CDP / browser tooling
(the existing ``browser_*`` Playwright tools). It does NOT participate in any
perception cascade (CDP -> UIA -> OCR -> VLM) and it does NOT replace CDP.

  * CDP / ``browser_*``  -> low-level DOM introspection of Electron / Chromium
    windows (no login state of the user's daily browser).
  * ``browser_skill_*``  -> drives the **user's real, already-logged-in** browser
    through the Tencent BrowserSkill (``bsk``) CLI + browser-extension bridge.
    This fills the gap CDP cannot: operating web pages the user is already
    authenticated to (xiaohongshu / zhihu / admin consoles / SaaS).

ARCHITECTURE (per upstream research):
  * This is a *subprocess execution backend*, not an in-process library.
  * It shells out to ``bsk ...`` and parses the ``--json`` stdout.
  * External deps: the user must install the BrowserSkill extension + the ``bsk``
    CLI + keep the bridge process resident on 127.0.0.1, otherwise calls fail fast
    with a clear error code (``BROWSER_SKILL_UNAVAILABLE``).

VERIFIED ``bsk`` CLI surface (Tencent/BrowserSkill, 0.1.x — fast-moving, confirm
against the installed version before production):
  bsk session start --browser         -> prints a 4-letter session id
  bsk navigate   --session <id> <url> [--wait-until] [--timeout]
  bsk snapshot   --session <id>        -> accessibility tree with @eN refs (first-choice observe)
  bsk click      --session <id> @eN [--button] [--click-count] [--modifiers]
  bsk fill       --session <id> @eN --value "text"
  bsk select     --session <id> @eN --value X
  bsk press      --session <id> <key> [--ref @eN]
  bsk get-html   --session <id>        -> raw HTML (when snapshot is insufficient)
  bsk screenshot --session <id> [--ref @eN] [--out path]
  bsk navigate-back / navigate-forward / reload --session <id>
  bsk tab list / borrow / return --session <id>   (user tabs are read-only until borrowed)
  bsk status / bsk browsers / bsk session list
  Global flags: --json (machine-readable stdout, errors included) --quiet
"""
from __future__ import annotations

import json
import re
import shlex
import shutil
import subprocess
from dataclasses import dataclass
from typing import Any, Optional

BSK_COMMAND = "bsk"
DEFAULT_TIMEOUT = 60.0
SESSION_ID_RE = re.compile(r"\b[a-zA-Z0-9]{4}\b")


class BrowserSkillError(Exception):
    """Raised for bsk failures the caller may want to catch and convert."""

    def __init__(self, message: str, code: str = "BROWSER_SKILL_ERROR",
                 exit_code: Optional[int] = None) -> None:
        super().__init__(message)
        self.code = code
        self.exit_code = exit_code


@dataclass
class BrowserSkillConfig:
    command: str = BSK_COMMAND
    timeout: float = DEFAULT_TIMEOUT
    json_flag: bool = True
    quiet: bool = True
    # Optional fixed session; otherwise the backend tracks its own default session.
    default_session: Optional[str] = None


@dataclass
class BrowserSkillResult:
    ok: bool
    code: str = "OK"
    data: Any = None
    error: Optional[str] = None
    session: Optional[str] = None
    exit_code: Optional[int] = None
    raw: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "ok": self.ok,
            "code": self.code,
            "data": self.data,
            "error": self.error,
            "session": self.session,
            "exit_code": self.exit_code,
            "raw": self.raw,
        }


class BrowserSkillBackend:
    """Subprocess execution backend for the Tencent BrowserSkill ``bsk`` CLI."""

    def __init__(self, config: Optional[BrowserSkillConfig] = None) -> None:
        self.config = config or BrowserSkillConfig()
        self._default_session: Optional[str] = self.config.default_session

    # ------------------------------------------------------------------ #
    # Availability
    # ------------------------------------------------------------------ #
    def is_available(self) -> tuple[bool, str]:
        """Return (available, detail). Fails fast when CLI or bridge is missing."""
        if shutil.which(self.config.command) is None:
            return False, (
                f"`{self.config.command}` 不在 PATH。可调用 browser_skill_ensure_installed() "
                "自动安装（幂等，装到 ~/.local/bin）；或手动执行 "
                "irm https://raw.githubusercontent.com/Tencent/BrowserSkill/main/install.ps1 | iex "
                "并安装浏览器扩展 + 常驻桥接进程（扩展无法自动装，需用 browser_skill_extension_guidance 引导）。"
            )
        try:
            self._run_raw(["status"], timeout=10.0)
            return True, "ok"
        except BrowserSkillError as exc:
            return False, f"bsk 已安装但桥接不可用: {exc}"

    # ------------------------------------------------------------------ #
    # Low-level runner
    # ------------------------------------------------------------------ #
    def _build_argv(self, subcommand_args: list[str]) -> list[str]:
        argv = [self.config.command, *subcommand_args]
        if self.config.json_flag:
            argv.append("--json")
        if self.config.quiet:
            argv.append("--quiet")
        return argv

    def _run_raw(self, subcommand_args: list[str],
                 timeout: Optional[float] = None) -> subprocess.CompletedProcess:
        argv = self._build_argv(subcommand_args)
        try:
            return subprocess.run(
                argv,
                capture_output=True,
                text=True,
                timeout=timeout if timeout is not None else self.config.timeout,
                check=False,
            )
        except FileNotFoundError as exc:
            raise BrowserSkillError(
                f"命令未找到: {self.config.command}", code="BROWSER_SKILL_UNAVAILABLE"
            ) from exc
        except subprocess.TimeoutExpired as exc:
            raise BrowserSkillError(
                f"bsk 调用超时 (> {timeout or self.config.timeout}s)",
                code="BROWSER_SKILL_TIMEOUT",
            ) from exc

    @staticmethod
    def _parse(proc: subprocess.CompletedProcess) -> Any:
        out = (proc.stdout or "").strip()
        if not out:
            return None
        try:
            return json.loads(out)
        except json.JSONDecodeError:
            return out

    def run(self, subcommand_args: list[str],
            *, timeout: Optional[float] = None) -> BrowserSkillResult:
        """Execute one bsk subcommand and return a structured result."""
        proc = self._run_raw(subcommand_args, timeout=timeout)
        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout or "").strip() or f"bsk exited {proc.returncode}"
            return BrowserSkillResult(
                ok=False, code="BSK_ERROR", error=err,
                exit_code=proc.returncode, raw=proc.stdout,
            )
        return BrowserSkillResult(ok=True, code="OK", data=self._parse(proc), raw=proc.stdout)

    # ------------------------------------------------------------------ #
    # Session handling
    # ------------------------------------------------------------------ #
    def _resolve_session(self, session: Optional[str]) -> str:
        sid = session or self._default_session
        if not sid:
            raise BrowserSkillError(
                "缺少 session。请先调用 browser_skill_session_start 获取 session id。",
                code="NO_SESSION",
            )
        return sid

    @staticmethod
    def _extract_session_id(data: Any, raw_text: str) -> Optional[str]:
        if isinstance(data, dict):
            for key in ("session", "session_id", "id", "agent_window_session"):
                v = data.get(key)
                if isinstance(v, str) and v.strip():
                    return v.strip()
        m = SESSION_ID_RE.search(raw_text or "")
        return m.group(0) if m else None

    def start_session(self, browser: Optional[str] = None) -> BrowserSkillResult:
        args = ["session", "start"]
        if browser:
            args += ["--browser", browser]
        res = self.run(args)
        if not res.ok:
            return res
        sid = self._extract_session_id(res.data, res.raw or "")
        if not sid:
            return BrowserSkillResult(
                ok=False, code="NO_SESSION_ID",
                error="未能从 `bsk session start` 输出解析出 session id",
                raw=res.raw,
            )
        self._default_session = sid
        return BrowserSkillResult(ok=True, code="OK", session=sid,
                                  data={"session": sid, "raw": res.data}, raw=res.raw)

    def session_stop(self, session: Optional[str] = None,
                     stop_all: bool = False) -> BrowserSkillResult:
        if stop_all:
            return self.run(["session", "stop", "--all"])
        sid = self._resolve_session(session)
        res = self.run(["session", "stop", "--session", sid])
        if res.ok and self._default_session == sid:
            self._default_session = None
        return res

    def session_list(self) -> BrowserSkillResult:
        return self.run(["session", "list"])

    # ------------------------------------------------------------------ #
    # Observation & navigation
    # ------------------------------------------------------------------ #
    def status(self) -> BrowserSkillResult:
        return self.run(["status"])

    def browsers(self) -> BrowserSkillResult:
        return self.run(["browsers"])

    def snapshot(self, session: Optional[str] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        return self.run(["snapshot", "--session", sid])

    def navigate(self, url: str, session: Optional[str] = None,
                 wait_until: Optional[str] = None,
                 timeout: Optional[float] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        args = ["navigate", "--session", sid, url]
        if wait_until:
            args += ["--wait-until", wait_until]
        return self.run(args, timeout=timeout)

    def navigate_back(self, session: Optional[str] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        return self.run(["navigate-back", "--session", sid])

    def navigate_forward(self, session: Optional[str] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        return self.run(["navigate-forward", "--session", sid])

    def reload(self, session: Optional[str] = None,
               hard: bool = False) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        args = ["reload", "--session", sid]
        if hard:
            args.append("--hard")
        return self.run(args)

    # ------------------------------------------------------------------ #
    # Interaction
    # ------------------------------------------------------------------ #
    def click(self, ref: str, session: Optional[str] = None,
              button: Optional[str] = None,
              click_count: Optional[int] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        args = ["click", "--session", sid, ref]
        if button:
            args += ["--button", button]
        if click_count is not None:
            args += ["--click-count", str(click_count)]
        return self.run(args)

    def fill(self, ref: str, value: str,
             session: Optional[str] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        return self.run(["fill", "--session", sid, ref, "--value", value])

    def select(self, ref: str, value: str,
               session: Optional[str] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        return self.run(["select", "--session", sid, ref, "--value", value])

    def press(self, key: str, session: Optional[str] = None,
              ref: Optional[str] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        args = ["press", "--session", sid, key]
        if ref:
            args += ["--ref", ref]
        return self.run(args)

    # ------------------------------------------------------------------ #
    # Extraction & capture
    # ------------------------------------------------------------------ #
    def extract(self, session: Optional[str] = None) -> BrowserSkillResult:
        """Raw HTML / hidden DOM extraction — use when snapshot is insufficient."""
        sid = self._resolve_session(session)
        return self.run(["get-html", "--session", sid])

    def screenshot(self, session: Optional[str] = None, ref: Optional[str] = None,
                   out: Optional[str] = None) -> BrowserSkillResult:
        sid = self._resolve_session(session)
        args = ["screenshot", "--session", sid]
        if ref:
            args += ["--ref", ref]
        if out:
            args += ["--out", out]
        return self.run(args)

    # ------------------------------------------------------------------ #
    # Escape hatch
    # ------------------------------------------------------------------ #
    def exec_raw(self, raw_args: list[str]) -> BrowserSkillResult:
        """Pass-through for arbitrary bsk subcommands (high-level skill ops)."""
        return self.run(list(raw_args))


# ---------------------------------------------------------------------- #
# Module-level singleton + tool-callable functions (the `browser_skill_*` set)
# ---------------------------------------------------------------------- #
_default_backend = BrowserSkillBackend()


def _wrap(method_name: str, *args, **kwargs) -> dict:
    try:
        return getattr(_default_backend, method_name)(*args, **kwargs).to_dict()
    except BrowserSkillError as exc:
        return BrowserSkillResult(
            ok=False, code=exc.code, error=str(exc), exit_code=exc.exit_code
        ).to_dict()


def browser_skill_status() -> dict:
    return _default_backend.status().to_dict()


def browser_skill_browsers() -> dict:
    return _default_backend.browsers().to_dict()


def browser_skill_session_start(browser: Optional[str] = None) -> dict:
    return _default_backend.start_session(browser).to_dict()


def browser_skill_session_stop(session: Optional[str] = None,
                                stop_all: bool = False) -> dict:
    return _default_backend.session_stop(session, stop_all).to_dict()


def browser_skill_session_list() -> dict:
    return _default_backend.session_list().to_dict()


def browser_skill_navigate(url: str, session: Optional[str] = None,
                           wait_until: Optional[str] = None,
                           timeout: Optional[float] = None) -> dict:
    return _wrap("navigate", url, session, wait_until, timeout)


def browser_skill_navigate_back(session: Optional[str] = None) -> dict:
    return _wrap("navigate_back", session)


def browser_skill_navigate_forward(session: Optional[str] = None) -> dict:
    return _wrap("navigate_forward", session)


def browser_skill_reload(session: Optional[str] = None, hard: bool = False) -> dict:
    return _wrap("reload", session, hard)


def browser_skill_snapshot(session: Optional[str] = None) -> dict:
    return _wrap("snapshot", session)


def browser_skill_click(ref: str, session: Optional[str] = None,
                        button: Optional[str] = None,
                        click_count: Optional[int] = None) -> dict:
    return _wrap("click", ref, session, button, click_count)


def browser_skill_input(ref: str, value: str,
                        session: Optional[str] = None) -> dict:
    return _wrap("fill", ref, value, session)


def browser_skill_select(ref: str, value: str,
                         session: Optional[str] = None) -> dict:
    return _wrap("select", ref, value, session)


def browser_skill_press(key: str, session: Optional[str] = None,
                        ref: Optional[str] = None) -> dict:
    return _wrap("press", key, session, ref)


def browser_skill_extract(session: Optional[str] = None) -> dict:
    return _wrap("extract", session)


def browser_skill_screenshot(session: Optional[str] = None,
                             ref: Optional[str] = None,
                             out: Optional[str] = None) -> dict:
    return _wrap("screenshot", session, ref, out)


def browser_skill_exec(raw_args: Any) -> dict:
    """Run an arbitrary bsk subcommand. Accepts a list or a shell string."""
    if isinstance(raw_args, str):
        raw_args = shlex.split(raw_args)
    return _default_backend.exec_raw(raw_args).to_dict()


# ---------------------------------------------------------------------- #
# Tool specifications (for the runtime / agent registry)
# ---------------------------------------------------------------------- #
BROWSER_SKILL_TOOL_SPECS: list[dict] = [
    {
        "name": "browser_skill_status",
        "category": "browser_skill",
        "description": "检查 BrowserSkill CLI 与桥接进程是否可用（连接健康、已连浏览器、活动 session）。不依赖 session。",
        "parameters": [],
        "session_required": False,
    },
    {
        "name": "browser_skill_browsers",
        "category": "browser_skill",
        "description": "列出已连接的浏览器实例（id / label / version），用于选取 session 目标。",
        "parameters": [],
        "session_required": False,
    },
    {
        "name": "browser_skill_session_start",
        "category": "browser_skill",
        "description": "开启 BrowserSkill Agent Window，返回 4 字母 session id（后续操作必填）。",
        "parameters": [
            {"name": "browser", "type": "str", "required": False,
             "description": "指定浏览器实例 id；省略则使用默认已登录浏览器。"},
        ],
        "session_required": False,
    },
    {
        "name": "browser_skill_session_stop",
        "category": "browser_skill",
        "description": "结束 session（关闭 Agent Window，自动归还借用的用户标签页）。stop_all=True 结束全部。",
        "parameters": [
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
            {"name": "stop_all", "type": "bool", "required": False, "description": "True 则结束所有活动 session。"},
        ],
        "session_required": False,
    },
    {
        "name": "browser_skill_session_list",
        "category": "browser_skill",
        "description": "列出当前所有活动 session。",
        "parameters": [],
        "session_required": False,
    },
    {
        "name": "browser_skill_navigate",
        "category": "browser_skill",
        "description": "在 Agent Window 打开 URL（复用用户真实登录态）。",
        "parameters": [
            {"name": "url", "type": "str", "required": True, "description": "目标网址。"},
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
            {"name": "wait_until", "type": "str", "required": False, "description": "load / domcontentloaded / networkidle 等。"},
            {"name": "timeout", "type": "float", "required": False, "description": "超时秒数。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_navigate_back",
        "category": "browser_skill",
        "description": "浏览器历史后退一步。",
        "parameters": [
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_navigate_forward",
        "category": "browser_skill",
        "description": "浏览器历史前进一步。",
        "parameters": [
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_reload",
        "category": "browser_skill",
        "description": "重新加载当前标签页，可硬刷新绕过缓存。",
        "parameters": [
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
            {"name": "hard", "type": "bool", "required": False, "description": "True 则硬刷新。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_snapshot",
        "category": "browser_skill",
        "description": "首选页面观测：返回带 @eN 编号的无障碍树，供规划点击/填写。导航后务必重新 snapshot。",
        "parameters": [
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_click",
        "category": "browser_skill",
        "description": "点击元素，按 snapshot 给出的 @eN 引用。",
        "parameters": [
            {"name": "ref", "type": "str", "required": True, "description": "元素引用，如 @e3。"},
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
            {"name": "button", "type": "str", "required": False, "description": "left / right / middle。"},
            {"name": "click_count", "type": "int", "required": False, "description": "点击次数。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_input",
        "category": "browser_skill",
        "description": "清空并键入文本到输入框（对应 bsk fill）。",
        "parameters": [
            {"name": "ref", "type": "str", "required": True, "description": "输入框元素引用，如 @e8。"},
            {"name": "value", "type": "str", "required": True, "description": "要输入的文本。"},
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_select",
        "category": "browser_skill",
        "description": "设置下拉选项（按 value）。",
        "parameters": [
            {"name": "ref", "type": "str", "required": True, "description": "下拉元素引用。"},
            {"name": "value", "type": "str", "required": True, "description": "选项 value。"},
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_press",
        "category": "browser_skill",
        "description": "执行键盘操作（Enter、Ctrl+A 等），可选 --ref 先聚焦元素。",
        "parameters": [
            {"name": "key", "type": "str", "required": True, "description": "按键或组合，如 Enter、Ctrl+A。"},
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
            {"name": "ref", "type": "str", "required": False, "description": "可选，先聚焦的元素引用。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_extract",
        "category": "browser_skill",
        "description": "提取页面原始 HTML / 隐藏 DOM（snapshot 不足以回答时再用，token 成本高）。",
        "parameters": [
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_screenshot",
        "category": "browser_skill",
        "description": "截图为 PNG；可 --ref 裁剪到单个元素，--out 指定路径。",
        "parameters": [
            {"name": "session", "type": "str", "required": False, "description": "session id；省略用默认 session。"},
            {"name": "ref", "type": "str", "required": False, "description": "可选，裁剪到的元素引用。"},
            {"name": "out", "type": "str", "required": False, "description": "可选，输出文件路径。"},
        ],
        "session_required": True,
    },
    {
        "name": "browser_skill_exec",
        "category": "browser_skill",
        "description": "透传任意 bsk 子命令（逃生口，喂高层 skill 操作进 executor）。接受参数列表或 shell 字符串。",
        "parameters": [
            {"name": "raw_args", "type": "list|str", "required": True,
             "description": "bsk 子命令参数，如 ['tab','borrow','--session','abcd'] 或 'tab list --session abcd'。"},
        ],
        "session_required": False,
    },
]


def get_browser_skill_tools() -> dict:
    """Return {tool_name: callable} for the browser_skill capability."""
    return {
        "browser_skill_status": browser_skill_status,
        "browser_skill_browsers": browser_skill_browsers,
        "browser_skill_session_start": browser_skill_session_start,
        "browser_skill_session_stop": browser_skill_session_stop,
        "browser_skill_session_list": browser_skill_session_list,
        "browser_skill_navigate": browser_skill_navigate,
        "browser_skill_navigate_back": browser_skill_navigate_back,
        "browser_skill_navigate_forward": browser_skill_navigate_forward,
        "browser_skill_reload": browser_skill_reload,
        "browser_skill_snapshot": browser_skill_snapshot,
        "browser_skill_click": browser_skill_click,
        "browser_skill_input": browser_skill_input,
        "browser_skill_select": browser_skill_select,
        "browser_skill_press": browser_skill_press,
        "browser_skill_extract": browser_skill_extract,
        "browser_skill_screenshot": browser_skill_screenshot,
        "browser_skill_exec": browser_skill_exec,
    }
