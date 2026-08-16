
//
// YAML pointer/lookup helpers. The original TypeScript version implemented
// `getByPath`, `setByPath`, `deleteByPath` and supported bracket/dot
// notation (e.g. `users[0].name`). The Rust port operates on
// `serde_yaml::Value` and supports both dotted paths and bracket index
// notation. Suitable for config lookups against settings files.
//

use serde_yaml::Value;

/// 将单个路径段拆成 (key, optional_index)。
/// 例:`users` -> [("users", None)]
///     `users[0]` -> [("users", Some(0))]
///     `a[0][1]` -> [("a", Some(0)), ("", Some(1))]
/// 之前用 `trim_end_matches(']').trim_start_matches('[')` 把 `users[0]`
/// 错误地变成 `users[0`(末尾 ] 被剥,但开头不是 [ 不能剥),导致 mapping
/// 查找 key 永远 miss。这里改用扫描方式正确解析。
///
/// 返回 Result:遇到非数字括号内容(如 `[name]`)、未闭合 `[`、或多个
/// 数字括号(如 `users[0][1]`)时返回 Err。之前这些情况被静默丢弃,
/// 调用方拿到错误数据还以为操作成功了。
fn split_segment(segment: &str) -> Result<Vec<(String, Option<usize>)>, String> {
    let mut out = Vec::new();
    let bytes = segment.as_bytes();
    let mut i = 0;
    // 先取主 key(到第一个 [ 之前)
    let key_start = i;
    while i < bytes.len() && bytes[i] != b'[' {
        i += 1;
    }
    let key = segment[key_start..i].to_string();
    out.push((key, None));
    let mut bracket_count = 0;
    // 然后逐个解析 [N]
    while i < bytes.len() {
        if bytes[i] != b'[' { break; }
        bracket_count += 1;
        // 当前实现只支持单层 bracket;多层(如 users[0][1])需要嵌套
        // 序列访问语义,返回 Err 而不是静默退化到第一个 idx。
        if bracket_count > 1 {
            return Err(format!(
                "yaml_path: multiple bracket indices not supported in segment {:?} (only one [N] allowed per segment)",
                segment
            ));
        }
        let j = i + 1;
        let mut k = j;
        while k < bytes.len() && bytes[k] != b']' { k += 1; }
        if k >= bytes.len() {
            return Err(format!(
                "yaml_path: unclosed '[' in segment {:?}",
                segment
            ));
        }
        let idx_str = &segment[j..k];
        let idx = idx_str.parse::<usize>().map_err(|_| {
            format!(
                "yaml_path: non-numeric bracket content {:?} in segment {:?} (string keys in brackets not supported)",
                idx_str, segment
            )
        })?;
        out.push((String::new(), Some(idx)));
        i = k + 1;
    }
    Ok(out)
}

/// 消费 split_segment 的结果,把 (key, idx) 序列折叠成
/// (key, first_idx_or_none)。当前调用方只支持一层 bracket,
/// 多层 bracket / 非数字括号会在 split_segment 阶段返回 Err。
fn fold_segment(segment: &str) -> Result<(String, Option<usize>), String> {
    let parts = split_segment(segment)?;
    let mut key = String::new();
    let mut idx = None;
    for (k, i) in parts {
        if !k.is_empty()
            && key.is_empty() { key = k; }
            // 额外的 key 段忽略(不该出现)
        if let Some(i) = i {
            if idx.is_none() { idx = Some(i); }
        }
    }
    Ok((key, idx))
}

pub fn get_by_path<'a>(root: &'a Value, path: &str) -> Option<&'a Value> {
    let mut cur = root;
    for segment in path.split('.') {
        if segment.is_empty() { continue; }
        let (key, idx) = fold_segment(segment).ok()?;
        if key.is_empty() && idx.is_none() { continue; }
        cur = match cur {
            Value::Mapping(m) => {
                let v = m.get(Value::String(key.clone()))?;
                match idx {
                    Some(i) => match v {
                        Value::Sequence(s) => s.get(i)?,
                        _ => return None,
                    },
                    None => v,
                }
            }
            Value::Sequence(s) => {
                let i = idx.or_else(|| key.parse::<usize>().ok())?;
                s.get(i)?
            }
            _ => return None,
        };
    }
    Some(cur)
}

