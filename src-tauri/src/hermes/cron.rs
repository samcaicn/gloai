
//
// Cron expression evaluator with the subset of features used in the
// original package: minute, hour, day-of-month, month, day-of-week.
// The TypeScript module relied on the `cron-parser` npm package. The
// Rust port implements a minimal but correct evaluator that returns
// the next `n` firing times for a given expression.

use chrono::{DateTime, Datelike, Timelike, Utc, Weekday};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CronEntry {
    pub id: String,
    pub name: String,
    pub expression: String,
    #[serde(default)]
    pub command: Option<String>,
    #[serde(default)]
    pub last_fired_at: Option<i64>,
    #[serde(default)]
    pub next_fire_at: Option<i64>,
    #[serde(default)]
    pub enabled: bool,
}

#[derive(Default)]
pub struct CronScheduler {
    entries: Vec<CronEntry>,
}

impl CronScheduler {
    pub fn new() -> Self { Self::default() }

    pub fn add(&mut self, entry: CronEntry) { self.entries.push(entry); }

    pub fn list(&self) -> Vec<CronEntry> { self.entries.clone() }

    pub fn remove(&mut self, id: &str) -> bool {
        let before = self.entries.len();
        self.entries.retain(|e| e.id != id);
        before != self.entries.len()
    }

    pub fn tick(&mut self, now: DateTime<Utc>) -> Vec<String> {
        let mut fired = Vec::new();
        for entry in self.entries.iter_mut() {
            if !entry.enabled { continue; }
            if let Ok(next) = next_fire(&entry.expression, now) {
                entry.next_fire_at = Some(next.timestamp_millis());
                // 首次加入(无 last_fired_at)允许立即触发一次,避免新
                // 注册的 cron 任务要等一个完整周期才会被 fire;之后
                // 的 fire 仍然依赖 `last_fired_at` 去重。
                let need_fire = match entry.last_fired_at {
                    Some(last) => last < now.timestamp_millis(),
                    None => true,
                };
                if need_fire && next.timestamp_millis() <= now.timestamp_millis() + 60_000 {
                    fired.push(entry.id.clone());
                    entry.last_fired_at = Some(now.timestamp_millis());
                }
            }
        }
        fired
    }
}

fn next_fire(expr: &str, from: DateTime<Utc>) -> Result<DateTime<Utc>, String> {
    let parts: Vec<&str> = expr.split_whitespace().collect();
    if parts.len() != 5 { return Err("cron expression must have 5 fields".into()); }
    let minute = parse_field(parts[0], 0, 59)?;
    let hour = parse_field(parts[1], 0, 23)?;
    let dom = parse_field(parts[2], 1, 31)?;
    let month = parse_field(parts[3], 1, 12)?;
    let dow = parse_field(parts[4], 0, 6)?;
    // POSIX cron:dom 与 dow 都被限制(非 `*`)时,满足任一即触发(OR 语义)。
    // 只有当其中之一是 `*` 时才用 AND 语义。
    // 之前的实现因为 `parse_field("*", 0, 6)` 返回 [0..=6] 非空,
    // 导致 `(parts[4] == "*" && dow.is_empty())` 永远为 false,
    // 最终条件化简为 dom AND dow,违反 POSIX。
    let dom_restricted = parts[2] != "*";
    let dow_restricted = parts[4] != "*";
    let mut candidate = from + chrono::Duration::minutes(1);
    for _ in 0..366 * 24 * 60 {
        if !minute.contains(&(candidate.minute() as i32)) {
            candidate += chrono::Duration::minutes(1);
            continue;
        }
        if !hour.contains(&(candidate.hour() as i32)) {
            candidate += chrono::Duration::minutes(1);
            continue;
        }
        if !month.contains(&(candidate.month() as i32)) {
            candidate += chrono::Duration::minutes(1);
            continue;
        }
        let dom_match = dom.contains(&(candidate.day() as i32));
        let dow_match = dow.contains(&(iso_dow(candidate.weekday()) as i32));
        let day_match = if dom_restricted && dow_restricted {
            // POSIX OR 语义
            dom_match || dow_match
        } else {
            // 其中之一是 *,等价于 AND(因为 * 永远匹配)
            dom_match && dow_match
        };
        if day_match {
            return Ok(candidate);
        }
        candidate += chrono::Duration::minutes(1);
    }
    Err("could not schedule within a year".into())
}

fn iso_dow(w: Weekday) -> u32 {
    match w {
        Weekday::Mon => 1, Weekday::Tue => 2, Weekday::Wed => 3,
        Weekday::Thu => 4, Weekday::Fri => 5, Weekday::Sat => 6, Weekday::Sun => 0,
    }
}

fn parse_field(field: &str, min: i32, max: i32) -> Result<Vec<i32>, String> {
    if field == "*" { return Ok((min..=max).collect()); }
    let mut out = Vec::new();
    for part in field.split(',') {
        if part.contains('/') {
            let mut s = part.splitn(2, '/');
            let range_str = s.next().ok_or_else(|| "missing range in step field".to_string())?;
            let step: i32 = s.next().ok_or_else(|| "missing step".to_string())?.parse().map_err(|_| "bad step")?;
            let (lo, hi) = if range_str == "*" { (min, max) } else { parse_range(range_str, min, max)? };
            let mut v = lo;
            while v <= hi { out.push(v); v += step; }
        } else if part.contains('-') {
            let (lo, hi) = parse_range(part, min, max)?;
            for v in lo..=hi { out.push(v); }
        } else {
            let v: i32 = part.parse().map_err(|_| "bad value")?;
            if v < min || v > max { return Err("out of range".into()); }
            out.push(v);
        }
    }
    out.sort();
    out.dedup();
    Ok(out)
}

fn parse_range(s: &str, min: i32, max: i32) -> Result<(i32, i32), String> {
    let mut parts = s.split('-');
    let lo: i32 = parts.next().ok_or_else(|| "missing range start".to_string())?.parse().map_err(|_| "bad lo")?;
    // `?` propagates the parse error from inside the closure; we use
    // an explicit `ok_or_else` so the closure itself returns `i32`
    // directly without needing the `?` operator (which would force the
    // closure to return `Result<_, _>` instead of `i32`).
    let hi: i32 = match parts.next() {
        Some(p) => p.parse().map_err(|_| "bad hi".to_string())?,
        None => lo,
    };
    if lo < min || hi > max { return Err("out of range".into()); }
    Ok((lo, hi))
}

#[tauri::command]
pub async fn hermes_cron_list(
    state: tauri::State<'_, crate::hermes::HermesAppState>,
) -> Result<Vec<CronEntry>, String> {
    // 真正从 HermesAppState 读取，避免之前 stub 永远返回 []。
    let guard = state.cron.lock().map_err(|e| e.to_string())?;
    Ok(guard.list())
}

#[tauri::command]
pub async fn hermes_cron_add(
    state: tauri::State<'_, crate::hermes::HermesAppState>,
    entry: CronEntry,
) -> Result<(), String> {
    let mut guard = state.cron.lock().map_err(|e| e.to_string())?;
    guard.add(entry);
    Ok(())
}

#[tauri::command]
pub async fn hermes_cron_remove(
    state: tauri::State<'_, crate::hermes::HermesAppState>,
    id: String,
) -> Result<bool, String> {
    let mut guard = state.cron.lock().map_err(|e| e.to_string())?;
    Ok(guard.remove(&id))
}
