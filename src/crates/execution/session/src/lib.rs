//! Append-only session log. Model history is derived from the surface.

use std::sync::Arc;

use chrono::Utc;
use dsh_core_types::{json::is_json_value, Message, SessionId};
use dsh_events::{
    EpochHeader, RequestContext, RequestHeaderReason, SessionEvent, SessionEventBody, SessionHeader,
    SurfaceOp, SESSION_FORMAT_VERSION,
};
use parking_lot::RwLock;
use thiserror::Error;

mod surface;
pub use surface::{derive_event_message, fold_surface, SurfaceFoldResult};

#[derive(Debug, Error)]
pub enum SessionError {
    #[error("session format version {found} is not {SESSION_FORMAT_VERSION}; refusing to load")]
    UnsupportedVersion { found: u32 },
    #[error("surface metadata is required on {0} and forbidden on log-only events")]
    SurfaceIntent(&'static str),
    #[error("event data is not lossless JSON")]
    NotJson,
    #[error("session `{0}` already exists")]
    AlreadyExists(String),
    #[error("session `{0}` was not found")]
    NotFound(String),
}

/// One live session: append-only log plus derived surface.
pub struct Session {
    header: RwLock<SessionHeader>,
    log: RwLock<Vec<SessionEvent>>,
    surface: RwLock<surface::SurfaceState>,
    derived: RwLock<DerivedCache>,
}

struct DerivedCache {
    messages: Vec<Message>,
    nodes: usize,
    generation: u64,
}

impl Session {
    pub fn create(mut header: SessionHeader) -> Result<Arc<Self>, SessionError> {
        if header.version != SESSION_FORMAT_VERSION {
            return Err(SessionError::UnsupportedVersion {
                found: header.version,
            });
        }
        header.version = SESSION_FORMAT_VERSION;
        Ok(Arc::new(Self {
            header: RwLock::new(header),
            log: RwLock::new(Vec::new()),
            surface: RwLock::new(surface::SurfaceState::default()),
            derived: RwLock::new(DerivedCache {
                messages: Vec::new(),
                nodes: 0,
                generation: 0,
            }),
        }))
    }

    pub fn restore(header: SessionHeader, events: Vec<SessionEvent>) -> Result<Arc<Self>, SessionError> {
        if header.version != SESSION_FORMAT_VERSION {
            return Err(SessionError::UnsupportedVersion {
                found: header.version,
            });
        }
        let session = Self::create(header)?;
        for event in events {
            session.replay(event)?;
        }
        Ok(session)
    }

    pub fn id(&self) -> SessionId {
        self.header.read().id.clone()
    }

    pub fn header(&self) -> SessionHeader {
        self.header.read().clone()
    }

    pub fn events(&self) -> Vec<SessionEvent> {
        self.log.read().clone()
    }

    pub fn append(
        &self,
        body: SessionEventBody,
        surface_op: Option<SurfaceOp>,
        source_event_seqs: Option<Vec<u64>>,
    ) -> Result<SessionEvent, SessionError> {
        let snapshot = serde_json::to_value(&body).map_err(|_| SessionError::NotJson)?;
        if !is_json_value(&snapshot) {
            return Err(SessionError::NotJson);
        }
        let eligible = matches!(
            body,
            SessionEventBody::UserMessage(_)
                | SessionEventBody::AssistantMessage { .. }
                | SessionEventBody::ToolResult { .. }
        );
        if eligible && surface_op.is_none() {
            return Err(SessionError::SurfaceIntent(body.event_type()));
        }
        if !eligible && surface_op.is_some() {
            return Err(SessionError::SurfaceIntent(body.event_type()));
        }
        let mut log = self.log.write();
        let seq = log.len() as u64;
        let event = SessionEvent {
            body,
            seq,
            time: Utc::now().timestamp_millis(),
            ignorable: None,
            surface_op,
            source_event_seqs,
        };
        surface::apply(&mut self.surface.write(), &event)?;
        log.push(event.clone());
        Ok(event)
    }

    fn replay(&self, event: SessionEvent) -> Result<(), SessionError> {
        surface::apply(&mut self.surface.write(), &event)?;
        self.log.write().push(event);
        Ok(())
    }

    pub fn derive_messages(&self) -> Vec<Message> {
        let surface = self.surface.read().clone();
        let log = self.log.read();
        let mut cache = self.derived.write();
        if cache.generation != surface.replace_generation {
            cache.messages.clear();
            cache.nodes = 0;
            cache.generation = surface.replace_generation;
        }
        for seq in surface.nodes.iter().skip(cache.nodes) {
            if let Some(msg) = derive_event_message(&log[*seq as usize]) {
                cache.messages.push(msg);
            }
        }
        cache.nodes = surface.nodes.len();
        cache.messages.clone()
    }

