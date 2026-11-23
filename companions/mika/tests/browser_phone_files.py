"""Run against a disposable arm64 emulator with the debug and Android test APKs installed.

    python3 tools/mika/tests/browser_phone_files.py --device emulator-5554

The browser launches the ordinary ADB desk. The on-device test supplies only the
model replies; Android imports the attachment and exports the real result file.
"""
import argparse
from contextlib import contextmanager
import json
import hashlib
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

from playwright.sync_api import expect, sync_playwright
from test_mika_desk import SCRIPT, desk


@contextmanager
def packaged_desk(adb, serial, pkg, script):
    if pkg == "com.wangzan101.mika":
        with desk("--device", serial, script=script) as url:
            yield url
        return
    # The side-by-side APK has its own USB bootstrap file. Exercise the same --url transport as a network phone.
    with tempfile.TemporaryDirectory() as tmp:
        info_path = "/sdcard/Android/data/%s/files/mcp-usb.json" % pkg
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            result = subprocess.run(adb + ["shell", "cat", info_path], capture_output=True)
            if result.returncode == 0:
                info = json.loads(result.stdout)
                break
            time.sleep(0.2)
        else:
            raise AssertionError("The test phone did not publish its USB connection.")
        token = Path(tmp) / "device"
        token.write_text(info["token"])
        port = subprocess.check_output(adb + ["forward", "tcp:0", "tcp:%d" % info["port"]], text=True).strip()
        try:
            with desk("--url", "http://127.0.0.1:" + port, "--token-file", str(token), script=script) as url:
                yield url
        finally:
            subprocess.run(adb + ["forward", "--remove", "tcp:" + port], check=True)


def run(serial, evidence=None, dev_install=False, script=SCRIPT):
    adb = ["adb", "-s", serial]
    if not serial.startswith("emulator-") or subprocess.check_output(adb + ["shell", "getprop", "ro.kernel.qemu"]).strip() != b"1":
        raise ValueError("Pass a disposable Android emulator serial.")
    pkg = "com.wangzan101.mika"
    test = pkg + ".task.RemoteTaskFilesDeviceTest"
    if dev_install:
        pkg += ".dev"
    driver_id = str(uuid.uuid4())
    payload = "SKU,数量\n".encode() * 40_000
    png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNwyzvxHwAE9gJ8c7jPEgAAAABJRU5ErkJggg=="
    with tempfile.TemporaryFile(mode="w+") as log:
        instrument = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "class", test, "-e",
            "browserDriver", driver_id, pkg + ".test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
        try:
            with packaged_desk(adb, serial, pkg, script) as url, sync_playwright() as pw:
                browser = pw.chromium.launch()
                page = browser.new_page(locale="zh-CN", viewport={"width": 1100, "height": 760})
                page.goto(url.geturl())
                page.locator("#new-chat").click()
                expect(page.locator("#attach-file")).to_be_visible(timeout=90000)
                page.locator("#file-picker").set_input_files({"name": "清单.csv", "mimeType": "text/csv", "buffer": payload})
                page.locator("#goal").evaluate("""(field, png) => {
                    const clip = new DataTransfer();
                    clip.items.add(new File([Uint8Array.from(atob(png), c => c.charCodeAt(0))], "样例.png", {type:"image/png"}));
                    field.dispatchEvent(new ClipboardEvent("paste", {clipboardData:clip, bubbles:true, cancelable:true}));
                }""", png)
                page.locator("#goal").fill("请将这份清单作为结果文件返回。\n" + driver_id)
                expect(page.locator("#attachments .file-chip")).to_have_count(2)
                if evidence:
                    page.screenshot(path=evidence / "desktop-input.png")
                page.locator("#submit-goal").click()
                button = page.locator(".result-files button").first
                expect(button).to_be_enabled(timeout=90000)
                with page.expect_download(timeout=30000) as event:
                    button.click()
                downloaded = Path(event.value.path()).read_bytes()
                assert downloaded == payload
                if evidence:
                    page.screenshot(path=evidence / "desktop-result.png")
                # Tell the instrumented test that the external reader has received all the bytes.
                subprocess.run(adb + ["shell", "run-as", pkg, "touch", "cache/desktop-file-test-" + driver_id], check=True)
                print("Browser round trip:", len(downloaded), "bytes, SHA-256", hashlib.sha256(downloaded).hexdigest())
                browser.close()
            instrument.wait(timeout=45)
        finally:
            if instrument.poll() is None:
                instrument.terminate()
                instrument.wait(timeout=10)
            log.seek(0)
            report = log.read()
            print(report)
        if "OK (1 test)" not in report:
            raise AssertionError("Android did not confirm the transfer")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--evidence", type=Path, help="Existing directory for browser screenshots")
    parser.add_argument("--dev-install", action="store_true", help="Use the side-by-side -PdevInstall APK")
    parser.add_argument("--client", type=Path, default=SCRIPT, help="Desktop entry script or packaged Python zipapp")
    args = parser.parse_args()
    run(args.device, args.evidence, args.dev_install, args.client)
