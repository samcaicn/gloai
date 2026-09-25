#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
创建 / 查询 WeAuto License 的 Creem 多档套餐（普通月租 / 高级月租 / 永久）。

背景与硬性约束（别改错）：
  1) Creem 只支持 USD / EUR 计价 -> 人民币价格必须折算成美元后才能建套餐。
  2) 月租 = 订阅（recurring），必须用 billing_type="recurring" + billing_period="every-month"
     （实测 "month"/"monthly" 均被拒，合法枚举是 every-month/every-year/.../once）。
  3) 永久 = 一次性（onetime）+ pay_what_you_want:true，"自愿付款"，price 字段即最低价。
  4) price 单位是**分（cents）**，必须 >= 100（$1.00）。
  5) 本机到 api.creem.io 有 Cloudflare WAF：不带浏览器 UA 会被挡成 HTTP 403 / error code 1010；
     且必须绕开本机 Clash 代理（ProxyHandler({}) 直连）。
  6) 旧的 "billing_period":"once" 带上会触发 API bug 被误判 recurring（文档却说合法，以实测为准）；
     onetime 产品**不要**传 billing_period。

用法：
  set CREEM_API_KEY=creem_xxx
  python create_creem_product.py --mode prod            # 真创建 3 档
  python create_creem_product.py --mode prod --apply     # 创建并写回 wrangler.toml 的 CREEM_PRODUCTS
  python create_creem_product.py --mode prod --dry-run   # 只算钱/打印 body，不请求

  --no-exact       改回 .99 心理定价档（如 29.9->4.99）。默认精确换算
  --no-auto-rate   不拉实时汇率，用内置回退
  --rate 6.71      手动指定 USD/CNY 汇率
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

BASE = {
    "prod": "https://api.creem.io/v1",
    "test": "https://test-api.creem.io/v1",
}

# 必须伪装成浏览器 UA，否则 Cloudflare 直接 403 (error code: 1010)
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

# 回退汇率用 USD/CNY（即 1 CNY = 多少 USD）。2026-09-25 ≈ 0.14895
DEFAULT_RATE = 0.14895
MIN_CENTS = 100

# 实时汇率源（本机到 api.creem.io 直连；这两个源实测可达）；取不到则用 DEFAULT_RATE
FX_URLS = [
    "https://api.frankfurter.app/latest?from=CNY&to=USD",
    "https://open.er-api.com/v6/latest/CNY",
]

# ---- 三档套餐定义（人民币标价，脚本自动折算 USD）----
#  tier:     内部标识（/buy?go=1&tier= 用，也写进 wrangler.toml）
#  label:    /buy 页面显示的档位名
#  price_text: /buy 页面显示的价格文案
#  cny:      人民币价（用于折算 USD cents）
#  billing:  monthly（订阅）| once（一次性 + 自愿付款）
#  name/desc: Creem 后台产品名/描述
#  features: /buy 页面该档的功能描述
TIERS = [
    {
        "tier": "normal",
        "label": "普通版（月租）",
        "price_text": "¥29.9 / 月",
        "cny": 29.9,
        "billing": "monthly",
        "name": "WeAuto 普通版（月租）",
        "desc": "WeAuto 微信机器人普通授权，按月订阅。付款后在收款邮箱获得卡密，在 WeAuto 后台「授权管理」粘贴激活。",
        "features": "基础自动回复 + 授权管理 + 标准 AI 额度",
    },
    {
        "tier": "premium",
        "label": "高级版（月租）",
        "price_text": "¥39.9 / 月",
        "cny": 39.9,
        "billing": "monthly",
        "name": "WeAuto 高级版（月租）",
        "desc": "WeAuto 微信机器人高级授权，按月订阅，解锁全部功能与优先支持。付款后在收款邮箱获得卡密，在后台「授权管理」粘贴激活。",
        "features": "全部功能 + 优先支持 + 更高 AI 额度",
    },
    {
        "tier": "lifetime",
        "label": "永久授权",
        "price_text": "¥199（自愿支持）",
        "cny": 199.0,
        "billing": "once",
        "name": "WeAuto 永久授权（自愿支持）",
        "desc": "WeAuto 微信机器人永久授权，一次买断长期使用。自愿付款，不低于 ¥199。付款后在收款邮箱获得卡密，在后台「授权管理」粘贴激活。",
        "features": "一次买断 · 永久可用 · 全功能",
    },
]


