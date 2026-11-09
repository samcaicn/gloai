//! 图片支付校验（0.4.0 新增）：OCR 识别出付款截图后，按「该团员」在 bbt-zb 的订单金额
//! 核验支付金额，给出**确定性**回复（不走 LLM）。
//!
//! 业务流（用户 2026-11-08 定义）：
//!   团员（微信私聊/群聊）发来一张付款截图 → 离线 OCR 出文字 →
//!   ① 提取支付金额（及是否确为支付截图）② 用发消息这位团员的微信昵称（=订单 buyerName）
//!      去 `leader/orders` 找其订单并汇总 amountYuan ③ 比对：
//!        - 金额一致 → 回复「收到 感谢{团员}」
//!        - 金额不一致 → 回复「貌似有些不太对，辛苦核实下付款人和金额」
//!   非支付截图 / 找不到该团员订单 / 订单接口无权限（如 PC 非团长态）→ 回退 AI 流程。
//!
//! 关联键说明（与前端 orderspage.ts::applyMemberStats 对齐）：订单 `buyerName` 即团员的
//! 微信昵称或脱敏手机尾号；入站消息的 `sender_name`（`WxMsg.sender`）正是发消息这位团员的
//! 微信昵称，天然就是关联键。匹配用「相等 / 包含」双向宽松匹配，容忍昵称微小差异。

use crate::ai_gateway::connection;
use crate::bbt;
use crate::state::AppState;
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;
use tauri::{AppHandle, Manager};

/// 订单快照缓存 TTL（ms）：同一团员连发多张截图时避免每次都打 leader/orders
const ORDER_CACHE_TTL_MS: i64 = 60_000;

pub struct PaymentVerifyState {
    /// 全团订单快照：(buyerName, amountYuan) 列表 + 时间戳
    cache: Mutex<Option<(Vec<(String, f64)>, i64)>>,
}

impl PaymentVerifyState {
    pub fn new() -> Self {
        Self { cache: Mutex::new(None) }
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

/// 手写金额提取（不引入 regex 依赖）：仅识别「货币锚定」的数字
/// —— 前面带 ¥/￥ 或后面带 元/块/圆，支持千分位逗号与小数。
fn extract_amounts(text: &str) -> Vec<f64> {
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
                    if has_cur {
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

/// 是否为支付截图：有关键词且至少含一个货币锚定金额
fn is_payment_text(text: &str) -> bool {
    PAY_KEYWORDS.iter().any(|k| text.contains(k)) && !extract_amounts(text).is_empty()
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
  let amounts = extract_amounts(ocr_text);
  let paid = amounts.iter().cloned().fold(0.0f64, |a, b| a.max(b)); // 取最大金额

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
      // 订单拉取失败（PC 非团长态/无权限/网络）→ 回退 AI，不误判
      log(app, "warn", &format!("订单拉取失败（{}），回退 AI 流程", e));
      return None;
    }
  };
  if orders.is_empty() {
    log(app, "info", "无订单数据，回退 AI 流程");
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
      reply: format!("收到 感谢{}", name),
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
      reply: "貌似有些不太对，辛苦核实下付款人和金额".to_string(),
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
}

