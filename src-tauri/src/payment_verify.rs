//! 图片支付校验（0.4.0 新增）：OCR 识别出付款截图后，按「该团员」在 bbt-zb 的订单金额
//! 核验支付金额，给出**确定性**回复（不走 LLM）。
//!
//! 业务流（用户 2026-11-08 定义）：
//!   团员（微信私聊/群聊）发来一张付款截图 → 离线 OCR 出文字 →
//!   ① 提取支付金额（及是否确为支付截图）② 用发消息这位团员的微信昵称（=订单 buyerName）
//!      去 `leader/orders` 找其订单并汇总 amountYuan ③ 比对：
//!        - 金额一致 → 回复「谢谢」
//!        - 金额不一致 → 回复「请核对下付款金额，截图 ¥X，订单 ¥Y」
//!   非支付截图 / 找不到该团员订单 / 订单接口无权限（如 PC 非团长态）→ 返回 None，
//!   由 autoreply 的 `payment_image_only` 决定是否改回确定性「请核对下…」提示，
//!   **不再让团长 AI 对付款截图自由发挥**（用户 2026-11-09 明确要求）。
//!
//! 本地订单种子（0.4.0 补充）：`leader/orders` 需团长 token，而 PC 端实际以**团员**账号
//! 登录（status 里 role=member），对该接口返回「无权限」导致校验永远走不到订单比对。
//! 为在本地让这一确定性校验可用，新增 `app_data_dir/payment_orders_seed.json` 作回退：
//! 后端拉订单失败时读本地种子；种子为空才回退 AI。种子由 `payment_verify_seed_set`
//! 写入，格式 `[{buyerName, amountYuan}, ...]`（与该团员在 bbt-zb 后台的订单一致即可）。
//! 根因是后端权限模型（单登录无法同时持有「读订单的团长 token」与「发 chat/send 的团员 token」），
//! 故种子是本地可用方案，不是实时后端数据。
//!
//! 关联键说明（与前端 orderspage.ts::applyMemberStats 对齐）：订单 `buyerName` 即团员的
//! 微信昵称或脱敏手机尾号；入站消息的 `sender_name`（`WxMsg.sender`）正是发消息这位团员的
//! 微信昵称，天然就是关联键。匹配用「相等 / 包含」双向宽松匹配，容忍昵称微小差异。

use crate::ai_gateway::connection;
use crate::bbt;
use crate::state::AppState;
use crate::util;
use serde_json::{json, Value};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;
use tauri::{AppHandle, Manager};

/// 订单快照缓存 TTL（ms）：同一团员连发多张截图时避免每次都打 leader/orders
const ORDER_CACHE_TTL_MS: i64 = 60_000;
/// 后端明确「无权限」后的退避（ms）：PC 端登录后 leader/ledger、leader/orders 是团长
/// 后台接口，多数部署下必然返回「无权限」。每张付款截图都空打一次后端（还有网络
/// 重试退避）纯属拖慢回复；退避期内直接用本地订单种子，到期再探一次（换团长
/// token / 重新登录后能自动恢复实时订单）。
const ORDER_PERM_BACKOFF_MS: i64 = 10 * 60_000;

pub struct PaymentVerifyState {
    /// 全团订单快照：(buyerName, amountYuan) 列表 + 时间戳
    cache: Mutex<Option<(Vec<(String, f64)>, i64)>>,
    /// 后端明确「无权限」的退避截止时刻（Unix ms），期间直接用本地种子
    perm_denied_until: Mutex<i64>,
}

impl PaymentVerifyState {
    pub fn new() -> Self {
        Self {
            cache: Mutex::new(None),
            perm_denied_until: Mutex::new(0),
        }
    }
}

impl Default for PaymentVerifyState {
    fn default() -> Self {
        Self::new()
    }
}

/// 支付校验结论（已判定则短路 AI 网关）
pub struct PaymentVerdict {
    /// 直接用于回发的确定性文案
    pub reply: String,
    /// true=金额一致 / false=不一致
    pub matched: bool,
    /// 该团员订单合计（元）
    pub expected: f64,
    /// OCR 识别出的支付金额（元，取最大）
    pub paid: f64,
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn log(app: &AppHandle, level: &str, msg: &str) {
    eprintln!("[payment_verify] {} {}", level, msg);
    if let Ok(dir) = app.path().app_data_dir() {
        let log_dir = dir.join("logs");
        let _ = std::fs::create_dir_all(&log_dir);
        let line = format!(
            "[{}] {} {}\n",
            crate::autoreply::fmt_ts(now_ms()),
            level,
            msg
        );
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log_dir.join("payment_verify.log"))
        {
            use std::io::Write;
            let _ = f.write_all(line.as_bytes());
        }
    }
}

