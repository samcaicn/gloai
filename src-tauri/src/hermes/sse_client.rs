
//
// Minimal Server-Sent-Events (SSE) client. The TypeScript version
// subscribed to an `EventSource` and yielded typed `SseEvent`s. The
// Rust port uses `reqwest`'s streaming response and a small line
// parser, then yields `SseEvent`s through a `futures::Stream`.

use futures::stream::Stream;
use reqwest::Client as HttpClient;
use std::collections::VecDeque;
use std::pin::Pin;
use std::task::{Context, Poll};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SseEvent {
    pub event: String,
    pub data: String,
    pub id: Option<String>,
}

pub struct SseClient {
    pub url: String,
    pub headers: Vec<(String, String)>,
}

impl SseClient {
    pub fn new(url: impl Into<String>) -> Self {
        Self { url: url.into(), headers: Vec::new() }
    }

    pub fn header(mut self, k: impl Into<String>, v: impl Into<String>) -> Self {
        self.headers.push((k.into(), v.into()));
        self
    }

    pub async fn connect(self) -> Result<impl Stream<Item = Result<SseEvent, String>>, String> {
        let client = HttpClient::new();
        let mut req = client.get(&self.url);
        for (k, v) in &self.headers { req = req.header(k, v); }
        req = req.header("Accept", "text/event-stream");
        let resp = req.send().await.map_err(|e| e.to_string())?;
        if !resp.status().is_success() { return Err(format!("http {}", resp.status())); }
        let stream = resp.bytes_stream();
        Ok(SseStream { inner: Box::pin(stream), buffer: Vec::new(), current_event: "message".to_string(), current_id: None, current_data: Vec::new(), pending_events: VecDeque::new() })
    }
}

pub struct SseStream {
    inner: Pin<Box<dyn Stream<Item = Result<bytes::Bytes, reqwest::Error>> + Send>>,
    buffer: Vec<u8>,
    current_event: String,
    current_id: Option<String>,
    current_data: Vec<String>,
    pending_events: VecDeque<SseEvent>,
}

impl Stream for SseStream {
    type Item = Result<SseEvent, String>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        loop {
            // Yield any previously buffered complete events.
            if let Some(ev) = self.pending_events.pop_front() {
                return Poll::Ready(Some(Ok(ev)));
            }
            // Try to flush any complete events already buffered.
            if let Some(idx) = self.buffer.iter().position(|b| *b == b'\n') {
                let line: Vec<u8> = self.buffer.drain(..=idx).collect();
                let line = String::from_utf8_lossy(&line[..line.len()-1]).to_string();
                self.handle_line(&line);
                continue;
            }
            // Need more bytes from the underlying stream.
            match Pin::new(&mut self.inner).poll_next(cx) {
                Poll::Ready(Some(Ok(bytes))) => {
                    self.buffer.extend_from_slice(&bytes);
                }
                Poll::Ready(Some(Err(e))) => return Poll::Ready(Some(Err(e.to_string()))),
                Poll::Ready(None) => {
                    if !self.current_data.is_empty() {
                        let ev = SseEvent {
                            event: std::mem::take(&mut self.current_event),
                            data: self.current_data.join("\n"),
                            id: self.current_id.take(),
                        };
                        return Poll::Ready(Some(Ok(ev)));
                    }
                    return Poll::Ready(None);
                }
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl SseStream {
    fn handle_line(&mut self, line: &str) {
        if line.is_empty() {
            if !self.current_data.is_empty() {
                let ev = SseEvent {
                    event: std::mem::take(&mut self.current_event),
                    data: self.current_data.join("\n"),
                    id: self.current_id.take(),
                };
                self.pending_events.push_back(ev);
                self.current_event = "message".to_string();
            }
            return;
        }
        if let Some(rest) = line.strip_prefix(':') { let _ = rest; return; }
        if let Some(rest) = line.strip_prefix("event:") {
            self.current_event = rest.trim().to_string();
        } else if let Some(rest) = line.strip_prefix("data:") {
            self.current_data.push(rest.trim().to_string());
        } else if let Some(rest) = line.strip_prefix("id:") {
            self.current_id = Some(rest.trim().to_string());
        }
    }
}
