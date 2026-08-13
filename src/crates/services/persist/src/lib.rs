//! JSONL session persistence. Header line first, then one event per line.

use std::path::{Path, PathBuf};

use async_trait::async_trait;
use dsh_core_types::SessionId;
use dsh_events::SessionHeader;
use dsh_runtime_ports::{PortError, PortErrorKind, PortResult, SessionPersistPort};
use dsh_session::Session;

pub struct JsonlPersist {
    root: PathBuf,
}

impl JsonlPersist {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    fn path_for(&self, id: &SessionId) -> PathBuf {
        self.root.join(format!("{id}.jsonl"))
    }

    pub async fn save_session(&self, session: &Session) -> PortResult<()> {
        let jsonl = session
            .to_jsonl()
            .map_err(|error| PortError::new(PortErrorKind::Backend, error.to_string()))?;
        self.save(&session.header(), &jsonl).await
    }
}

#[async_trait]
impl SessionPersistPort for JsonlPersist {
    async fn save(&self, header: &SessionHeader, events_jsonl: &str) -> PortResult<()> {
        tokio::fs::create_dir_all(&self.root).await.map_err(|error| {
            PortError::new(PortErrorKind::Backend, error.to_string())
        })?;
        // The payload already includes the header as its first line.
        let body = if events_jsonl.starts_with('{') {
            events_jsonl.to_string()
        } else {
            format!(
                "{}\n{events_jsonl}",
                serde_json::to_string(header).map_err(|error| {
                    PortError::new(PortErrorKind::Backend, error.to_string())
                })?
            )
        };
        let tmp = self.path_for(&header.id).with_extension("jsonl.tmp");
        tokio::fs::write(&tmp, body).await.map_err(|error| {
            PortError::new(PortErrorKind::Backend, error.to_string())
        })?;
        tokio::fs::rename(&tmp, self.path_for(&header.id))
            .await
            .map_err(|error| PortError::new(PortErrorKind::Backend, error.to_string()))?;
        Ok(())
    }

    async fn load(&self, id: &SessionId) -> PortResult<Option<(SessionHeader, String)>> {
        let path = self.path_for(id);
        match tokio::fs::read_to_string(&path).await {
            Ok(text) => {
                let session = Session::from_jsonl(&text)
                    .map_err(|error| PortError::new(PortErrorKind::Backend, error.to_string()))?;
                if session.header().version != dsh_events::SESSION_FORMAT_VERSION {
                    return Err(PortError::new(
                        PortErrorKind::InvalidRequest,
                        format!(
                            "session format version {} is not {}",
                            session.header().version,
                            dsh_events::SESSION_FORMAT_VERSION
                        ),
                    ));
                }
                Ok(Some((session.header(), text)))
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(PortError::new(PortErrorKind::Backend, error.to_string())),
        }
    }
}

pub fn session_dir(home: &Path) -> PathBuf {
    home.join("sessions")
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::{human_text, SessionId};
    use dsh_events::{SessionEventBody, SessionHeader, SurfaceOp};

    #[tokio::test]
    async fn roundtrip_jsonl() {
        let dir = tempfile::tempdir().unwrap();
        let persist = JsonlPersist::new(dir.path());
        let session = Session::create(SessionHeader::new(SessionId::new("s1"), 1)).unwrap();
        session
            .append(
                SessionEventBody::UserMessage(human_text("hi")),
                Some(SurfaceOp::Append),
                None,
            )
            .unwrap();
        persist.save_session(&session).await.unwrap();
        let loaded = persist.load(&SessionId::new("s1")).await.unwrap().unwrap();
        assert_eq!(loaded.0.id.as_str(), "s1");
        let restored = Session::from_jsonl(&loaded.1).unwrap();
        assert_eq!(restored.derive_messages().len(), 1);
    }
}