/// 支付类关键词：命中 + 存在货币锚定的金额 才判定为支付截图
const PAY_KEYWORDS: &[&str] = &[
    "转账", "收款", "收钱", "支付", "已收款", "转账给", "向", "零钱", "红包", "微信支付",
    "支付宝", "汇款", "付款", "账单", "交易",
];

/// 手写金额提取（不引入 regex 依赖）：默认仅识别「货币锚定」的数字
/// —— 前面带 ¥/￥ 或后面带 元/块/圆，支持千分位逗号与小数。
/// `require_anchor=false` 时额外放行「形如 250.00 的两位小数」——微信/支付宝
/// 付款截图里金额常单独成行、不带 ¥ 或 元，只按锚定会整张漏判（用户 11-09 实测）。
fn extract_amounts_with(text: &str, require_anchor: bool) -> Vec<f64> {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mut out: Vec<f64> = Vec::new();
    let mut i = 0;
    while i < n {
        let c = chars[i];
        let currency_here = c == '¥' || c == '￥';
        let digit_here = c.is_ascii_digit();
        if currency_here || digit_here {
            // 跳过货币符与紧邻空格
            let mut j = i;
            while j < n
                && (chars[j] == '¥'
                    || chars[j] == '￥'
                    || chars[j] == ' '
                    || (chars[j] == '.'
                        && j + 1 < n
                        && chars[j + 1].is_ascii_digit()))
            {
                j += 1;
            }
            let start = j;
            let mut k = j;
            let mut seen_dot = false;
            let mut valid = false;
            while k < n {
                let d = chars[k];
                if d.is_ascii_digit() {
                    valid = true;
                    k += 1;
                } else if d == ',' && k + 1 < n && chars[k + 1].is_ascii_digit() {
                    k += 1;
                } else if d == '.'
                    && !seen_dot
                    && k + 1 < n
                    && chars[k + 1].is_ascii_digit()
                {
                    seen_dot = true;
                    k += 1;
                } else {
                    break;
                }
            }
            if valid {
                let s: String = chars[start..k].iter().collect();
                let s = s.replace(',', "");
                if let Ok(v) = s.parse::<f64>() {
                    let prev = if start > 0 { chars[start - 1] } else { ' ' };
                    let next = if k < n { chars[k] } else { ' ' };
                    let has_cur = prev == '¥'
                        || prev == '￥'
                        || next == '元'
                        || next == '块'
                        || next == '圆'
                        || next == '￥'
                        || next == '¥';
                    // 放宽路径：仅接受「两位小数」且 ≥1 的数字，避免把时间/序号当金额
                    let loose_ok = !require_anchor && {
                        let parts: Vec<&str> = s.split('.').collect();
                        parts.len() == 2 && parts[1].len() == 2 && v >= 1.0 && v <= 1_000_000.0
                    };
                    if has_cur || loose_ok {
                        let v = (v * 100.0).round() / 100.0;
                        if !out.contains(&v) {
                            out.push(v);
                        }
                    }
                }
                i = k;
                continue;
            }
        }
        i += 1;
    }
    out
}

fn extract_amounts(text: &str) -> Vec<f64> {
    extract_amounts_with(text, true)
}

fn extract_amounts_loose(text: &str) -> Vec<f64> {
    extract_amounts_with(text, false)
}

