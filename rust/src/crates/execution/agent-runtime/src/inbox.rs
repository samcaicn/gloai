//! Incremental projection of durable agent inbox events.

use std::sync::Arc;

use dsh_core_types::{MessageId, UserMessage};
use dsh_events::{InboxTarget, SessionEventBody};
use dsh_session::{Session, SessionError};
use parking_lot::Mutex;

struct State {
    next_turn: Vec<UserMessage>,
    next_step: Vec<UserMessage>,
}

pub struct Inbox {
    session: Arc<Session>,
    state: Mutex<State>,
}

impl Inbox {
    pub fn new(session: Arc<Session>) -> Arc<Self> {
        let inbox = Arc::new(Self {
            session: Arc::clone(&session),
            state: Mutex::new(State {
                next_turn: Vec::new(),
                next_step: Vec::new(),
            }),
        });
        let seed = session.header().seed_length.unwrap_or(0);
        for event in session.events().into_iter().skip(seed) {
            if let SessionEventBody::InboxSpliced {
                target,
                start,
                removed_count,
                inserted,
                ..
            } = event.body
            {
                inbox
                    .apply(target, start, removed_count.unwrap_or(0), inserted, false)
                    .expect("persisted inbox splice must apply");
            }
        }
        inbox
    }

    pub fn next_turn(&self) -> Vec<UserMessage> {
        self.state.lock().next_turn.clone()
    }

    pub fn next_step(&self) -> Vec<UserMessage> {
        self.state.lock().next_step.clone()
    }

    pub fn has_pending(&self) -> bool {
        let state = self.state.lock();
        !state.next_turn.is_empty() || !state.next_step.is_empty()
    }

    pub fn clear(&self) -> Result<(), SessionError> {
        let step_len = self.next_step().len();
        self.splice(InboxTarget::NextStep, 0, step_len, Vec::new())?;
        let turn_len = self.next_turn().len();
        self.splice(InboxTarget::NextTurn, 0, turn_len, Vec::new())?;
        Ok(())
    }

    pub fn claim(&self, target: InboxTarget, _turn: u32) -> Result<Vec<UserMessage>, SessionError> {
        let step_len = self.next_step().len();
        let mut claimed = self.mutate(InboxTarget::NextStep, 0, step_len, Vec::new(), true)?;
        if target == InboxTarget::NextTurn {
            claimed.extend(self.mutate(InboxTarget::NextTurn, 0, 1, Vec::new(), true)?);
        }
        Ok(claimed)
    }

    pub fn splice(
        &self,
        target: InboxTarget,
        start: usize,
        delete_count: usize,
        inserted: Vec<UserMessage>,
    ) -> Result<Vec<UserMessage>, SessionError> {
        self.mutate(target, start, delete_count, inserted, true)
    }

    fn list(state: &mut State, target: InboxTarget) -> &mut Vec<UserMessage> {
        match target {
            InboxTarget::NextTurn => &mut state.next_turn,
            InboxTarget::NextStep => &mut state.next_step,
        }
    }

    fn mutate(
        &self,
        target: InboxTarget,
        start: usize,
        delete_count: usize,
        inserted: Vec<UserMessage>,
        persist: bool,
    ) -> Result<Vec<UserMessage>, SessionError> {
        let mut state = self.state.lock();
        let list = Self::list(&mut state, target);
        let actual_start = start.min(list.len());
        let actual_delete = delete_count.min(list.len().saturating_sub(actual_start));
        if persist {
            drop(state);
            self.session.append(
                SessionEventBody::InboxSpliced {
                    target,
                    start: actual_start,
                    removed_count: if actual_delete == 0 {
                        None
                    } else {
                        Some(actual_delete)
                    },
                    inserted: inserted.clone(),
                    outcome: None,
                },
                None,
                None,
            )?;
            state = self.state.lock();
        }
        let list = Self::list(&mut state, target);
        let removed: Vec<_> = list
            .drain(actual_start..actual_start + actual_delete)
            .collect();
        for (offset, message) in inserted.into_iter().enumerate() {
            list.insert(actual_start + offset, message);
        }
        Ok(removed)
    }

    fn apply(
        &self,
        target: InboxTarget,
        start: usize,
        delete_count: usize,
        inserted: Vec<UserMessage>,
        persist: bool,
    ) -> Result<Vec<UserMessage>, SessionError> {
        self.mutate(target, start, delete_count, inserted, persist)
    }

    pub fn locate(&self, message_id: &MessageId) -> Option<(InboxTarget, usize)> {
        let state = self.state.lock();
        if let Some(index) = state.next_turn.iter().position(|m| &m.id == message_id) {
            return Some((InboxTarget::NextTurn, index));
        }
        state
            .next_step
            .iter()
            .position(|m| &m.id == message_id)
            .map(|index| (InboxTarget::NextStep, index))
    }
}
