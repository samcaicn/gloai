#!/usr/bin/env python3
"""
Raphael AI - web2api proxy (personal / research use only)
=========================================================

Turns the free Raphael AI web UI (https://raphael.app) into a local API.

Why a browser is required:
  Raphael gates generation behind Cloudflare Turnstile. The real endpoint is
  POST /api/generate-image (NDJSON stream) and needs a `turnstileToken` that is
  only produced by solving the CF challenge inside a real browser. So this
  proxy keeps a persistent Playwright Chromium session, lets it clear CF, and
  captures the generated image URLs by hooking window.fetch and tee-ing the
  stream (the web app keeps working; we read a copy).

Endpoints (OpenAI-ish image API):
  POST /v1/images/generations
      { "prompt": "...", "model": "...", "aspect_ratio": "1:1",
        "n": 1, "negative_prompt": "...", "size": "1024x1024" }
      -> { "created": 169..., "data": [ { "url": "https://..." }, ... ] }
  GET  /health

Bypassing the anonymous daily limit (IP-keyed):
  Raphael enforces a ~2 images/day cap **per source IP** for anonymous users
  (verified: brand-new browser contexts on the same egress IP all hit
  ANON_DAILY_LIMIT with used:2). It is NOT cookie/session-keyed, so clearing
  cookies does nothing. The only effective bypass is **egress-IP rotation**:
  give the proxy a pool of upstream proxies (each a distinct egress IP) and
  this server round-robins over them, automatically retrying on
  ANON_DAILY_LIMIT by switching to the next proxy. Provide the pool via
  RAPHAEL_PROXIES (newline/comma separated; "direct" or empty = no proxy).

NOTE: Built for PERSONAL, NON-COMMERCIAL, RESEARCH/EDUCATIONAL use.
Raphael's Terms of Service state they do not offer a public API and flag
reverse-engineering as unauthorized. Use gently (don't hammer their servers)
and respect their ToS. The author is not responsible for misuse.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import time
from typing import Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from playwright.async_api import async_playwright, Browser, BrowserContext, Page

# --------------------------------------------------------------------------- #
# Config
# --------------------------------------------------------------------------- #
RAPHAEL_URL = os.getenv("RAPHAEL_URL", "https://raphael.app/en")
HEADLESS = os.getenv("RAPHAEL_HEADLESS", "true").lower() in ("1", "true", "yes")
PW_BROWSERS_PATH = os.getenv("RAPHAEL_PW_BROWSERS_PATH")
COOKIES_FILE = os.getenv("RAPHAEL_COOKIES")  # path to JSON cookies (logged-in session)
GEN_TIMEOUT = int(os.getenv("RAPHAEL_TIMEOUT", "180"))
CLEAR_WAIT = int(os.getenv("RAPHAEL_CLEAR_WAIT", "6"))

# Proxy rotation (the anon-limit bypass).
PROXIES_RAW = os.getenv("RAPHAEL_PROXIES", "")
ROTATE = os.getenv("RAPHAEL_ROTATE_PROXIES", "true").lower() in ("1", "true", "yes")
MAX_PROXY_RETRIES = int(os.getenv("RAPHAEL_MAX_PROXY_RETRIES", "8"))

if PW_BROWSERS_PATH:
    os.environ["PLAYWRIGHT_BROWSERS_PATH"] = PW_BROWSERS_PATH

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


def _parse_proxies(raw: str) -> list[Optional[str]]:
    """Parse RAPHAEL_PROXIES -> list of proxy URLs or None ('direct'/empty)."""
    out: list[Optional[str]] = []
    for part in re.split(r"[\n,]", raw or ""):
        p = part.strip()
        if not p or p.lower() == "direct":
            out.append(None)
        else:
            out.append(p)
    return out


# Resolved proxy pool. Empty => fall back to a single entry using the system
# proxy (current behavior), so existing single-proxy setups keep working.
_PROXIES: list[Optional[str]] = _parse_proxies(PROXIES_RAW) if PROXIES_RAW else []


# JS injected before the app loads: tee the /api/generate-image stream so we
# can read the NDJSON `url` fields without disturbing the app's own reader.
FETCH_HOOK = r"""
(() => {
  window.__raphCollected = [];
  window.__raphError = null;
  window.__raphSeen = [];
  window.__raphRaw = '';
  window.__raphStatus = null;
  const _orig = window.fetch ? window.fetch.bind(window) : null;
  if (!_orig) return;
  window.fetch = async (input, init) => {
    const resp = await _orig(input, init);
    const url = (typeof input === 'string') ? input : (input && input.url);
    if (url && url.indexOf('/api/generate-image') !== -1) {
      window.__raphSeen.push(url);
      window.__raphStatus = resp.status;
      try {
        const t = await resp.clone().text();
        window.__raphRaw += t + '\n';
        if (!resp.ok) {
          try { window.__raphError = (JSON.parse(t).error) || t; }
          catch (e) { window.__raphError = t || ('HTTP ' + resp.status); }
        } else {
          for (const line of t.split('\n')) {
            const s = line.trim();
            if (!s) continue;
            try {
              const o = JSON.parse(s);
              if (o.url && o.url.indexOf('http') === 0) window.__raphCollected.push(o.url);
            } catch (e) {}
          }
        }
      } catch (e) {
        window.__raphError = 'clone read fail: ' + e;
      }
    }
    return resp;
  };
})();
"""

app = FastAPI(title="Raphael web2api", version="0.2.0")


# --------------------------------------------------------------------------- #
# Worker pool (one browser per egress proxy)
# --------------------------------------------------------------------------- #
class Worker:
    def __init__(self, proxy: Optional[str], cookies: bool = False) -> None:
        self.proxy = proxy
        self.cookies = cookies
        self.browser: Optional[Browser] = None
        self.context: Optional[BrowserContext] = None
        self.page: Optional[Page] = None
        self.ready = False
        self.exhausted = False  # anon daily limit reached for this egress IP


WORKERS: dict[str, Worker] = {}
_RR = 0
_LOCK = asyncio.Lock()
_PW = None


def _proxy_key(proxy: Optional[str], cookies: bool) -> str:
    if cookies:
        return "acct"
    return "direct" if proxy is None else proxy


async def _get_pw():
    global _PW
    if _PW is None:
        _PW = await async_playwright().start()
    return _PW


def _system_proxy() -> Optional[str]:
    return os.getenv("HTTPS_PROXY") or os.getenv("HTTP_PROXY")


def _candidate_list() -> list[tuple[str, Optional[str], bool]]:
    """Anonymous proxy workers, plus the account worker if cookies are set."""
    if _PROXIES:
        cands = [(_proxy_key(p, False), p, False) for p in _PROXIES]
    else:
        sp = _system_proxy()
        cands = [(_proxy_key(sp, False), sp, False)]
    if COOKIES_FILE and os.path.exists(COOKIES_FILE):
        cands.append(("acct", _system_proxy(), True))
    return cands


async def ensure_worker(key: str, proxy: Optional[str], cookies: bool) -> Worker:
    w = WORKERS.get(key)
    if w is not None and w.ready:
        try:
            await w.page.evaluate("1")
        except Exception:
            w.ready = False
    if w is not None and not w.ready:
        try:
            if w.browser:
                await w.browser.close()
        except Exception:
            pass
        WORKERS.pop(key, None)
        w = None
    if w is None:
        w = Worker(proxy, cookies)
        WORKERS[key] = w
    if not w.ready:
        await _launch(w)
    return w


async def _launch(w: Worker) -> None:
    pw = await _get_pw()
    launch_proxy = {"server": w.proxy} if w.proxy else None
    w.browser = await pw.chromium.launch(
        headless=HEADLESS,
        proxy=launch_proxy,
        args=["--disable-blink-features=AutomationControlled",
              "--no-sandbox", "--disable-dev-shm-usage"],
    )
    w.context = await w.browser.new_context(
        user_agent=UA, viewport={"width": 1366, "height": 900}, locale="en-US")
    if w.cookies and COOKIES_FILE and os.path.exists(COOKIES_FILE):
        try:
            with open(COOKIES_FILE, "r", encoding="utf-8") as f:
                ck = json.load(f)
            await w.context.add_cookies(ck)
            print(f"[raphael] loaded {len(ck)} cookies from {COOKIES_FILE}")
        except Exception as e:
            print(f"[raphael] failed to load cookies: {e}")
    w.page = await w.context.new_page()
    await w.page.add_init_script(FETCH_HOOK)
    await w.page.goto(RAPHAEL_URL, wait_until="domcontentloaded")
    await asyncio.sleep(CLEAR_WAIT)
    try:
        await w.page.wait_for_load_state("networkidle", timeout=20000)
    except Exception:
        pass
    w.ready = True


async def acquire_worker() -> Optional[Worker]:
    """Round-robin over non-exhausted workers (rotation = the anon-limit bypass)."""
    global _RR
    cands = _candidate_list()
    n = len(cands)
    for _ in range(n):
        idx = _RR % n
        _RR += 1
        key, proxy, cookies = cands[idx]
        w = WORKERS.get(key)
        if w is not None and w.exhausted:
            continue
        try:
            return await ensure_worker(key, proxy, cookies)
        except Exception as e:
            print(f"[raphael] worker {key} launch failed: {e}")
            continue
    # Everything anonymous is exhausted; fall back to the account worker if any.
    if COOKIES_FILE and os.path.exists(COOKIES_FILE):
        return await ensure_worker("acct", _system_proxy(), True)
    return None


# --------------------------------------------------------------------------- #
# UI helpers
# --------------------------------------------------------------------------- #
async def _type_prompt(page: Page, prompt: str) -> None:
    box = page.locator("textarea[placeholder*='Describe the image']").first
    if await box.count() == 0:
        box = page.locator("textarea").first
    await box.click()
    await box.fill(prompt)


async def _click_generate(page: Page) -> None:
    btn = page.get_by_role("button", name="Generate", exact=False)
    if await btn.count() == 0:
        btn = page.locator("button:has-text('Generate')")
    if await btn.count() == 0:
        raise RuntimeError("Could not locate the Generate button")
    await btn.first.click()


async def _try_set_aspect(page: Page, aspect: str) -> None:
    try:
        b = page.locator(f"button:has-text('{aspect}')").first
        if await b.count():
            await b.click(timeout=3000)
    except Exception:
        pass


async def _try_set_count(page: Page, n: int) -> None:
    try:
        for _ in range(max(0, n - 1)):
            plus = page.locator("button:has-text('+')").first
            if await plus.count():
                await plus.click(timeout=2000)
            else:
                break
    except Exception:
        pass


# --------------------------------------------------------------------------- #
# Core generation (on a specific worker's page)
# --------------------------------------------------------------------------- #
async def _generate_on(page: Page, prompt: str, aspect_ratio: Optional[str],
                       n: int, negative_prompt: Optional[str]) -> list[str]:
    await page.evaluate("window.__raphCollected = []; window.__raphError = null;")
    await _type_prompt(page, prompt)
    if negative_prompt:
        try:
            nb = page.locator("textarea[placeholder*='negative' i]").first
            if await nb.count():
                await nb.fill(negative_prompt)
        except Exception:
            pass
    if aspect_ratio:
        await _try_set_aspect(page, aspect_ratio)
    if n and n > 1:
        await _try_set_count(page, n)

    await _click_generate(page)

    want = max(1, n)
    deadline = time.time() + GEN_TIMEOUT
    last = time.time()
    last_count = 0
    while time.time() < deadline:
        err = await page.evaluate("window.__raphError")
        if err:
            raise RuntimeError(f"Raphael returned error: {err}")
        got = await page.evaluate("window.__raphCollected")
        got = [u for u in got if u not in ("", None)]
        if len(got) >= want:
            await asyncio.sleep(1.5)
            return list(dict.fromkeys(got))[:want]
        if len(got) > last_count:
            last_count = len(got)
            last = time.time()
        elif got and time.time() - last > 25:
            return list(dict.fromkeys(got))
        await asyncio.sleep(1.0)
    got = await page.evaluate("window.__raphCollected")
    got = [u for u in got if u]
    if got:
        return list(dict.fromkeys(got))
    raise RuntimeError(
        "Generation timed out (no images captured). Cloudflare Turnstile "
        "may have blocked headless access - try RAPHAEL_HEADLESS=false."
    )


async def generate(prompt: str, aspect_ratio: Optional[str], n: int,
                   negative_prompt: Optional[str]) -> list[str]:
    """Generate, rotating egress proxies on ANON_DAILY_LIMIT."""
    attempts = 0
    max_attempts = max(1, MAX_PROXY_RETRIES) if (ROTATE and _PROXIES) else 1
    last_err: Optional[Exception] = None
    while attempts < max_attempts:
        attempts += 1
        async with _LOCK:
            worker = await acquire_worker()
        if worker is None:
            raise RuntimeError(
                "No available egress (all proxies exhausted and no account "
                "cookies configured). Add more proxies via RAPHAEL_PROXIES "
                "or supply RAPHAEL_COOKIES.")
        try:
            return await _generate_on(worker.page, prompt, aspect_ratio, n, negative_prompt)
        except RuntimeError as e:
            msg = str(e)
            if "ANON_DAILY_LIMIT" in msg or "DAILY_LIMIT" in msg:
                worker.exhausted = True
                last_err = e
                print(f"[raphael] {worker.proxy or 'direct'} exhausted "
                      f"(ANON_DAILY_LIMIT); rotating to next proxy.")
                continue
            raise
    raise RuntimeError(
        f"All proxies exhausted after {attempts} attempts: {last_err}")


# --------------------------------------------------------------------------- #
# API
# --------------------------------------------------------------------------- #
class ImageGenRequest(BaseModel):
    prompt: str = Field(..., description="Text prompt")
    model: Optional[str] = Field(None, description="Model name (best-effort UI)")
    aspect_ratio: Optional[str] = Field(None, description="e.g. 1:1,16:9,9:16,4:3,3:4,21:9,2:3,3:2")
    n: int = Field(1, ge=1, le=4, description="Number of images")
    size: Optional[str] = Field(None, description="Ignored by free tier (compat)")
    negative_prompt: Optional[str] = Field(None, description="Negative prompt")


@app.get("/health")
async def health():
    ready = any(w.ready for w in WORKERS.values())
    return {"status": "ok", "ready": ready, "workers": len(WORKERS)}


@app.post("/v1/images/generations")
async def images_generations(req: ImageGenRequest):
    try:
        urls = await generate(req.prompt, req.aspect_ratio, req.n, req.negative_prompt)
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
    return {"created": int(time.time()), "data": [{"url": u} for u in urls]}


@app.post("/api/generate")
async def generate_compat(req: ImageGenRequest):
    return await images_generations(req)


if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("RAPHAEL_PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
