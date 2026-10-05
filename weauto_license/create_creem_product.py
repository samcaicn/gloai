#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
创建 / 查询 WeAuto License 的 Creem 多档套餐（普通月租 / 高级月租 / 永久）。

背景与硬性约束（别改错）：
  1) Creem 只支持 USD / EUR 计价 -> 人民币价格必须折算成美元后才能建套餐。
  2) 全部档位均为<b>一次性（onetime）</b>：初级/中级为固定价一次性；高级为一次性 + 自愿付款（pay_what_you_want）。
     一次性套餐无 Creem 自动续订，由本软件在 valid_days 到期前提示用户再次购买（续费）。
  3) 旧文档里的「月租=订阅」已弃用：Creem 的支付宝只在一次性收银台出现，故全部改为一次性以支持支付宝。
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
  python create_creem_product.py --mode prod --sync-desc  # **只改文案**：PATCH 已有产品的
                                                        # name/description，不动价格与销量记录

  --no-exact       改回 .99 心理定价档（如 29.9->4.99）。默认精确换算
  --no-auto-rate   不拉实时汇率，用内置回退
  --rate 6.71      手动指定 USD/CNY 汇率

⚠️ 本机**没有**当前 Creem 账户的 API key（只在 Worker secret 里）。若拿旧 key 跑，
   所有产品查询/建 checkout 都会 404 `Product not found` —— 这**不代表产品不存在**
   （实测：旧 key 404，线上 Worker 用同一 product_id 302 正常）。
   404 时先跑 `python create_creem_product.py --probe` 自检，别急着重建产品。
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
        "label": "初级 套餐费",
        "price_text": "¥19.9",
        "cny": 19.9,
        "billing": "once",
        "voluntary": False,
        "valid_days": 30,
        "name": "WeAuto 初级套餐费（一次性）",
        "desc": "WeAuto 微信机器人初级授权，一次性购买，获得 30 天使用权。到期前软件会提示续费。",
        "features": "基础自动回复 + 授权管理 + 标准 AI 额度",
    },
    {
        "tier": "premium",
        "label": "中级 套餐费",
        "price_text": "¥59.9",
        "cny": 59.9,
        "billing": "once",
        "voluntary": False,
        "valid_days": 30,
        "name": "WeAuto 中级套餐费（一次性）",
        "desc": "WeAuto 微信机器人中级授权，一次性购买，获得 30 天使用权，解锁全部功能与优先支持。到期前软件会提示续费。",
        "features": "全部功能 + 优先支持 + 更高 AI 额度",
    },
    {
        "tier": "lifetime",
        "label": "高级 套餐费",
        "price_text": "¥199",
        "cny": 199.0,
        "billing": "once",
        "voluntary": True,
        "valid_days": 365,
        "name": "WeAuto 高级套餐费（一次性·自愿支持）",
        "desc": "WeAuto 微信机器人高级授权，一次性购买，获得 365 天使用权，全功能。自愿付款，不低于 ¥199。到期前软件会提示续费。",
        "features": "一次购买 · 365天全功能 · 优先支持",
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
    # CREEM_PROXY=http://127.0.0.1:10808 可强制走本机代理（直连被墙时用）
    px = os.environ.get("CREEM_PROXY", "")
    if px:
        return urllib.request.build_opener(urllib.request.ProxyHandler({"http": px, "https": px}))
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


def read_product_ids():
    """从 wrangler.toml 的 CREEM_PRODUCTS 读出 [{tier, product_id}]。

    --sync-desc 用它定位「已存在的产品」：改文案必须 PATCH 已有产品，
    绝不能重建（重建会丢销量/评价/统计，且旧卡密仍指向旧产品）。
    """
    p = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "wrangler.toml"))
    if not os.path.exists(p):
        return []
    txt = open(p, encoding="utf-8").read()
    m = re.search(r"^\s*CREEM_PRODUCTS\s*=\s*'(\[.*?\])'\s*$", txt, flags=re.M | re.S)
    if not m:
        return []
    try:
        return [(x.get("tier"), x.get("product_id")) for x in json.loads(m.group(1))]
    except Exception:
        return []


