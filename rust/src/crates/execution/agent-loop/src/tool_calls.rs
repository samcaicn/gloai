//! Schedules one assistant step's tool calls. Exclusive calls form barriers;
//! parallel calls use a bounded rolling pool. Results commit in model order.

use std::sync::Arc;

use dsh_agent_runtime::Inbox;
use dsh_core_types::{
    create_tool_result_message, create_user_message, ContentBlock, ToolCallBlock,
};
use dsh_events::{InboxTarget, SessionEventBody, SurfaceOp, ToolErrorIdentity};
use dsh_session::Session;
use dsh_tool_contracts::{parse_arguments, ExecutionMode, ToolExecutionInput, ToolRegistry};
use tokio_util::sync::CancellationToken;

pub(crate) struct ToolDispatch {
    pub session: Arc<Session>,
    pub inbox: Arc<Inbox>,
    pub tools: Arc<ToolRegistry>,
    pub turn: u32,
    pub step: u32,
    pub tool_calls: Vec<ToolCallBlock>,
    pub token: CancellationToken,
    pub max_parallel: usize,
}

pub(crate) async fn execute_tool_calls(dispatch: ToolDispatch) -> Result<bool, String> {
    let ToolDispatch {
        session,
        inbox,
        tools,
        turn,
        step,
        tool_calls,
        token,
        max_parallel,
    } = dispatch;
    let mut next = 0;
    let mut concluded = false;
    while next < tool_calls.len() {
        if token.is_cancelled() {
            for call in tool_calls.iter().skip(next) {
                append_skipped(&session, turn, step, call)?;
            }
            return Ok(concluded);
        }
        let mode = tools.execution_mode(&tool_calls[next].name);
        let group = if mode == ExecutionMode::Parallel {
            let mut end = next + 1;
            while end < tool_calls.len()
                && tools.execution_mode(&tool_calls[end].name) == ExecutionMode::Parallel
                && end - next < max_parallel
            {
                end += 1;
            }
            &tool_calls[next..end]
        } else {
            &tool_calls[next..next + 1]
        };
        let consumed = group.len();
        let mut results = Vec::with_capacity(consumed);
        if mode == ExecutionMode::Parallel && consumed > 1 {
            let mut handles = Vec::new();
            for call in group {
                let tools = Arc::clone(&tools);
                let call = call.clone();
                let token = token.clone();
                handles.push(tokio::spawn(
                    async move { run_one(tools, call, token).await },
                ));
            }
            for handle in handles {
                results.push(handle.await.map_err(|e| e.to_string())?);
            }
        } else {
            for call in group {
                results.push(run_one(Arc::clone(&tools), call.clone(), token.clone()).await);
            }
        }
        for (call, result) in group.iter().zip(results) {
            session
                .append(
                    SessionEventBody::ToolCall {
                        turn,
                        step,
                        call_id: call.id.clone(),
                        name: call.name.clone(),
                        arguments: call.arguments.clone(),
                    },
                    None,
                    None,
                )
                .map_err(|e| e.to_string())?;
            let message = create_tool_result_message(
                call.id.clone(),
                result.content.clone(),
                result.is_error,
            );
            let error = result.error.map(|identity| ToolErrorIdentity {
                name: identity.name,
                code: identity.code,
            });
            session
                .append(
                    SessionEventBody::ToolResult {
                        turn,
                        step,
                        message,
                        error,
                        meta: result.meta,
                    },
                    Some(SurfaceOp::Append),
                    None,
                )
                .map_err(|e| e.to_string())?;
            if result.concludes_turn {
                concluded = true;
            }
            let notice = dsh_core_types::flatten_text(&result.content);
            if !notice.is_empty() && result.is_error {
                let start = inbox.next_step().len();
                let _ = inbox.splice(
                    InboxTarget::NextStep,
                    start,
                    0,
                    vec![create_user_message(
                        vec![ContentBlock::text(format!(
                            "<system-reminder>Tool `{}` failed: {notice}</system-reminder>",
                            call.name
                        ))],
                        dsh_core_types::MessageSource::Plugin {
                            plugin: "tool-result".into(),
                            form: Some("notice".into()),
                        },
                    )],
                );
            }
        }
        next += consumed;
        if token.is_cancelled() {
            for call in tool_calls.iter().skip(next) {
                append_skipped(&session, turn, step, call)?;
            }
            return Ok(concluded);
        }
    }
    Ok(concluded)
}

async fn run_one(
    tools: Arc<ToolRegistry>,
    call: ToolCallBlock,
    token: CancellationToken,
) -> dsh_tool_contracts::ToolExecutionResult {
    if token.is_cancelled() {
        return dsh_tool_contracts::ToolExecutionResult::error_text(
            "tool aborted before dispatch",
            "ABORTED",
        );
    }
    tools
        .execute(ToolExecutionInput {
            call_id: call.id,
            name: call.name,
            arguments: parse_arguments(&call.arguments),
        })
        .await
}

fn append_skipped(
    session: &Session,
    turn: u32,
    step: u32,
    call: &ToolCallBlock,
) -> Result<(), String> {
    session
        .append(
            SessionEventBody::ToolCall {
                turn,
                step,
                call_id: call.id.clone(),
                name: call.name.clone(),
                arguments: call.arguments.clone(),
            },
            None,
            None,
        )
        .map_err(|e| e.to_string())?;
    let message = create_tool_result_message(
        call.id.clone(),
        vec![ContentBlock::text("tool aborted before dispatch")],
        true,
    );
    session
        .append(
            SessionEventBody::ToolResult {
                turn,
                step,
                message,
                error: Some(ToolErrorIdentity {
                    name: "ToolError".into(),
                    code: "ABORTED".into(),
                }),
                meta: None,
            },
            Some(SurfaceOp::Append),
            None,
        )
        .map_err(|e| e.to_string())?;
    Ok(())
}