/// 从 OCR 文本里挑「实付金额」：优先「支付成功 / 付款金额」附近出现的第一个金额。
/// 付款截图里常同时有商品原价（¥103.00）、立减金（-¥0.17）、代金券（¥100）、
/// 免息额度（200 元）等，直接取全局最大会拿错数字（11-09 实测评 ¥102.83 的
/// 截图里还有 ¥103.00）。找不到主金额时才回退到最大金额。
fn pick_paid_amount(text: &str, amounts: &[f64]) -> f64 {
    let lines: Vec<&str> = text.lines().collect();
    // OCR 常把「支付成功」误认成「支付或功 / 支付成力 / 支寸或功」（11-09 两张
    // 真实截图都这样），强匹配「成功」会整行漏掉 → 回退到 max() 取成商品原价
    // （实测 ¥102.83 的截图被判成 ¥904/¥105，¥6317.69 的被判成 ¥6318）。
    // 故标题行只要求含支付类关键词且是短行（≤8 字）：「付款方式 / 支付有礼」
    // 这类说明行排在标题之后，先命中即返回，且其后通常紧跟不到锚定金额。
    let keys = ["支付", "付款", "转账", "收款", "实付"];
    for (i, line) in lines.iter().enumerate() {
        let t = line.trim();
        if t.chars().count() > 8 || !keys.iter().any(|k| t.contains(k)) {
            continue;
        }
        for w in 0..=3 {
            let l = match lines.get(i + w) {
                Some(x) => *x,
                None => break,
            };
            let mut v = extract_amounts(l);
            if v.is_empty() {
                v = extract_amounts_loose(l);
            }
            if let Some(c) = v.first() {
                if amounts.iter().any(|a| (a - c).abs() < 0.005) {
                    return *c;
                }
            }
        }
    }
    // 兜底：付款截图里实付金额几乎总在正文最靠前（时间/状态行之后就是它），
    // 取「文本顺序第一个金额」远稳于取全局最大 —— 最大常是原价 / 代金券面额。
    // `extract_amounts*` 按文本顺序 push，故 first() 即最早出现的金额。
    amounts.first().copied().unwrap_or(0.0)
}

/// 是否为支付截图：命中支付关键词，且含货币锚定金额或两位小数金额。
fn is_payment_text(text: &str) -> bool {
    if !PAY_KEYWORDS.iter().any(|k| text.contains(k)) {
        return false;
    }
    !extract_amounts(text).is_empty() || !extract_amounts_loose(text).is_empty()
}

/// 按 buyerName 双向宽松匹配候选名，汇总订单金额（元）
fn order_total_for_buyer(orders: &[(String, f64)], candidates: &[String]) -> f64 {
    let mut total = 0.0f64;
    for (bn, amt) in orders {
        for c in candidates {
            let c = c.trim();
            if c.is_empty() {
                continue;
            }
            if bn == c || bn.contains(c) || c.contains(bn) {
                total += amt;
                break;
            }
        }
    }
    (total * 100.0).round() / 100.0
}

/// 解析金额字段（优先 amountYuan，缺则 amount；去掉非数字字符后按「元」解析）
fn parse_yuan(v: Option<&Value>) -> f64 {
    let s = match v {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => return 0.0,
    };
    let cleaned: String = s.chars().filter(|c| c.is_ascii_digit() || *c == '.').collect();
    cleaned.parse::<f64>().unwrap_or(0.0)
}

/// 本地订单种子文件名（落在 app_data_dir）。
const ORDER_SEED_FILE: &str = "payment_orders_seed.json";

/// 解析本地订单种子（与 leader/orders 返回同形态）：支持
///   [ {buyerName, amountYuan}, ... ]  或  { "orders": [ ... ] }
/// amountYuan 可为数字或字符串（含千分位 / 元）。buyerName 为空或金额 ≤0 的条目跳过。
fn parse_seed_entries(v: &Value) -> Vec<(String, f64)> {
    let arr = if let Some(a) = v.as_array() {
        a.clone()
    } else if let Some(a) = v.get("orders").and_then(|x| x.as_array()) {
        a.clone()
    } else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for o in &arr {
        let bn = o
            .get("buyerName")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .trim()
            .to_string();
        if bn.is_empty() {
            continue;
        }
        let amt = parse_yuan(o.get("amountYuan").or_else(|| o.get("amount")));
        if amt > 0.0 {
            out.push((bn, amt));
        }
    }
    out
}

/// 后端 leader/orders 无权限（PC 团员登录态）时的本地回退：读 app_data_dir 下的订单种子。
fn load_seed_orders(app: &AppHandle) -> Vec<(String, f64)> {
    let dir = match app.path().app_data_dir() {
        Ok(d) => d,
        Err(_) => return Vec::new(),
    };
    let p = dir.join(ORDER_SEED_FILE);
    let text = match std::fs::read_to_string(&p) {
        Ok(t) => t,
        Err(_) => return Vec::new(),
    };
    match serde_json::from_str::<Value>(&text) {
        Ok(v) => parse_seed_entries(&v),
        Err(_) => Vec::new(),
    }
}

