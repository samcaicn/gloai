from playwright.sync_api import sync_playwright

URL = "https://signpath.io/solutions/open-source-community"

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
    ctx = browser.contexts[0] if browser.contexts else browser.new_context()
    page = ctx.new_page()
    page.goto(URL, wait_until="load", timeout=30000)
    page.wait_for_timeout(3000)
    print("TITLE:", page.title())
    print("URL_NOW:", page.url)
    fields = page.evaluate(
        """() => {
        const out = [];
        document.querySelectorAll('input, textarea, select').forEach(el => {
            const lbl = (el.labels && el.labels.length) ? Array.from(el.labels).map(l=>l.innerText.trim()).join(' | ') : '';
            const ph = el.getAttribute('placeholder') || '';
            out.push({tag: el.tagName, type: el.type, name: el.name, id: el.id, label: lbl, placeholder: ph, value: el.value||''});
        });
        return out;
    }"""
    )
    print("FIELDS:")
    for f in fields:
        print(" ", f)
    btns = page.evaluate(
        """() => Array.from(document.querySelectorAll('button, a.btn, input[type=submit], a[href*="apply"], a[href*="request"], a[href*="signup"]')).map(b=>({t: b.innerText.trim().slice(0,50), href: b.href||''}))"""
    )
    print("LINKS/BTNS:")
    for b in btns:
        if b["t"] or b["href"]:
            print(" ", b)
    # snapshot of visible text (first 1500 chars)
    text = page.evaluate("() => document.body.innerText.replace(/\\n+/g,'\\n').slice(0,1500)")
    print("TEXT_SNIPPET:\n", text)
    # do NOT close browser (would kill user's Brave); just close our page
    try:
        page.close()
    except Exception:
        pass
