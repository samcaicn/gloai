from playwright.sync_api import sync_playwright

URL = "https://signpath.io/solutions/open-source-community"

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
    ctx = browser.contexts[0] if browser.contexts else browser.new_context()
    page = ctx.new_page()
    page.goto(URL, wait_until="load", timeout=30000)
    page.wait_for_timeout(2000)

    # all anchors
    anchors = page.evaluate(
        """() => Array.from(document.querySelectorAll('a')).map(a=>({t:a.innerText.trim().slice(0,50), href:a.href||''})).filter(x=>x.href)"""
    )
    print("ANCHORS:")
    for a in anchors:
        print("  ", a)

    # iframes
    iframes = page.evaluate("""() => Array.from(document.querySelectorAll('iframe')).map(f=>({src:f.src||'', id:f.id||''}))""")
    print("IFRAMES:", iframes)

    # scroll to bottom to trigger lazy forms
    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)")
    page.wait_for_timeout(2500)

    # dump fields again (incl. possibly now-visible form)
    def dump_fields(scope):
        return scope.evaluate(
            """() => {
            const out = [];
            document.querySelectorAll('input, textarea, select').forEach(el => {
                const lbl = (el.labels && el.labels.length) ? Array.from(el.labels).map(l=>l.innerText.trim()).join(' | ') : '';
                out.push({tag: el.tagName, type: el.type, name: el.name, id: el.id, label: lbl, placeholder: el.getAttribute('placeholder')||'', value: el.value||''});
            });
            return out;
        }"""
        )
    fields = dump_fields(page)
    print("PAGE FIELDS AFTER SCROLL:")
    for f in fields:
        print("  ", f)

    # try HubSpot iframe
    hs = page.query_selector("iframe[src*='hsforms'], iframe[src*='hubspot'], iframe.iframe--hubspot")
    if hs:
        print("HUBSPOT IFRAME FOUND")
        fctx = hs.content_frame()
        if fctx:
            hf = dump_fields(fctx)
            print("HUBSPOT FIELDS:")
            for f in hf:
                print("  ", f)
    else:
        print("NO HUBSPOT IFRAME (by known selectors)")

    print("URL_NOW:", page.url)
    try:
        page.close()
    except Exception:
        pass
