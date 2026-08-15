from playwright.sync_api import sync_playwright

URLS = ["https://signpath.org/", "https://signpath.org/request", "https://signpath.org/apply"]

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
    ctx = browser.contexts[0] if browser.contexts else browser.new_context()
    page = ctx.new_page()
    for URL in URLS:
        try:
            page.goto(URL, wait_until="load", timeout=20000)
        except Exception as e:
            print("GOTO FAIL", URL, e)
            continue
        page.wait_for_timeout(2500)
        page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)")
        page.wait_for_timeout(1500)
        print("==== URL:", page.url, "| TITLE:", page.title())
        iframes = page.evaluate("""() => Array.from(document.querySelectorAll('iframe')).map(f=>({src:f.src||'', id:f.id||''}))""")
        print("IFRAMES:", iframes)
        def dump(scope):
            return scope.evaluate(
                """() => Array.from(document.querySelectorAll('input, textarea, select')).map(el=>{
                    const lbl=(el.labels&&el.labels.length)?Array.from(el.labels).map(l=>l.innerText.trim()).join(' | '):'';
                    return {tag:el.tagName,type:el.type,name:el.name,id:el.id,label:lbl,ph:el.getAttribute('placeholder')||'',val:el.value||''};
                })"""
            )
        fields = dump(page)
        print("FIELDS:")
        for f in fields: print("  ", f)
        # hubspot iframe?
        for sel in ["iframe[src*='hsforms']", "iframe[src*='hubspot']"]:
            fr = page.query_selector(sel)
            if fr:
                cf = fr.content_frame()
                if cf:
                    hf = dump(cf)
                    print("HUBSPOT FIELDS:")
                    for f in hf: print("  ", f)
        # forms
        forms = page.evaluate("""() => Array.from(document.querySelectorAll('form')).map(f=>({action:f.action||'', method:f.method||''}))""")
        print("FORMS:", forms)
    try:
        page.close()
    except Exception:
        pass
