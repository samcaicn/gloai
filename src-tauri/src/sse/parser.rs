
//
// SSE (Server-Sent Events) parser. The TypeScript module read raw
// text, split it on blank lines, and produced `SseEvent`s. The
// Rust port exposes the same data shape and a `feed()` method that
// the front-end can call with chunks of bytes.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct SseEvent {
    pub event: String,
    pub data: String,
    pub id: Option<String>,
    pub retry: Option<u32>,
}

pub struct SseParser {
    buffer: String,
    current_event: String,
    current_id: Option<String>,
    current_retry: Option<u32>,
    current_data: Vec<String>,
}

impl Default for SseParser {
    fn default() -> Self { Self::new() }
}

impl SseParser {
    pub fn new() -> Self { Self { buffer: String::new(), current_event: "message".into(), current_id: None, current_retry: None, current_data: Vec::new() } }

    pub fn feed(&mut self, chunk: &str) -> Vec<SseEvent> {
        self.buffer.push_str(chunk);
        let mut out = Vec::new();
        while let Some(pos) = self.buffer.find('\n') {
            let line: String = self.buffer.drain(..=pos).collect();
            let line = line.trim_end_matches('\n').trim_end_matches('\r').to_string();
            if line.is_empty() {
                if !self.current_data.is_empty() {
                    out.push(SseEvent {
                        event: std::mem::take(&mut self.current_event),
                        data: self.current_data.join("\n"),
                        id: self.current_id.take(),
                        retry: self.current_retry.take(),
                    });
                    self.current_data.clear();
                    self.current_event = "message".into();
                }
                continue;
            }
            if let Some(rest) = line.strip_prefix(':') { let _ = rest; continue; }
            if let Some(rest) = line.strip_prefix("event:") { self.current_event = rest.trim().into(); }
            else if let Some(rest) = line.strip_prefix("data:") { self.current_data.push(rest.trim().into()); }
            else if let Some(rest) = line.strip_prefix("id:") { self.current_id = Some(rest.trim().into()); }
            else if let Some(rest) = line.strip_prefix("retry:") { self.current_retry = rest.trim().parse().ok(); }
            // Per the SSE spec, a field name with no ':' is treated
            // as "field:" + empty value. `retry` with no value is
            // invalid and is left as None.
            else if line == "event" { self.current_event = String::new(); }
            else if line == "data" { self.current_data.push(String::new()); }
            else if line == "id" { self.current_id = Some(String::new()); }
        }
        out
    }

    pub fn finish(&mut self) -> Option<SseEvent> {
        if self.current_data.is_empty() { return None; }
        Some(SseEvent {
            event: std::mem::take(&mut self.current_event),
            data: self.current_data.join("\n"),
            id: self.current_id.take(),
            retry: self.current_retry.take(),
        })
    }
}