/// 写入本地订单种子（供无 leader/orders 权限的部署用）。返回写入条数。
pub fn save_seed_orders(app: &AppHandle, orders: &Value) -> Result<usize, String> {
    let parsed = parse_seed_entries(orders);
    if parsed.is_empty() {
        return Err("订单种子为空或格式不对（需 [{buyerName, amountYuan}, ...]）".into());
    }
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("取 app_data_dir 失败: {}", e))?;
    std::fs::create_dir_all(&dir).map_err(|e| format!("创建目录失败: {}", e))?;
    let p = dir.join(ORDER_SEED_FILE);
    let body = json!({
        "orders": parsed
            .iter()
            .map(|(n, a)| json!({ "buyerName": n, "amountYuan": a }))
            .collect::<Vec<_>>()
    });
    let text = serde_json::to_string_pretty(&body).map_err(|e| format!("序列化失败: {}", e))?;
    util::write_atomic(Path::new(&p), &text).map_err(|e| format!("写种子文件失败: {}", e))?;
    log(
        app,
        "info",
        &format!("已写入本地订单种子 {} 条 → {}", parsed.len(), p.display()),
    );
    Ok(parsed.len())
}

/// 拉取全团订单快照（遍历 leader/ledger 各团 → leader/orders），带 TTL 缓存。
/// leader/orders 需团长 token；PC 端登录团长账号即具备（见 ai_gateway 身份模型）。
async fn load_all_orders(app: &AppHandle) -> Result<Vec<(String, f64)>, String> {
    // 命中缓存直接返回
    {
        let cached = {
            let st = app.state::<Arc<PaymentVerifyState>>();
            let guard = st.cache.lock().unwrap();
            guard.clone()
        };
        if let Some((data, ts)) = cached {
            if now_ms() - ts < ORDER_CACHE_TTL_MS {
                return Ok(data);
            }
        }
    }
    // 后端明确「无权限」后的退避：不再每张截图都空打一次 leader/ledger（含网络重试），
    // 直接让调用方回退本地订单种子。到期自动再探，换团长 token 后无需重启即可恢复。
    {
        let st = app.state::<Arc<PaymentVerifyState>>();
        if now_ms() < *st.perm_denied_until.lock().unwrap() {
            return Err("订单接口无权限（退避中，使用本地种子）".into());
        }
    }
    let conn = connection(app).await?;
    // 1) 团列表
    let ledger = bbt::call(&conn, "leader/ledger", &json!({})).await?;
    let ledger_data = bbt::data_of(&ledger).map_err(|e| e.to_string())?;
    let groups = ledger_data
        .get("list")
        .and_then(|l| l.as_array())
        .cloned()
        .unwrap_or_default();
    let mut all: Vec<(String, f64)> = Vec::new();
    for g in &groups {
        // 对齐前端 leader/orders 的 groupId 强转：数字字符串转数字，非数字保持原样
        let gid_num = match g.get("id") {
            Some(Value::Number(n)) => json!(n.as_i64().unwrap_or(0)),
            Some(Value::String(s)) => s
                .trim()
                .parse::<i64>()
                .map(|i| json!(i))
                .unwrap_or_else(|_| json!(s.clone())),
            _ => g.get("id").cloned().unwrap_or(Value::Null),
        };
        let r = bbt::call(&conn, "leader/orders", &json!({ "groupId": gid_num })).await?;
        let d = match bbt::data_of(&r) {
            Ok(d) => d,
            Err(_) => continue, // 单团失败不影响其它团
        };
        let list = if let Some(arr) = d.as_array() {
            arr.clone()
        } else {
            d.get("list")
                .or_else(|| d.get("orders"))
                .and_then(|l| l.as_array())
                .cloned()
                .unwrap_or_default()
        };
        for o in &list {
            let bn = o
                .get("buyerName")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            if bn.is_empty() {
                continue;
            }
            let amt = parse_yuan(o.get("amountYuan").or_else(|| o.get("amount")));
            if amt > 0.0 {
                all.push((bn, amt));
            }
        }
    }
    app.state::<Arc<PaymentVerifyState>>()
        .cache
        .lock()
        .unwrap()
        .replace((all.clone(), now_ms()));
    Ok(all)
}

