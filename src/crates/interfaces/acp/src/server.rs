//! NDJSON JSON-RPC agent-side connection.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use dsh_agent_loop::ReactLoopAgent;
use dsh_agent_runtime::{Agent, CancelOptions};
use dsh_core::{last_turn_reason, ProductRuntime};
use dsh_core_types::{human_text, ContentBlock};
use dsh_events::{AgentCancelCause, SessionEventBody};
use parking_lot::Mutex;
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;

use crate::codec::{
    acp_prompt_to_text, prompt_has_unsupported, turn_end_to_stop_reason, PromptBlock,
    PROTOCOL_VERSION,
};
use crate::RpcError;

struct SessionRecord {
    agent: Arc<ReactLoopAgent>,
    busy: AtomicBool,
    cancelled: AtomicBool,
}

/// ACP session table plus the assembled product runtime.
pub struct AcpServer {
    runtime: Arc<ProductRuntime>,
    sessions: Mutex<HashMap<String, Arc<SessionRecord>>>,
    outgoing: mpsc::UnboundedSender<String>,
}

impl AcpServer {
    pub fn new(runtime: Arc<ProductRuntime>, outgoing: mpsc::UnboundedSender<String>) -> Self {
        Self {
            runtime,
            sessions: Mutex::new(HashMap::new()),
            outgoing,
        }
    }

    /// Handle one NDJSON request or notification. Returns a response line for requests.
    pub async fn handle_line(&self, line: &str) -> Option<String> {
        let value: Value = match serde_json::from_str(line) {
            Ok(value) => value,
            Err(error) => {
                return Some(error_response(
                    Value::Null,
                    RpcError::Parse(error.to_string()),
                ));
            }
        };
        let method = value
            .get("method")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let params = value.get("params").cloned().unwrap_or(Value::Null);
        let Some(id) = value.get("id").cloned() else {
            if method == "session/cancel" {
                self.cancel(&params);
            }
            return None;
        };
        let result = self.dispatch(&method, params).await;
        Some(match result {
            Ok(value) => json!({"jsonrpc":"2.0","id": id, "result": value}).to_string(),
            Err(error) => error_response(id, error),
        })
    }

    async fn dispatch(&self, method: &str, params: Value) -> Result<Value, RpcError> {
        match method {
            "initialize" => Ok(json!({
                "protocolVersion": PROTOCOL_VERSION,
                "agentInfo": { "name": "deepseek-harness-acp", "version": "0.1.0" },
                "agentCapabilities": {
                    "promptCapabilities": {
                        "image": false,
                        "audio": false,
                        "embeddedContext": false
                    }
                },
                "authMethods": []
            })),
            "authenticate" => Ok(Value::Null),
            "session/new" => self.new_session(params).await,
            "session/prompt" => self.prompt(params).await,
            "session/cancel" => {
                self.cancel(&params);
                Ok(Value::Null)
            }
            other => Err(RpcError::MethodNotFound(other.to_string())),
        }
    }

    async fn new_session(&self, params: Value) -> Result<Value, RpcError> {
        let params: NewSessionParams = serde_json::from_value(params)
            .map_err(|error| RpcError::InvalidParams(error.to_string()))?;
        if !Path::new(&params.cwd).is_absolute() {
            return Err(RpcError::InvalidParams(format!(
                "cwd must be an absolute path: {}",
                params.cwd
            )));
        }
        if !params.additional_directories.is_empty() {
            return Err(RpcError::InvalidParams(
                "additionalDirectories is not supported".into(),
            ));
        }
        if !params.mcp_servers.is_empty() {
            return Err(RpcError::InvalidParams(
                "mcpServers is not supported".into(),
            ));
        }
        let agent = self
            .runtime
            .create_agent(PathBuf::from(&params.cwd))
            .await
            .map_err(|error| RpcError::Internal(error.to_string()))?;
        let session_id = agent.id().to_string();
        self.sessions.lock().insert(
            session_id.clone(),
            Arc::new(SessionRecord {
                agent,
                busy: AtomicBool::new(false),
                cancelled: AtomicBool::new(false),
            }),
        );
        Ok(json!({ "sessionId": session_id }))
    }

