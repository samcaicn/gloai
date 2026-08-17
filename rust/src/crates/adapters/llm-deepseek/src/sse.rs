//! Decode an SSE byte stream into event `data` payloads.

use dsh_core_types::{LlmError, STREAM_CLOSED_CODE};
use futures::StreamExt;

pub const DONE: &str = "[DONE]";

/// Parse SSE bytes. Events dispatch only on a blank-line terminator.
/// Yields `[DONE]` last. EOF without `[DONE]` is `STREAM_CLOSED`.
/// An unterminated tail at EOF is truncation, not a flushable payload.
pub async fn parse_sse<S, E>(mut stream: S) -> Result<Vec<String>, LlmError>
where
    S: futures::Stream<Item = Result<bytes::Bytes, E>> + Unpin,
    E: std::fmt::Display,
{
    let mut buf = String::new();
    let mut payloads = Vec::new();
    while let Some(chunk) = stream.next().await {
        let bytes = chunk.map_err(|error| LlmError::new(error.to_string(), "TRANSPORT"))?;
        buf.push_str(&String::from_utf8_lossy(&bytes));
        drain_events(&mut buf, &mut payloads);
        if payloads.last().map(String::as_str) == Some(DONE) {
            return Ok(payloads);
        }
    }
    Err(LlmError::new(
        "SSE stream ended without [DONE]",
        STREAM_CLOSED_CODE,
    ))
}

fn drain_events(buf: &mut String, payloads: &mut Vec<String>) {
    loop {
        let (index, sep) = if let Some(index) = buf.find("\r\n\r\n") {
            (index, 4)
        } else if let Some(index) = buf.find("\n\n") {
            (index, 2)
        } else {
            return;
        };
        let raw: String = buf.drain(..index + sep).collect();
        if let Some(data) = event_data(&raw) {
            payloads.push(data);
            if payloads.last().map(String::as_str) == Some(DONE) {
                return;
            }
        }
    }
}

fn event_data(raw: &str) -> Option<String> {
    let mut data_lines = Vec::new();
    for line in raw.split('\n') {
        let line = line.trim_end_matches('\r');
        if let Some(rest) = line.strip_prefix("data:") {
            data_lines.push(rest.strip_prefix(' ').unwrap_or(rest));
        }
    }
    if data_lines.is_empty() {
        None
    } else {
        Some(data_lines.join("\n"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::Bytes;
    use futures::stream;

    #[tokio::test]
    async fn requires_done() {
        let stream = stream::iter(vec![Ok::<_, std::io::Error>(Bytes::from(
            "data: {\"x\":1}\n\n",
        ))]);
        let err = parse_sse(stream).await.unwrap_err();
        assert_eq!(err.code(), STREAM_CLOSED_CODE);
    }

    #[tokio::test]
    async fn yields_payloads_then_done() {
        let stream = stream::iter(vec![Ok::<_, std::io::Error>(Bytes::from(
            "data: one\n\ndata: [DONE]\n\n",
        ))]);
        let payloads = parse_sse(stream).await.unwrap();
        assert_eq!(payloads, vec!["one".to_string(), DONE.to_string()]);
    }

    #[tokio::test]
    async fn unterminated_tail_is_not_flushed() {
        let stream = stream::iter(vec![Ok::<_, std::io::Error>(Bytes::from(
            "data: one\n\ndata: incomplete-without-blank-line",
        ))]);
        let err = parse_sse(stream).await.unwrap_err();
        assert_eq!(err.code(), STREAM_CLOSED_CODE);
    }
}