def sync_price(base, key, rate, exact):
    """把各档价格按汇率折算后 PATCH 到已有产品（不新建、不动文案）。

    为什么需要单独一个模式（2026-10-05 实测踩坑）：
      wrangler.toml 里的 `price_text`（购买页展示价，如 "¥19.9"）是**硬编码文案**，
      与 Creem 实际收费**没有任何联动**。2026-10-03 建档时价格算错，导致
      初级档线上实收 $1.01（约 ¥6.8），而购买页写着 ¥29.9 —— 每单少收 ¥23。
      所以「改文案」必须与「校价格」分开：前者随时可做，后者动的是钱，要显式开关。

    ⚠️ 若本机没有 Creem API key（key 只在 Worker secret），本函数用不了 ——
       改用 Worker 侧的一次性运维接口 `/creem/price`（它用 Worker 自己的 secret），
       目标价读 wrangler.toml 的 `usd_cents` 字段。详见 weauto_license/README.md。
    """
    ids = dict((t, pid) for t, pid in read_product_ids() if pid)
    if not ids:
        print("[x] wrangler.toml 里没解析到 CREEM_PRODUCTS，无法定位产品")
        return 1
    print("价格校准（PATCH 已有产品的 price，不新建）：")
    print("  %-9s %-9s %-11s %-11s %s" % ("档位", "目标CNY", "应为(USD)", "线上现价", "动作"))
    plan = []
    for t in TIERS:
        pid = ids.get(t["tier"])
        if not pid:
            print("  [跳过] %s：wrangler.toml 无 product_id" % t["tier"])
            continue
        cents, _ = cny_to_cents(t["cny"], rate, exact)
        st, d = creem_request("GET", base + "/products/" + pid, key, timeout=30)
        cur = d.get("price") if st == 200 else None
        same = (cur == cents)
        print("  %-9s ¥%-8.1f $%-10.2f %-11s %s"
              % (t["tier"], t["cny"], cents / 100.0,
                 ("$%.2f" % (cur / 100.0)) if isinstance(cur, (int, float)) else "查询失败",
                 "已一致" if same else "需修正"))
        plan.append((t, pid, cents, same))

    todo = [x for x in plan if not x[3]]
    if not todo:
        print("\n=> 三档价格均已与目标一致，无需改动。")
        return 0
    if os.environ.get("CONFIRM_PRICE") != "YES":
        print("\n[x] 价格涉及真实收款，未执行修正。")
        print("    确认无误后加环境变量重跑：set CONFIRM_PRICE=YES")
        print("    （可先改 TIERS 里的 cny 值来调整目标价，再执行）")
        return 2

    rc = 0
    for t, pid, cents, _same in todo:
        st, d = creem_request("PATCH", base + "/products/" + pid, key, {"price": cents})
        ok = 200 <= st < 300 and d.get("price") == cents
        print("  [%s] %-9s %s -> %d cents ($%.2f)  HTTP %s  回读 %s"
              % ("OK" if ok else "!!", t["tier"], pid, cents, cents / 100.0, st, d.get("price")))
        if not ok:
            print("      ", json.dumps(d, ensure_ascii=False)[:240])
            rc = 1
    return rc


