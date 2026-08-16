
//
// A wrapper around a tokio byte stream that recovers from mid-stream
// parse errors and yields a `Result<Chunk, _>` per chunk. The
// TypeScript version used Node's `ReadableStream` and a custom parser.
// The Rust port uses `futures::Stream` and a tiny line splitter.

use futures::stream::Stream;
use std::pin::Pin;
use std::task::{Context, Poll};

#[derive(Debug, Clone)]
pub struct Chunk {
    pub bytes: Vec<u8>,
    pub index: u64,
}

#[derive(Debug)]
pub enum StreamError {
    Decoding,
    Overflow,
    Other(String),
}

impl std::fmt::Display for StreamError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StreamError::Decoding => write!(f, "decoding error"),
            StreamError::Overflow => write!(f, "overflow"),
            StreamError::Other(s) => write!(f, "{}", s),
        }
    }
}
impl std::error::Error for StreamError {}

pub struct SafeStream<S: Stream<Item = Result<Vec<u8>, std::io::Error>> + Unpin> {
    inner: S,
    max_chunk: usize,
    next_index: u64,
    failed_in_row: u32,
}

impl<S: Stream<Item = Result<Vec<u8>, std::io::Error>> + Unpin> SafeStream<S> {
    pub fn new(inner: S) -> Self {
        Self { inner, max_chunk: 1024 * 1024, next_index: 0, failed_in_row: 0 }
    }

    pub fn with_max_chunk(mut self, max: usize) -> Self { self.max_chunk = max; self }
}

impl<S: Stream<Item = Result<Vec<u8>, std::io::Error>> + Unpin> Stream for SafeStream<S> {
    type Item = Result<Chunk, StreamError>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let this = &mut *self;
        match Pin::new(&mut this.inner).poll_next(cx) {
            Poll::Ready(Some(Ok(bytes))) => {
                if bytes.len() > this.max_chunk {
                    return Poll::Ready(Some(Err(StreamError::Overflow)));
                }
                let chunk = Chunk { bytes, index: this.next_index };
                this.next_index += 1;
                this.failed_in_row = 0;
                Poll::Ready(Some(Ok(chunk)))
            }
            Poll::Ready(Some(Err(e))) => {
                this.failed_in_row += 1;
                if this.failed_in_row > 16 {
                    return Poll::Ready(Some(Err(StreamError::Other(format!("too many consecutive errors: {}", e)))));
                }
                Poll::Ready(Some(Err(StreamError::Other(e.to_string()))))
            }
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Pending => Poll::Pending,
        }
    }
}
