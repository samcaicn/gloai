from playwright.sync_api import sync_playwright

APPLY = "https://signpath.org/apply"

FILLS = {
    "2-198696014/name": "SafeOPC",
    "2-198696014/repository_url": "https://github.com/samcaicn/safeopc",
    "2-198696014/homepage_url": "https://github.com/samcaicn/safeopc",
    "2-198696014/download_url": "https://github.com/samcaicn/safeopc/releases",
    "2-198696014/tagline": "Open-source AI-native company framework with a code-signed Windows installer.",
    "2-198696014/description": (
        "SafeOPC is an open-source AI-native company framework (originally samcaicn/safeopc). "
        "This public repository (samcaicn/safeopc) hosts the Windows desktop installer (NSIS .exe) "
        "for end-user distribution. We request free OSS Authenticode signing so Windows users "
        "no longer see SmartScreen / 'Unknown Publisher' warnings when downloading and running "
        "the installer. The repository is public and open-source."
    ),
    "2-198696014/reputation": (
        "SafeOPC is developed by SafeOPC (The University of Hong Kong) and has an active open-source "
        "community on GitHub. The desktop installer targets Windows end-users who currently face "
        "SmartScreen / Unknown Publisher warnings; code signing will establish trust for these users."
    ),
    "0-1/email": "tuptup@qq.com",
}

REQUIRED_CHECKS = [
    "LEGAL_CONSENT.subscription_type_2099806800",  # Code of Conduct (required)
    "LEGAL_CONSENT.processing",                     # store/process personal data (required)
]

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
    ctx = browser.contexts[0] if browser.contexts else browser.new_context()
    page = ctx.new_page()
    page.goto(APPLY, wait_until="load", timeout=30000)
    page.wait_for_selector("iframe[src*='hsforms']", timeout=20000)
    frame = page.query_selector("iframe[src*='hsforms']").content_frame()
    frame.wait_for_selector('[name="2-198696014/name"]', timeout=20000)

    done, failed = [], []
    for name, val in FILLS.items():
        try:
            el = frame.locator(f'[name="{name}"]')
            el.fill(val, timeout=8000)
            done.append(name)
        except Exception as e:
            failed.append((name, str(e)[:120]))

    checked, check_failed = [], []
    for name in REQUIRED_CHECKS:
        try:
            cb = frame.locator(f'[name="{name}"]')
            cb.check(force=True, timeout=8000)
            checked.append(name)
        except Exception as e:
            # fallback: click the visible label
            try:
                frame.locator(f'label:has([name="{name}"])').click(timeout=8000)
                checked.append(name + " (via label)")
            except Exception as e2:
                check_failed.append((name, str(e2)[:120]))

    print("FILLED OK:", done)
    print("FILL FAILED:", failed)
    print("CHECKED OK:", checked)
    print("CHECK FAILED:", check_failed)
    print("LEFTOVER FOR USER: First Name, Last Name, Maintainer Type, Build System, Primary Discovery Channel, then Submit + captcha.")
    print("PAGE_URL:", page.url)
    # intentionally NOT closing browser/page so the pre-filled tab stays in user's Brave
