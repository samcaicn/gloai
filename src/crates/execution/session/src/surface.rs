//! Ordered surface of message-producing events.

use dsh_core_types::Message;
use dsh_events::{SessionEvent, SessionEventBody, SurfaceOp};
use thiserror::Error;

use crate::SessionError;

#[derive(Clone, Debug, Default)]
pub struct SurfaceState {
    pub nodes: Vec<u64>,
    pub replace_generation: u64,
}

#[derive(Clone, Debug)]
pub struct SurfaceFoldResult {
    pub nodes: Vec<u64>,
    pub replace_generation: u64,
}

pub fn derive_event_message(event: &SessionEvent) -> Option<Message> {
    match &event.body {
        SessionEventBody::UserMessage(message) => Some(message.clone()),
        SessionEventBody::AssistantMessage { message, .. } => {
            if message.content.is_empty() {
                None
            } else {
                Some(message.clone())
            }
        }
        SessionEventBody::ToolResult { message, .. } => Some(message.clone()),
        _ => None,
    }
}

pub fn fold_surface(events: &[SessionEvent]) -> Result<SurfaceFoldResult, SessionError> {
    let mut state = SurfaceState::default();
    for event in events {
        apply(&mut state, event)?;
    }
    Ok(SurfaceFoldResult {
        nodes: state.nodes,
        replace_generation: state.replace_generation,
    })
}

pub fn apply(state: &mut SurfaceState, event: &SessionEvent) -> Result<(), SessionError> {
    if !event.is_surface_eligible() {
        return Ok(());
    }
    match event.surface_op.as_ref() {
        Some(SurfaceOp::Append) => {
            state.nodes.push(event.seq);
            Ok(())
        }
        Some(SurfaceOp::Replace { start, end, .. }) => {
            let start_idx = state
                .nodes
                .iter()
                .position(|seq| *seq == *start)
                .ok_or(SessionError::SurfaceIntent("replace start"))?;
            let end_idx = state
                .nodes
                .iter()
                .position(|seq| *seq == *end)
                .ok_or(SessionError::SurfaceIntent("replace end"))?;
            if end_idx < start_idx {
                return Err(SessionError::SurfaceIntent("replace range"));
            }
            state.nodes.splice(start_idx..=end_idx, [event.seq]);
            state.replace_generation += 1;
            Ok(())
        }
        None => Err(SessionError::SurfaceIntent(event.event_type())),
    }
}

#[derive(Debug, Error)]
#[error("surface fold failed")]
pub struct SurfaceFoldError;
