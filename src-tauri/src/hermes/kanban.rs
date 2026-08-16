
//
// Kanban board data model. The original TypeScript module exposed a
// `KanbanBoard` with `columns: KanbanColumn[]` and operations to add
// / move / archive cards. The Rust port uses a `RwLock` for shared
// access and supports serialization for persistence.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct KanbanCard {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub assignee: Option<String>,
    #[serde(default)]
    pub due_date: Option<String>,
    #[serde(default)]
    pub order: i32,
    #[serde(default)]
    pub archived: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct KanbanColumn {
    pub id: String,
    pub name: String,
    pub order: i32,
    pub cards: Vec<KanbanCard>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct KanbanBoard {
    pub id: String,
    pub name: String,
    pub columns: Vec<KanbanColumn>,
}

pub type SharedKanban = Arc<RwLock<KanbanBoard>>;

pub fn new_board(id: impl Into<String>, name: impl Into<String>) -> KanbanBoard {
    KanbanBoard { id: id.into(), name: name.into(), columns: Vec::new() }
}

pub fn ensure_column(board: &mut KanbanBoard, id: &str, name: &str) {
    if !board.columns.iter().any(|c| c.id == id) {
        let order = board.columns.len() as i32;
        board.columns.push(KanbanColumn { id: id.to_string(), name: name.to_string(), order, cards: Vec::new() });
    }
}

pub fn add_card(board: &mut KanbanBoard, column_id: &str, card: KanbanCard) -> Result<(), String> {
    let col = board.columns.iter_mut().find(|c| c.id == column_id).ok_or_else(|| format!("column not found: {}", column_id))?;
    let order = col.cards.len() as i32;
    let mut card = card;
    card.order = order;
    col.cards.push(card);
    Ok(())
}

pub fn move_card(board: &mut KanbanBoard, card_id: &str, from: &str, to: &str, target_index: i32) -> Result<(), String> {
    let (idx, mut card) = {
        let from_col = board.columns.iter_mut().find(|c| c.id == from).ok_or_else(|| format!("from column not found: {}", from))?;
        let idx = from_col.cards.iter().position(|c| c.id == card_id).ok_or_else(|| "card not found".to_string())?;
        let card = from_col.cards.remove(idx);
        (idx, card)
    };
    let _ = idx;
    let to_col = board.columns.iter_mut().find(|c| c.id == to).ok_or_else(|| format!("to column not found: {}", to))?;
    let mut insert_at = target_index.max(0).min(to_col.cards.len() as i32) as usize;
    // 同列内移动:之前 remove 已经把 `idx` 拿掉,如果 target_index
    // > 原 idx,新位置在 `to_col.cards` 里实际上比用户指定的
    // target_index 少一个(因为 cards 整体左移了一格)。补回 1。
    if from == to && insert_at > idx {
        insert_at -= 1;
    }
    card.order = insert_at as i32;
    to_col.cards.insert(insert_at, card);
    Ok(())
}

pub fn archive_card(board: &mut KanbanBoard, card_id: &str) -> Result<(), String> {
    for col in board.columns.iter_mut() {
        if let Some(c) = col.cards.iter_mut().find(|c| c.id == card_id) {
            c.archived = true;
            return Ok(());
        }
    }
    Err("card not found".into())
}

pub fn snapshot_counts(board: &KanbanBoard) -> HashMap<String, usize> {
    let mut out = HashMap::new();
    for c in &board.columns {
        out.insert(c.id.clone(), c.cards.iter().filter(|c| !c.archived).count());
    }
    out
}