    async fn prompt(&self, params: Value) -> Result<Value, RpcError> {
        let params: PromptParams = serde_json::from_value(params)
            .map_err(|error| RpcError::InvalidParams(error.to_string()))?;
        let record = self.require_session(&params.session_id)?;
        if record.busy.swap(true, Ordering::SeqCst) {
            return Err(RpcError::InvalidParams(
                "a prompt is already in flight for this session".into(),
            ));
        }
        struct BusyGuard<'a>(&'a AtomicBool);
        impl Drop for BusyGuard<'_> {
            fn drop(&mut self) {
                self.0.store(false, Ordering::SeqCst);
            }
        }
        let _busy = BusyGuard(&record.busy);
        if prompt_has_unsupported(&params.prompt) {
            return Err(RpcError::InvalidParams(
                "only text and resource_link prompt content is supported".into(),
            ));
        }
        let text = acp_prompt_to_text(&params.prompt);
        if text.trim().is_empty() {
            return Err(RpcError::InvalidParams("empty prompt".into()));
        }
        let before = record.agent.session().events().len();
        record.cancelled.store(false, Ordering::SeqCst);
        record.agent.followup(human_text(text));
        record.agent.when_idle().await;
        if record.cancelled.load(Ordering::SeqCst) {
            return Ok(json!({ "stopReason": "cancelled" }));
        }
        for event in record.agent.session().events().into_iter().skip(before) {
            if let SessionEventBody::AssistantMessage { message, .. } = event.body {
                for block in message.content {
                    if let ContentBlock::Text(text) = block {
                        if !text.text.is_empty() {
                            self.notify_chunk(&params.session_id, &text.text);
                        }
                    } else if let ContentBlock::Image(image) = block {
                        self.notify_chunk(
                            &params.session_id,
                            &format!("[image attachment {}]", image.attachment_id),
                        );
                    }
                }
            }
        }
        let stop = match last_turn_reason(record.agent.session().as_ref()) {
            Some(reason) => {
                if matches!(reason, dsh_events::TurnEndReason::Error { .. }) {
                    return Err(RpcError::Internal(format!(
                        "turn failed: {}",
                        match &reason {
                            dsh_events::TurnEndReason::Error { error } => error.message.clone(),
                            _ => String::new(),
                        }
                    )));
                }
                turn_end_to_stop_reason(&reason)
            }
            None => "cancelled",
        };
        Ok(json!({ "stopReason": stop }))
    }

    fn cancel(&self, params: &Value) {
        let Ok(params) = serde_json::from_value::<SessionIdParams>(params.clone()) else {
            return;
        };
        let Some(record) = self.sessions.lock().get(&params.session_id).cloned() else {
            return;
        };
        record.cancelled.store(true, Ordering::SeqCst);
        record
            .agent
            .cancel(AgentCancelCause::User, CancelOptions::default());
    }

    fn require_session(&self, session_id: &str) -> Result<Arc<SessionRecord>, RpcError> {
        self.sessions
            .lock()
            .get(session_id)
            .cloned()
            .ok_or_else(|| RpcError::InvalidParams(format!("unknown session: {session_id}")))
    }

    fn notify_chunk(&self, session_id: &str, text: &str) {
        let line = json!({
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {
                "sessionId": session_id,
                "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": { "type": "text", "text": text }
                }
            }
        })
        .to_string();
        let _ = self.outgoing.send(line);
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NewSessionParams {
    cwd: String,
    #[serde(default)]
    mcp_servers: Vec<Value>,
    #[serde(default)]
    additional_directories: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PromptParams {
    session_id: String,
    prompt: Vec<PromptBlock>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SessionIdParams {
    session_id: String,
}

fn error_response(id: Value, error: RpcError) -> String {
    json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": { "code": error.code(), "message": error.to_string() }
    })
    .to_string()
}

async fn write_line<W: AsyncWrite + Unpin>(
    writer: &mut W,
    line: &str,
) -> Result<(), std::io::Error> {
    writer.write_all(line.as_bytes()).await?;
    writer.write_all(b"\n").await?;
    writer.flush().await?;
    Ok(())
}

fn json_method(line: &str) -> String {
    serde_json::from_str::<Value>(line)
        .ok()
        .and_then(|value| {
            value
                .get("method")
                .and_then(Value::as_str)
                .map(str::to_string)
        })
        .unwrap_or_default()
}