def fetch_rate(auto):
    """自动拉 USD/CNY 汇率（1 CNY = ? USD）；auto=False 直接回退。返回 (rate, 来源说明)。"""
    if not auto:
        return DEFAULT_RATE, "内置回退"
    for u in FX_URLS:
        try:
            req = urllib.request.Request(u, headers={"User-Agent": UA, "Accept": "application/json"})
            with opener().open(req, timeout=8) as r:
                j = json.loads(r.read().decode("utf-8", "replace"))
            if "frankfurter" in u and "rates" in j and "USD" in j["rates"]:
                return float(j["rates"]["USD"]), "frankfurter.app"
            if j.get("result") == "success" and "rates" in j and "USD" in j["rates"]:
                return float(j["rates"]["USD"]), "open.er-api.com"
        except Exception:
            continue
    return DEFAULT_RATE, "拉取失败,用内置回退"


def opener():
    """强制直连（绕开本机 Clash 127.0.0.1:17890，它对 api.creem.io 不通）。"""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def creem_request(method, url, key, body=None, idem=None, timeout=45):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {
        "User-Agent": UA,
        "Accept": "application/json",
        "Content-Type": "application/json",
        "x-api-key": key,
    }
    if idem:
        headers["Idempotency-Key"] = idem
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with opener().open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = {"raw": raw[:500]}
        return e.code, parsed
    except Exception as e:  # 网络层
        return 0, {"error": "%s: %s" % (type(e).__name__, str(e)[:200])}


def cny_to_cents(cny, rate, exact):
    """人民币 -> 美元分。rate = USD/CNY（1 CNY = ? USD）。返回 (cents, 说明)。"""
    raw = cny * rate * 100
    if exact:
        return max(MIN_CENTS, int(round(raw))), "精确换算"
    usd_floor = raw / 100
    target = int(usd_floor) + 0.99
    if target < usd_floor:
        target += 1.0
    return max(MIN_CENTS, int(round(target * 100))), "向上取整到 .99 档"


def build_body(tier, cents):
    """按档位构造 Creem 建产品 body（实测合法字段）。"""
    b = {
        "name": tier["name"],
        "description": tier["desc"],
        "price": cents,
        "currency": "USD",
        "tax_mode": "inclusive",
        "tax_category": "saas",
    }
    if tier["billing"] == "monthly":
        # 订阅：合法枚举是 every-month（"month"/"monthly" 都被 Creem 拒）
        b["billing_type"] = "recurring"
        b["billing_period"] = "every-month"
    else:
        # 一次性 + 自愿付款：price 即最低价，suggested_price 预填建议额
        b["billing_type"] = "onetime"
        b["pay_what_you_want"] = True
        b["suggested_price"] = cents
    return b