def probe(base, key):
    """只读自检：key 是否有效 + wrangler.toml 里的 product_id 是否属于这个账户。

    存在的必要性（2026-10-05 实测踩坑）：本机保存的 key 是**换账户前的旧 key**，
    拿它查任何产品都返回 404 `Product not found`。若不知道这点，极易误判成
    「产品不存在」→ 去重建产品 → 丢销量/评价/统计，线上还是收不到款。
    所以这里在拿到 404 时**自动去线上 Worker 交叉验证**：线上能跳收银台即证明
    产品存在、只是本地 key 不对。
    """
    ids = dict((t, pid) for t, pid in read_product_ids() if pid)
    print("=" * 64)
    print("PROBE（只读，不创建不修改）")
    print("=" * 64)
    if not key:
        print("[!] 未提供 API key。本机通常没有当前账户的 key（只在 Worker secret）。")
        print("    可先跑线上交叉验证（不需要 key）：")
        return _probe_online(ids) or 2
    if not ids:
        print("[x] wrangler.toml 里没解析到 CREEM_PRODUCTS")
        return 1

    # 第一步：key 有效性。用 /webhooks（无副作用，且能区分 401 与 404）
    st, _ = creem_request("GET", base + "/webhooks", key, timeout=30)
    print("\n[1] key 连通性  GET %s/webhooks -> HTTP %s  %s"
          % (base, st, "OK" if st == 200 else "!!"))
    if st == 401:
        print("    -> key 无效/已轮换。先去 Creem 后台取新 key，别动产品。")
        return 1
    if st == 200:
        # 200 只说明「这个 key 有效」，不代表它属于当前账户——换过账户的旧 key
        # 同样 200，但查本账户产品会全 404。真正的判据在第 3 步的线上交叉验证。
        print("    注意：200 只代表 key 有效。若下面全 404，多半是**换账户前的旧 key**。")

    # 第二步：逐档查产品
    missing = []
    price_bad = []
    print("\n[2] wrangler.toml 里的 product_id + 线上实收价")
    rate = fetch_rate(True)[0]
    cny_by_tier = dict((t["tier"], t["cny"]) for t in TIERS)
    for tier, pid in sorted(ids.items()):
        st, d = creem_request("GET", base + "/products/" + pid, key, timeout=30)
        if st == 200:
            cur = d.get("price")
            note = ""
            if tier in cny_by_tier and isinstance(cur, int):
                want, _ = cny_to_cents(cny_by_tier[tier], rate, True)
                if cur != want:
                    # 购买页展示价 price_text 是硬编码的，与这里没有任何联动：
                    # 展示 ¥29.9 实际只收 $1.01 就是靠这个漏洞 quietly 发生的。
                    note = "  <== 价格异常！页面写 %s，线上实收 $%.2f（约 ¥%.2f）" % (
                        dict((t["tier"], t["price_text"]) for t in TIERS).get(tier, "?"),
                        cur / 100.0, cur / 100.0 * (1.0 / rate))
                    price_bad.append(tier)
            print("    [OK]   %-9s %s  $%-7.2f %s" % (tier, pid, (cur or 0) / 100.0, note))
        else:
            print("    [404]  %-9s %s" % (tier, pid))
            missing.append((tier, pid))

    if price_bad:
        print("\n[!!] %d 档价格与购买页展示价不一致：%s"
              % (len(price_bad), ", ".join(price_bad)))
        print("     这意味着买家按页面价付款、实际只收到差额 —— 每单少收。")
        print("     修法：跑 `--sync-price`（会先打印对照表，确认后加 CONFIRM_PRICE=YES 执行）。")

    if missing:
        print("\n[3] 这 %d 档在本地 key 下查不到。**先别重建产品**——做线上交叉验证："
              % len(missing))
        if _probe_online({t: p for t, p in missing}):
            print("\n=> 结论：产品在线上是正常的，**本机 key 不是当前账户的**。")
            print("   正确做法：去 Creem 后台 Settings > API Keys 取当前 key，")
            print("   然后再跑 --sync-desc / --apply。本地不要重建产品。")
            return 3
        print("\n=> 结论：线上也不通。这才需要排查 Worker 部署或产品状态。")
        return 1

    print("\n=> 全部档位可用，可以安全执行 --sync-desc。")
    return 0