/// 读取 config.wechat_pairing.map（member_id -> {wxid, wxname, is_group, member_name}）
fn read_wechat_pairing(app: &AppHandle) -> serde_json::Map<String, Value> {
  app.state::<AppState>()
    .config
    .lock()
    .unwrap()
    .get("wechat_pairing")
    .and_then(|m| m.get("map"))
    .and_then(|v| v.as_object())
    .cloned()
    .unwrap_or_default()
}

/// 入站微信会话 id（wxid / @chatroom）若已与某团员配对，返回该团员的显示名。
/// 这把「图片消息来自哪个微信会话」精确映射成「哪个团员」，直接服务于
/// 「根据该团员的订单值比对」——比单纯依赖 sender_name 模糊匹配更可靠。
fn member_name_for_peer(app: &AppHandle, peer: &str) -> Option<String> {
  if peer.is_empty() {
    return None;
  }
  let map = read_wechat_pairing(app);
  for (_mid, v) in &map {
    if v.get("wxid").and_then(|x| x.as_str()) == Some(peer) {
      return v
        .get("member_name")
        .and_then(|x| x.as_str())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string());
    }
  }
  None
}

/// 入口：图片 OCR 文本 → 支付校验结论。
/// 返回 Some 表示已判定（调用方应短路 AI 网关直接用 `reply` 回发）；
/// 返回 None 表示非支付截图 / 无法确定（交给 AI 流程）。
pub async fn verify_image(
  app: &AppHandle,
  ocr_text: &str,
  to: &str,
  sender: &str,
  _is_group: bool,
) -> Option<PaymentVerdict> {
  if !is_payment_text(ocr_text) {
    return None;
  }
  // 先用严格锚定金额；截图里金额单独成行（无 ¥ / 元）时回退到两位小数候选
  let mut amounts = extract_amounts(ocr_text);
  if amounts.is_empty() {
    amounts = extract_amounts_loose(ocr_text);
  }
  // 优先取「支付成功」附近的实付金额，避免把原价/立减金/代金券当成付款额
  let paid = pick_paid_amount(ocr_text, &amounts);

  // 候选买家名：优先用「微信会话↔团员」配对得到的团员名（精确），
  // 其次回退发消息者昵称（兼容未配对 / iLink 渠道）。
  let mut candidates: Vec<String> = Vec::new();
  if let Some(pn) = member_name_for_peer(app, to) {
    candidates.push(pn);
  }
  if !sender.trim().is_empty() {
    candidates.push(sender.trim().to_string());
  }

  let orders = match load_all_orders(app).await {
    Ok(o) => o,
    Err(e) => {
      // 后端 leader/orders 无权限（PC 团员登录态）→ 回退本地订单种子；
      // 种子仍为空才回退 AI，避免对付款截图误下结论。
      // 仅「无权限」这类确定性失败进入退避（网络抖动仍每次重试，避免缓存住故障）；
      // 退避期内不打后端也不刷 warn 日志，只留 debug。
      let in_backoff = e.contains("退避中");
      if !in_backoff && (e.contains("无权限") || e.contains("权限")) {
        let st = app.state::<Arc<PaymentVerifyState>>();
        *st.perm_denied_until.lock().unwrap() = now_ms() + ORDER_PERM_BACKOFF_MS;
      }
      log(
        app,
        if in_backoff { "debug" } else { "warn" },
        &format!("订单接口失败（{}），尝试本地订单种子", e),
      );
      load_seed_orders(app)
    }
  };
  if orders.is_empty() {
    log(
      app,
      "info",
      "无订单数据（后端无权限且无本地种子），回退 AI 流程",
    );
    return None;
  }

  let expected = order_total_for_buyer(&orders, &candidates);
  if expected <= 0.0 {
    // 找不到该团员订单 → 回退 AI，避免对任意截图误下结论
    log(
      app,
      "info",
      &format!("未按「{}」匹配到订单，回退 AI 流程", candidates.join(" / ")),
    );
    return None;
  }

  // 展示名：配对优先，其次发消息者昵称，兜底「老板」
  let name = candidates
    .first()
    .cloned()
    .filter(|s| !s.trim().is_empty())
    .unwrap_or_else(|| "老板".to_string());

  let diff = (paid - expected).abs();
  if diff <= 0.01 {
    log(
      app,
      "info",
      &format!(
        "支付校验通过：团员「{}」付款 ¥{} = 订单合计 ¥{}",
        name, paid, expected
      ),
    );
    Some(PaymentVerdict {
      // 用户 2026-11-09 定稿：「金额对就说 谢谢」。不带称呼，避免配对名（如 k）
      // 错配成「谢谢k」这种尴尬文案。
      reply: "谢谢".to_string(),
      matched: true,
      expected,
      paid,
    })
  } else {
    log(
      app,
      "warn",
      &format!(
        "支付校验不符：团员「{}」付款 ¥{} ≠ 订单合计 ¥{}",
        name, paid, expected
      ),
    );
    Some(PaymentVerdict {
      // 用户 2026-11-09 定稿：「不对就提示 请核对下xxxx」——把双方金额都写清楚，
      // 团员才知道差在哪，不用再追问。
      reply: format!("请核对下付款金额，截图 ¥{:.2}，订单 ¥{:.2}", paid, expected),
      matched: false,
      expected,
      paid,
    })
  }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn amount_extraction() {
        assert_eq!(extract_amounts("¥250.00"), vec![250.0]);
        assert_eq!(extract_amounts("¥1,234.56"), vec![1234.56]);
        assert_eq!(extract_amounts("250.00元"), vec![250.0]);
        assert_eq!(extract_amounts("已收款\n¥88.00\n来自 小美"), vec![88.0]);
        // 无货币锚定的数字（手机号/日期）不应被当成金额
        assert!(extract_amounts("电话18602890023").is_empty());
        assert!(extract_amounts("2026-11-08").is_empty());
        // 多个货币金额都提取
        let mut v = extract_amounts("¥100.00 和 ¥50.00");
        v.sort_by(|a, b| a.partial_cmp(b).unwrap());
        assert_eq!(v, vec![50.0, 100.0]);
    }

    #[test]
    fn payment_detection() {
        assert!(is_payment_text("微信转账\n转账给 团长\n¥250.00"));
        assert!(is_payment_text("支付宝\n转账\n收款方 团长\n¥300.00\n订单号2026"));
        assert!(is_payment_text("零钱通\n已收款\n¥250.00\n付款方：凯哥"));
        assert!(!is_payment_text("今天天气不错，这是我家猫"));
        assert!(!is_payment_text("¥250.00")); // 有金额但无支付关键词 → 不误判
    }

    #[test]
    fn buyer_matching_and_total() {
        let orders = vec![
            ("凯哥".to_string(), 200.0),
            ("凯哥".to_string(), 50.0),
            ("小美".to_string(), 888.0),
            ("186****0023".to_string(), 1234.56),
        ];
        // buyerName 双向包含匹配
        assert_eq!(order_total_for_buyer(&orders, &["凯哥".to_string()]), 250.0);
        assert_eq!(order_total_for_buyer(&orders, &["小美".to_string()]), 888.0);
        assert_eq!(order_total_for_buyer(&orders, &["186****0023".to_string()]), 1234.56);
        // 匹配不到 → 0
        assert_eq!(order_total_for_buyer(&orders, &["不存在的人".to_string()]), 0.0);
    }

    #[test]
    fn parse_yuan_field() {
        assert_eq!(parse_yuan(Some(&json!("200.00"))), 200.0);
        assert_eq!(parse_yuan(Some(&json!(888))), 888.0);
        assert_eq!(parse_yuan(Some(&json!("1,234.56"))), 1234.56);
        assert_eq!(parse_yuan(None), 0.0);
    }

    #[test]
    fn seed_entry_parsing() {
        let v = json!([
            {"buyerName": "凯哥", "amountYuan": 250.0},
            {"buyerName": "小美", "amountYuan": "888.00"},
            {"buyerName": "", "amountYuan": 1.0},     // 无名跳过
            {"amountYuan": 10.0}                       // 缺名跳过
        ]);
        let o = parse_seed_entries(&v);
        assert_eq!(o.len(), 2);
        assert_eq!(order_total_for_buyer(&o, &["凯哥".to_string()]), 250.0);
        assert_eq!(order_total_for_buyer(&o, &["小美".to_string()]), 888.0);
        // 兼容 { "orders": [...] } 包裹
        let w = json!({ "orders": [{"buyerName":"张三","amountYuan":99}] });
        let o2 = parse_seed_entries(&w);
        assert_eq!(o2.len(), 1);
        assert_eq!(o2[0].0, "张三");
        assert_eq!(o2[0].1, 99.0);
        // 其它形态 → 空
        assert!(parse_seed_entries(&json!({"foo": 1})).is_empty());
        assert!(parse_seed_entries(&json!("not array")).is_empty());
    }
}