/// Serve ACP over any NDJSON reader/writer pair.
///
/// `session/prompt` runs on a spawned task so `session/cancel` can be read
/// while a turn is in flight. All writes happen on this task.
pub async fn serve<R, W>(
    reader: R,
    mut writer: W,
    runtime: Arc<ProductRuntime>,
) -> Result<(), std::io::Error>
where
    R: AsyncBufRead + Unpin,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    let server = Arc::new(AcpServer::new(runtime, tx));
    let mut lines = reader.lines();
    let mut prompt: Option<tokio::task::JoinHandle<Option<String>>> = None;
    loop {
        tokio::select! {
            Some(line) = rx.recv() => {
                write_line(&mut writer, &line).await?;
            }
            join = async {
                match prompt.as_mut() {
                    Some(handle) => handle.await,
                    None => std::future::pending().await,
                }
            } => {
                prompt = None;
                while let Ok(line) = rx.try_recv() {
                    write_line(&mut writer, &line).await?;
                }
                if let Ok(Some(response)) = join {
                    write_line(&mut writer, &response).await?;
                }
            }
            line = lines.next_line() => {
                let Some(line) = line? else {
                    break;
                };
                if line.trim().is_empty() {
                    continue;
                }
                if json_method(&line) == "session/prompt" {
                    let server = Arc::clone(&server);
                    prompt = Some(tokio::spawn(async move { server.handle_line(&line).await }));
                } else if let Some(response) = server.handle_line(&line).await {
                    write_line(&mut writer, &response).await?;
                }
            }
        }
    }
    if let Some(handle) = prompt.take() {
        if let Ok(Some(response)) = handle.await {
            write_line(&mut writer, &response).await?;
        }
    }
    Ok(())
}

/// Production stdio transport. Logs must not be written to stdout.
pub async fn serve_stdio(runtime: Arc<ProductRuntime>) -> Result<(), std::io::Error> {
    let stdin = BufReader::new(tokio::io::stdin());
    let stdout = tokio::io::stdout();
    serve(stdin, stdout, runtime).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_testkit::boot_mock;
    use serde_json::{json, Value};
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt};

    #[cfg(unix)]
    #[tokio::test]
    async fn initialize_new_session_and_prompt() {
        let workspace = tempfile::tempdir().unwrap();
        let home = tempfile::tempdir().unwrap();
        let runtime = Arc::new(boot_mock(
            workspace.path().to_path_buf(),
            home.path().to_path_buf(),
            vec![dsh_llm_mock::MockTurn::Text("from-acp".into())],
        ));
        // UnixStream::into_split is lock-free. tokio::io::duplex + split uses a
        // std mutex around both directions and deadlocks the current-thread
        // runtime when the test reads while the server writes.
        let (client, server) = tokio::net::UnixStream::pair().unwrap();
        let (server_read, server_write) = server.into_split();
        let (client_read, mut client_write) = client.into_split();
        let mut client_read = BufReader::new(client_read);
        let serve_runtime = Arc::clone(&runtime);
        let server_task = tokio::spawn(async move {
            serve(BufReader::new(server_read), server_write, serve_runtime).await
        });
        client_write
            .write_all(
                br#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
"#,
            )
            .await
            .unwrap();
        let init = read_json(&mut client_read).await;
        assert_eq!(init["result"]["protocolVersion"], 1);
        let cwd = workspace.path().canonicalize().unwrap();
        let new_session = format!(
            "{}\n",
            json!({"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd": cwd, "mcpServers": []}})
        );
        client_write
            .write_all(new_session.as_bytes())
            .await
            .unwrap();
        let created = read_json(&mut client_read).await;
        let session_id = created["result"]["sessionId"].as_str().unwrap().to_string();
        let prompt = format!(
            "{}\n",
            json!({"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{
                "sessionId": session_id,
                "prompt": [{"type":"text","text":"hi"}]
            }})
        );
        client_write.write_all(prompt.as_bytes()).await.unwrap();
        let mut saw_text = false;
        let mut stop = None;
        for _ in 0..8 {
            let msg = read_json(&mut client_read).await;
            if msg.get("method").and_then(Value::as_str) == Some("session/update") {
                if msg["params"]["update"]["content"]["text"] == "from-acp" {
                    saw_text = true;
                }
            } else if msg.get("id") == Some(&json!(3)) {
                stop = msg["result"]["stopReason"].as_str().map(str::to_string);
                break;
            }
        }
        assert!(saw_text);
        assert_eq!(stop.as_deref(), Some("end_turn"));
        drop(client_write);
        server_task.await.unwrap().unwrap();
    }

    async fn read_json<R: tokio::io::AsyncBufRead + Unpin>(reader: &mut R) -> Value {
        let mut line = String::new();
        let n = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            reader.read_line(&mut line),
        )
        .await
        .unwrap_or_else(|_| panic!("timed out waiting for ACP NDJSON, partial={line:?}"))
        .unwrap();
        assert!(n > 0, "ACP stream closed, partial={line:?}");
        serde_json::from_str(line.trim()).unwrap_or_else(|error| {
            panic!("invalid ACP NDJSON {line:?}: {error}");
        })
    }
}