def _probe_online(ids):
    """不带 key 的旁路验证：直接问线上 Worker 要收银台跳转。

    能 302 到 pay.jukuai.net 即证明「产品存在且 Worker 配置正确」——
    这与本地 key 无关。返回 True = 线上全通。
    """
    origin = os.environ.get("WEAUTO_WORKER_URL", "https://wetech.jukuai.net").rstrip("/")
    print("\n    线上交叉验证（无需 key） %s/buy?go=1&tier=..." % origin)
    class _NoRedir(urllib.request.HTTPRedirectHandler):
        """禁掉自动跟随：我们要看的是 Worker 回的 302 Location，而不是最终页面 200。"""

        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    # NoRedirect 必须排在 ProxyHandler 之前传进 build_opener：urllib 按
    # handler_order 匹配到默认 HTTPRedirectHandler 就会跟随跳转，那样只能看到
    # 收银台页面的 200，会把「不通」误判成「通」。
    opener = urllib.request.build_opener(_NoRedir(), urllib.request.ProxyHandler({}))
    all_ok = True
    for tier, _pid in sorted(ids.items()):
        u = "%s/buy?go=1&tier=%s&mid=probe" % (origin, tier)
        try:
            r = opener.open(urllib.request.Request(u, headers={"User-Agent": UA}), timeout=30)
            code, loc = r.status, r.headers.get("Location", "")
        except urllib.error.HTTPError as e:
            code, loc = e.code, e.headers.get("Location", "")
        except Exception as e:
            code, loc = 0, str(e)[:80]
        good = code in (301, 302, 303, 307, 308) and "pay." in loc
        all_ok = all_ok and good
        print("      %-9s -> HTTP %-3s %s  %s"
              % (tier, code, "[OK] 线上可收款" if good else "[!!] 不通", loc[:70]))
    return all_ok


def sync_desc(base, key):
    """把 TIERS 里的 name/desc 同步到 Creem 已有产品（只改文案，不动价格）。"""
    ids = dict((t, pid) for t, pid in read_product_ids() if pid)
    if not ids:
        print("[x] wrangler.toml 里没解析到 CREEM_PRODUCTS，无法定位产品")
        return 1
    rc = 0
    for t in TIERS:
        pid = ids.get(t["tier"])
        if not pid:
            print("  [跳过] %s：wrangler.toml 无 product_id" % t["tier"])
            rc = 1
            continue
        status, data = creem_request("PATCH", base + "/products/" + pid, key,
                                     {"name": t["name"], "description": t["desc"]})
        ok = 200 <= status < 300
        print("  [%s] %-8s %s  HTTP %s" % ("OK" if ok else "!!", t["tier"], pid, status))
        if not ok:
            print("      ", json.dumps(data, ensure_ascii=False)[:300])
            # 404 多半是「key 不属于当前账户」，而不是产品不存在（实测踩过）。
            # 此时重建产品会造成「线上仍收不到款 + 丢销量统计」双重损失，必须拦下。
            if status == 404:
                print("      ⚠️ 404：这通常说明 API key 不属于当前 Creem 账户，")
                print("         **不是产品不存在**。请先跑 `--probe` 自检，")
                print("         确认 key 有效后再执行；切勿重建产品。")
            rc = 1
    return rc


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
        # 一次性：固定价（voluntary）才开 pay_what_you_want；普通一次性套餐给固定价
        b["billing_type"] = "onetime"
        if tier.get("voluntary"):
            b["pay_what_you_want"] = True
            b["suggested_price"] = cents
    return b