def apply_wrangler(products):
    """把 CREEM_PRODUCTS（JSON 数组）写回 wrangler.toml 的 [vars]，并清掉旧的单产品变量。"""
    p = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "wrangler.toml"))
    if not os.path.exists(p):
        return "未找到 " + p
    txt = open(p, encoding="utf-8").read()
    # 清掉旧的单产品变量行（已被 CREEM_PRODUCTS 取代）
    for var in ("CREEM_PRODUCT_ID", "CREEM_CHECKOUT_URL", "PRODUCT_NAME",
                "PRODUCT_PRICE", "PRODUCT_DESC"):
        txt = re.sub(r'^\s*' + var + r'\s*=\s*"[^"]*"\s*\n', "", txt, flags=re.M)
    arr = json.dumps(products, ensure_ascii=False)
    # 用 TOML 字面量字符串（单引号）包裹 JSON：内部双引号/中文无需转义，
    # 绕开 wrangler TOML 解析器对「行内数组 of 内联表」的报错。
    line = "CREEM_PRODUCTS = '%s'\n" % arr
    if re.search(r'^\s*CREEM_PRODUCTS\s*=', txt, flags=re.M):
        txt, n = re.subn(r'^\s*CREEM_PRODUCTS\s*=\s*.*(\n|$)',
                          lambda m: line, txt, count=1, flags=re.M)
    else:
        # 插到 [vars] 段首行之后
        txt = re.sub(r'(\[vars\])', lambda m: m.group(1) + "\n" + line, txt, count=1)
    open(p, "w", encoding="utf-8").write(txt)
    return "wrangler.toml 已更新 CREEM_PRODUCTS（%d 档）" % len(products)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="prod", choices=["prod", "test"])
    ap.add_argument("--key", default=os.environ.get("CREEM_API_KEY", ""))
    ap.add_argument("--rate", type=float, default=0.0, help="手动 USD/CNY 汇率，>0 时覆盖自动")
    ap.add_argument("--no-exact", action="store_true", help="改用 .99 心理定价档")
    ap.add_argument("--no-auto-rate", action="store_true", help="不拉实时汇率")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()

    if a.rate and a.rate > 0:
        rate, rate_src = a.rate, "手动指定"
    else:
        rate, rate_src = fetch_rate(not a.no_auto_rate)
    exact = not a.no_exact

    print("=" * 64)
    print("汇率 %.5f USD/CNY (%s)  ≈ 1 USD = %.4f CNY" % (rate, rate_src, 1.0 / rate))
    print("=" * 64)

    if a.dry_run:
        for t in TIERS:
            cents, note = cny_to_cents(t["cny"], rate, exact)
            print("\n[%s] %s  ¥%.1f -> $%.2f (%d cents) [%s]" % (
                t["tier"], t["label"], t["cny"], cents / 100.0, cents, note))
            print(json.dumps(build_body(t, cents), ensure_ascii=False, indent=2))
        return 0

    if not a.key:
        print("[!] 缺 API key。Creem > Developers > API Keys 复制，然后：")
        print('    set CREEM_API_KEY=creem_xxx  &&  python %s --mode %s --apply'
              % (os.path.basename(__file__), a.mode))
        return 2

    base = BASE[a.mode]
    created = []
    for t in TIERS:
        cents, note = cny_to_cents(t["cny"], rate, exact)
        print("\n[%s] %s  ¥%.1f -> $%.2f (%d cents) [%s]" % (
            t["tier"], t["label"], t["cny"], cents / 100.0, cents, note))
        body = build_body(t, cents)
        idem = "weauto-%s-%s" % (a.mode, t["tier"])
        status, data = creem_request("POST", base + "/products", a.key, body, idem=idem)
        pid = data.get("id")
        print("    HTTP", status, json.dumps({k: data.get(k) for k in
              ("id", "mode", "billing_type", "billing_period", "price",
               "pay_what_you_want", "status")}, ensure_ascii=False))
        if not pid:
            print("    [x] 未拿到 product_id：", json.dumps(data, ensure_ascii=False)[:300])
            return 1
        created.append({
            "tier": t["tier"],
            "label": t["label"],
            "price_text": t["price_text"],
            "product_id": pid,
            "billing": t["billing"],
            "features": t["features"],
        })

    print("\n" + "=" * 64)
    print("创建完成（共 %d 档）：" % len(created))
    for c in created:
        print("  %-8s %-14s %s" % (c["tier"], c["price_text"], c["product_id"]))
    print("=" * 64)

    if a.apply:
        print("  ", apply_wrangler(created))

    print("""
必做的后台操作（API 做不了）：
  1) 逐个打开这 3 个产品 -> 开启 License Keys（否则买家付完拿不到卡密，流程断）
  2) Developers -> Webhooks 填 https://weauto-license.tuptup-workbuddy.workers.dev/webhook，
     把 Webhook Secret 发我
  3) 月租是订阅制：Creem 的 Alipay 已知只在一次性收银台出现，订阅档可能无支付宝入口
     （只能信用卡等）；若你要月租也走支付宝，需把月租改成一次性"可续费"方案，告知我即可
  4) Balance -> Payout Account 绑支付宝（商家收款，与买家付款两条独立链路）
""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
