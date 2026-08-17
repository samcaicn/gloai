"""Layer 4 tools: concrete tool implementations (shell / file / git / browser / web / MCP ...).

`browser_skill` is one of them — a *complementary* backend that drives the user's
real, already-logged-in browser through the Tencent BrowserSkill (`bsk`) CLI. It
is **parallel to** (and does NOT replace) the CDP / Playwright browser tooling,
and it is intentionally **not part of any perception cascade** (CDP -> UIA -> OCR -> VLM).
"""
from opc.layer4_tools import browser_skill, registry

__all__ = ["browser_skill", "registry"]