def apply_wrangler(products):
    """把 CREEM_PRODUCTS（JSON 数组）写回 wrangler.toml 的 [vars]，并清掉旧的单产品变量。

    返回 (ok, msg)：**ok=False 表示没有真正写入**。以前无论正则有没有命中都打印
    「已更新」，用户以为配置生效了、实际没写进去（部署后 /buy 一直未开放）。
    """
    p = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "wrangler.toml"))
    if not os.path.exists(p):
        return False, "未找到 " + p
    try:
        txt = open(p, encoding="utf-8").read()
    except Exception as e:
        return False, "读取 %s 失败: %s" % (p, e)
    # 清掉旧的单产品变量行（已被 CREEM_PRODUCTS 取代）；单引号与双引号都要匹配
    for var in ("CREEM_PRODUCT_ID", "CREEM_CHECKOUT_URL", "PRODUCT_NAME",
                "PRODUCT_PRICE", "PRODUCT_DESC"):
        txt = re.sub(r'^\s*' + var + r'\s*=\s*(?:"[^"]*"|\'[^\']*\')\s*(?:\n|$)',
                     "", txt, flags=re.M)
    arr = json.dumps(products, ensure_ascii=False)
    # 用 TOML 字面量字符串（单引号）包裹 JSON：内部双引号/中文无需转义，
    # 绕开 wrangler TOML 解析器对「行内数组 of 内联表」的报错。
    line = "CREEM_PRODUCTS = '%s'\n" % arr
    if re.search(r'^\s*CREEM_PRODUCTS\s*=', txt, flags=re.M):
        txt, n = re.subn(r'^\s*CREEM_PRODUCTS\s*=\s*.*(\n|$)',
                         lambda m: line, txt, count=1, flags=re.M)
        if n != 1:
            return False, "替换 CREEM_PRODUCTS 失败：正则未命中（n=%d），文件未改动" % n
    else:
        # key 完全缺失：插到 [vars] 段首行之后；连 [vars] 都没有就整段追加
        txt, n = re.subn(r'(\[vars\])', lambda m: m.group(1) + "\n" + line,
                         txt, count=1)
        if n != 1:
            txt = txt.rstrip("\n") + "\n\n[vars]\n" + line
    try:
        with open(p, "w", encoding="utf-8") as f:
            f.write(txt)
    except Exception as e:
        return False, "写入 %s 失败: %s" % (p, e)
    # 回读校验：确认真的落盘（防止正则/写入失败导致「谎报成功」）
    try:
        back = open(p, encoding="utf-8").read()
    except Exception as e:
        return False, "写入后回读失败: %s" % e
    if arr not in back:
        return False, "写入后校验失败：%s 中未找到新的 CREEM_PRODUCTS 内容，请手动检查" % p
    return True, "wrangler.toml 已更新 CREEM_PRODUCTS（%d 档）" % len(products)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="prod", choices=["prod", "test"])
    ap.add_argument("--key", default=os.environ.get("CREEM_API_KEY", ""))
    ap.add_argument("--rate", type=float, default=0.0, help="手动 USD/CNY 汇率，>0 时覆盖自动")
    ap.add_argument("--no-exact", action="store_true", help="改用 .99 心理定价档")
    ap.add_argument("--no-auto-rate", action="store_true", help="不拉实时汇率")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--sync-desc", action="store_true",
                    help="只把 name/description 同步到已有产品（不改价格、不重建）")
    ap.add_argument("--probe", action="store_true",
                    help="自检：只读验证 key 与 wrangler.toml 里的 product_id（不建不改）")
    ap.add_argument("--sync-price", action="store_true",
                    help="校准各档 price（动钱！需再设环境变量 CONFIRM_PRICE=YES 才执行）")
    a = ap.parse_args()

    if a.probe:
        return probe(BASE[a.mode], a.key or os.environ.get("CREEM_API_KEY", ""))

    if a.sync_price:
        if a.rate and a.rate > 0:
            _rate, _src = a.rate, "手动指定"
        else:
            _rate, _src = fetch_rate(not a.no_auto_rate)
        print("汇率 %.5f USD/CNY (%s)  ≈ 1 USD = %.4f CNY" % (_rate, _src, 1.0 / _rate))
        if not a.key:
            print("[!] 缺 API key：set CREEM_API_KEY=creem_xxx")
            return 2
        return sync_price(BASE[a.mode], a.key, _rate, not a.no_exact)

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

    # --sync-desc：只 PATCH 文案，绝不重建产品（重建会丢销量/评价/统计）
    if a.sync_desc:
        print("同步产品文案（不动价格）：")
        return sync_desc(base, a.key)

    created = []
    for t in TIERS:
        cents, note = cny_to_cents(t["cny"], rate, exact)
        print("\n[%s] %s  ¥%.1f -> $%.2f (%d cents) [%s]" % (
            t["tier"], t["label"], t["cny"], cents / 100.0, cents, note))
        body = build_body(t, cents)
        idem = "weauto-once-%s-%s" % (a.mode, t["tier"])
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
            "voluntary": t.get("voluntary", False),
            "valid_days": t.get("valid_days", 30),
            "features": t["features"],
        })

    print("\n" + "=" * 64)
    print("创建完成（共 %d 档）：" % len(created))
    for c in created:
        print("  %-8s %-14s %s" % (c["tier"], c["price_text"], c["product_id"]))
    print("=" * 64)

    if a.apply:
        ok, msg = apply_wrangler(created)
        print("  ", msg)
        if not ok:
            print("[x] 写回 wrangler.toml 失败，配置未生效（上面已创建的产品仍需手动填 CREEM_PRODUCTS）")
            return 1

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