    pub fn request_header(&self) -> Option<EpochHeader> {
        self.log.read().iter().rev().find_map(|event| match &event.body {
            SessionEventBody::RequestHeader { header, .. } => Some(header.clone()),
            _ => None,
        })
    }

    pub fn request_header_reason(&self) -> Option<RequestHeaderReason> {
        self.log.read().iter().rev().find_map(|event| match &event.body {
            SessionEventBody::RequestHeader { reason, .. } => Some(*reason),
            _ => None,
        })
    }

    pub fn request_context(&self) -> Option<RequestContext> {
        self.log.read().iter().rev().find_map(|event| match &event.body {
            SessionEventBody::RequestContext(ctx) => Some(ctx.clone()),
            _ => None,
        })
    }

    pub fn last_turn(&self) -> u32 {
        self.log
            .read()
            .iter()
            .rev()
            .find_map(|event| match &event.body {
                SessionEventBody::TurnStart { turn } => Some(*turn),
                _ => None,
            })
            .unwrap_or(0)
    }

    pub fn to_jsonl(&self) -> Result<String, serde_json::Error> {
        let header = serde_json::to_string(&self.header())?;
        let mut out = String::new();
        out.push_str(&header);
        out.push('\n');
        for event in self.events() {
            out.push_str(&serde_json::to_string(&event)?);
            out.push('\n');
        }
        Ok(out)
    }

    pub fn from_jsonl(text: &str) -> Result<Arc<Self>, SessionError> {
        let mut lines = text.lines().filter(|l| !l.trim().is_empty());
        let header_line = lines.next().ok_or_else(|| SessionError::NotJson)?;
        let header: SessionHeader =
            serde_json::from_str(header_line).map_err(|_| SessionError::NotJson)?;
        let mut events = Vec::new();
        for line in lines {
            events.push(serde_json::from_str(line).map_err(|_| SessionError::NotJson)?);
        }
        Self::restore(header, events)
    }
}

#[derive(Default)]
pub struct SessionStore {
    sessions: parking_lot::Mutex<std::collections::HashMap<String, Arc<Session>>>,
}

impl SessionStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn create(&self, header: SessionHeader) -> Result<Arc<Session>, SessionError> {
        let mut map = self.sessions.lock();
        let key = header.id.to_string();
        if map.contains_key(&key) {
            return Err(SessionError::AlreadyExists(key));
        }
        let session = Session::create(header)?;
        map.insert(key, Arc::clone(&session));
        Ok(session)
    }

    pub fn get(&self, id: &SessionId) -> Result<Arc<Session>, SessionError> {
        self.sessions
            .lock()
            .get(id.as_str())
            .cloned()
            .ok_or_else(|| SessionError::NotFound(id.to_string()))
    }

    pub fn insert_restored(&self, session: Arc<Session>) -> Result<(), SessionError> {
        let mut map = self.sessions.lock();
        let key = session.id().to_string();
        if map.contains_key(&key) {
            return Err(SessionError::AlreadyExists(key));
        }
        map.insert(key, session);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::{human_text, SessionId};
    use dsh_events::SessionEventBody;

    fn header() -> SessionHeader {
        SessionHeader::new(SessionId::new("s1"), 1)
    }

    #[test]
    fn rejects_wrong_version() {
        let mut h = header();
        h.version = 9;
        let err = Session::create(h).unwrap_err();
        assert!(matches!(err, SessionError::UnsupportedVersion { found: 9 }));
    }

    #[test]
    fn derive_messages_follows_surface_append() {
        let session = Session::create(header()).unwrap();
        session
            .append(
                SessionEventBody::TurnStart { turn: 1 },
                None,
                None,
            )
            .unwrap();
        session
            .append(
                SessionEventBody::UserMessage(human_text("hi")),
                Some(SurfaceOp::Append),
                None,
            )
            .unwrap();
        let messages = session.derive_messages();
        assert_eq!(messages.len(), 1);
        assert_eq!(
            dsh_core_types::content::flatten_text(&messages[0].content),
            "hi"
        );
    }

    #[test]
    fn jsonl_roundtrip() {
        let session = Session::create(header()).unwrap();
        session
            .append(
                SessionEventBody::UserMessage(human_text("hi")),
                Some(SurfaceOp::Append),
                None,
            )
            .unwrap();
        let text = session.to_jsonl().unwrap();
        let restored = Session::from_jsonl(&text).unwrap();
        assert_eq!(restored.derive_messages().len(), 1);
    }
}
