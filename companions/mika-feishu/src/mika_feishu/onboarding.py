"""Feishu's official device registration flow; credentials never appear in the QR page."""
import base64
import getpass
import html
from pathlib import Path
import tempfile
import urllib.parse
import webbrowser

from .api import validate_url
from .config import read_config, save_config

ADDONS = {
    "preset": False,
    "scopes": {"tenant": ["im:message.p2p_msg:readonly", "im:message:send_as_bot"], "user": []},
    "events": {"items": {"tenant": ["im.message.receive_v1"]}},
}


def qr_page(info, directory, open_browser=True):
    import qrcode
    from qrcode.image.svg import SvgPathImage
    url = info.get("url", "")
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname != "accounts.feishu.cn" or parsed.username or parsed.password:
        raise ValueError("Unexpected Feishu registration URL; no browser was opened")
    image = qrcode.make(url, image_factory=SvgPathImage).to_string()
    src = "data:image/svg+xml;base64," + base64.b64encode(image).decode()
    page = Path(directory) / "feishu-setup.html"
    page.write_text("""<!doctype html><html lang="en"><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<meta name="referrer" content="no-referrer"><title>Mika · Connect Feishu</title>
<style>body{font:18px system-ui;max-width:640px;margin:48px auto;padding:24px}img{width:320px;max-width:100%}a{overflow-wrap:anywhere}</style>
<h1>Connect your Feishu bot / 连接飞书机器人</h1>
<p>Scan in Feishu and review the app and permissions. 扫码后核对应用及权限。</p>
<img alt="Feishu authorization QR code" src=""" + '"' + src + '"' + "><p><a href=\"" + html.escape(url, quote=True) + "\">Open Feishu authorization / 打开授权链接</a></p>"
                    "<p>Return to the terminal after authorization. 授权后回到终端继续。</p></html>", encoding="utf-8")
    page.chmod(0o600)
    print("Scan in Feishu or open this authorization link (do not share it):\n" + url)
    qr = qrcode.QRCode(border=2)
    qr.add_data(url)
    qr.make(fit=True)
    qr.print_ascii(invert=True)
    if open_browser:
        webbrowser.open(page.as_uri())
    return page


def register(mode, app_id=None, open_browser=True, register_app=None):
    if register_app is None:
        import lark_oapi as lark
        register_app = getattr(lark, "register_app", None)
    if not callable(register_app):
        raise ValueError("This SDK lacks register_app; upgrade mika-feishu or use manual setup")
    options = {"source": "mika-feishu", "addons": ADDONS,
               "domain": "https://accounts.feishu.cn", "lark_domain": "https://accounts.feishu.cn"}
    if mode == "create":
        options["create_only"] = True
        options["app_preset"] = {"name": "Mika", "desc": "Send tasks to your own Android phone"}
    elif mode == "existing":
        if app_id:
            if not app_id.startswith("cli_"):
                raise ValueError("An existing application ID must start with cli_")
            options["app_id"] = app_id
        # Without an ID, Feishu's authorization page lets the user select an app.
    else:
        raise ValueError("Unknown registration mode")
    with tempfile.TemporaryDirectory(prefix="mika-feishu-") as directory:
        result = register_app(on_qr_code=lambda info: qr_page(info, directory, open_browser), **options)
    if not isinstance(result, dict) or not result.get("client_id") or not result.get("client_secret"):
        raise ValueError("Registration did not return application credentials")
    brand = (result.get("user_info") or {}).get("tenant_brand")
    if brand and brand != "feishu":
        raise ValueError("This connector currently supports Feishu; Lark tenants are not supported")
    # Deliberately require local pairing even when registration returns a user ID.
    return result["client_id"], result["client_secret"]


def setup(path, mode=None, open_browser=True):
    previous = read_config(path)
    print("Credentials stay in your local config; secret inputs are not echoed.")
    if mode is None:
        print("1. Scan to create a new bot / 扫码新建\n2. Link an existing app / 关联已有应用\n3. Enter credentials / 手动填写")
        mode = {"1": "create", "2": "existing", "3": "manual"}.get(input("Choose [3]: ").strip() or "3")
        if mode is None:
            raise ValueError("Choose 1, 2 or 3")
    def ask(key, prompt, secret=False):
        old = previous.get(key, "")
        hint = (" [Enter to keep]" if secret else " [" + old + "]") if old else ""
        return ((getpass.getpass if secret else input)(prompt + hint + ": ").strip() or old)
    config = {}
    # Validate the phone target before starting an external authorization flow.
    config["mika_url"] = ask("mika_url", "Mika URL, e.g. http://100.64.1.2:8765")
    validate_url(config["mika_url"])
    config["mika_token"] = ask("mika_token", "Mika network access token (not a Tailscale key)", True)
    if not config["mika_token"]:
        raise ValueError("A Mika network access token is required")
    if mode == "manual":
        config["feishu_app_id"] = ask("feishu_app_id", "Feishu App ID")
        config["feishu_app_secret"] = ask("feishu_app_secret", "Feishu App Secret", True)
    else:
        app_id = input("Existing App ID (Enter to choose on the authorization page): ").strip() if mode == "existing" else None
        config["feishu_app_id"], config["feishu_app_secret"] = register(mode, app_id, open_browser)
    if not config.get("feishu_app_id", "").startswith("cli_") or not config.get("feishu_app_secret"):
        raise ValueError("A Feishu App ID and App Secret are required")
    config["allowed_open_ids"] = (previous.get("allowed_open_ids", [])
                                   if config["feishu_app_id"] == previous.get("feishu_app_id") else [])
    save_config(config, path)
    print("Saved. Run mika-feishu doctor, then mika-feishu run. Pair using the one-time code printed by run.")