pub fn set_by_path(root: &mut Value, path: &str, new_value: Value) -> Result<(), String> {
    let segments: Vec<&str> = path.split('.').filter(|s| !s.is_empty()).collect();
    if segments.is_empty() { return Err("empty path".into()); }
    let (last, rest) = segments.split_last().unwrap();
    let mut cur = root;
    for seg in rest {
        let (key, idx) = fold_segment(seg)?;
        cur = match cur {
            Value::Mapping(m) => match idx {
                Some(i) => {
                    let v = m.entry(Value::String(key.clone())).or_insert(Value::Sequence(Vec::new()));
                    match v {
                        Value::Sequence(s) => {
                            if i >= s.len() { return Err("index out of bounds".into()); }
                            &mut s[i]
                        }
                        _ => return Err("path traverses non-sequence".into()),
                    }
                }
                None => m.entry(Value::String(key.clone())).or_insert(Value::Mapping(Default::default())),
            },
            Value::Sequence(s) => {
                let i = idx.or_else(|| key.parse::<usize>().ok()).ok_or_else(|| format!("bad index: {}", key))?;
                if i >= s.len() { return Err("index out of bounds".into()); }
                &mut s[i]
            }
            _ => return Err("path traverses non-container".into()),
        };
    }
    let (last_key, last_idx) = fold_segment(last)?;
    match cur {
        Value::Mapping(m) => match last_idx {
            Some(i) => {
                let v = m.entry(Value::String(last_key.clone())).or_insert(Value::Sequence(Vec::new()));
                match v {
                    Value::Sequence(s) => {
                        if i >= s.len() { return Err("index out of bounds".into()); }
                        s[i] = new_value;
                        Ok(())
                    }
                    _ => Err("path traverses non-sequence".into()),
                }
            }
            None => { m.insert(Value::String(last_key), new_value); Ok(()) }
        },
        Value::Sequence(s) => {
            let idx = last_idx.or_else(|| last_key.parse::<usize>().ok()).ok_or_else(|| format!("bad index: {}", last_key))?;
            if idx >= s.len() { return Err("index out of bounds".into()); }
            s[idx] = new_value;
            Ok(())
        }
        _ => Err("parent is not a container".into()),
    }
}

pub fn delete_by_path(root: &mut Value, path: &str) -> Result<(), String> {
    let segments: Vec<&str> = path.split('.').filter(|s| !s.is_empty()).collect();
    if segments.is_empty() { return Err("empty path".into()); }
    let (last, rest) = segments.split_last().unwrap();
    let mut cur = root;
    for seg in rest {
        let (key, idx) = fold_segment(seg)?;
        cur = match cur {
            Value::Mapping(m) => match idx {
                Some(i) => {
                    let v = m.get_mut(Value::String(key.clone())).ok_or("missing key")?;
                    match v {
                        Value::Sequence(s) => s.get_mut(i).ok_or("index out of bounds")?,
                        _ => return Err("path traverses non-sequence".into()),
                    }
                }
                None => m.get_mut(Value::String(key.clone())).ok_or("missing key")?,
            },
            Value::Sequence(s) => {
                let i = idx.or_else(|| key.parse::<usize>().ok()).ok_or_else(|| format!("bad index: {}", key))?;
                s.get_mut(i).ok_or("index out of bounds")?
            }
            _ => return Err("path traverses non-container".into()),
        };
    }
    let (last_key, last_idx) = fold_segment(last)?;
    match cur {
        Value::Mapping(m) => match last_idx {
            Some(i) => {
                let v = m.get_mut(Value::String(last_key.clone())).ok_or("missing key")?;
                match v {
                    Value::Sequence(s) => {
                        if i >= s.len() { return Err("index out of bounds".into()); }
                        s.remove(i);
                        Ok(())
                    }
                    _ => Err("path traverses non-sequence".into()),
                }
            }
            None => { m.remove(Value::String(last_key)); Ok(()) }
        },
        Value::Sequence(s) => {
            let idx = last_idx.or_else(|| last_key.parse::<usize>().ok()).ok_or_else(|| format!("bad index: {}", last_key))?;
            if idx >= s.len() { return Err("index out of bounds".into()); }
            s.remove(idx);
            Ok(())
        }
        _ => Err("parent is not a container".into()),
    }
}
