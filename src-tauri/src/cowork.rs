use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::Mutex;
use crate::database::Database;
use crate::goclaw::GoClawManager;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoworkSession {
    pub id: String,
    pub title: String,
    pub status: String,
    pub pinned: bool,
    pub cwd: Option<String>,
    pub system_prompt: Option<String>,
    pub execution_mode: Option<String>,
    pub active_skill_ids: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoworkMessage {
    pub id: String,
    pub session_id: String,
    pub r#type: String,
    pub content: String,
    pub timestamp: i64,
    pub metadata: Option<String>,
    pub sequence: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserMemory {
    pub id: String,
    pub text: String,
    pub confidence: f64,
    pub is_explicit: bool,
    pub status: String,
    pub created_at: i64,
    pub updated_at: i64,
    pub last_used_at: Option<i64>,
}

pub struct CoworkManager {
    database: Arc<Mutex<Database>>,
    goclaw_manager: Option<Arc<Mutex<GoClawManager>>>,
}

impl CoworkManager {
    pub fn new(database: Arc<Mutex<Database>>) -> Self {
        CoworkManager { database, goclaw_manager: None }
    }
    
    pub fn set_goclaw_manager(&mut self, goclaw_manager: Arc<Mutex<GoClawManager>>) {
        self.goclaw_manager = Some(goclaw_manager);
    }
    
    pub async fn send_message(&self, session_id: String, content: String) -> anyhow::Result<CoworkMessage> {
        let _user_msg = self.add_message(session_id.clone(), "user".to_string(), content.clone())?;
        
        if let Some(goclaw_manager) = &self.goclaw_manager {
            let goclaw = goclaw_manager.lock().await;
            let params = serde_json::json!({ "content": content });
            match goclaw.request("chat".to_string(), params).await {
                Ok(response) => {
                    let response_text = response.as_str().unwrap_or("消息已接收").to_string();
                    let assistant_msg = self.add_message(session_id, "assistant".to_string(), response_text)?;
                    return Ok(assistant_msg);
                }
                Err(e) => {
                    let error_msg = self.add_message(session_id.clone(), "assistant".to_string(), format!("请求失败: {}", e))?;
                    return Err(anyhow::anyhow!("AI request failed: {}", e));
                }
            }
        }
        
        let fallback_msg = self.add_message(session_id, "assistant".to_string(), "消息已接收".to_string())?;
        Ok(fallback_msg)
    }

    pub async fn send_message_with_error(&self, session_id: String, content: String) -> anyhow::Result<(CoworkMessage, Option<String>)> {
        let _user_msg = self.add_message(session_id.clone(), "user".to_string(), content.clone())?;
        
        if let Some(goclaw_manager) = &self.goclaw_manager {
            let goclaw = goclaw_manager.lock().await;
            let params = serde_json::json!({ "content": content });
            match goclaw.request("chat".to_string(), params).await {
                Ok(response) => {
                    let response_text = response.as_str().unwrap_or("消息已接收").to_string();
                    let assistant_msg = self.add_message(session_id, "assistant".to_string(), response_text)?;
                    return Ok((assistant_msg, None));
                }
                Err(e) => {
                    let error_msg = format!("AI 请求失败: {}", e);
                    let assistant_msg = self.add_message(session_id, "assistant".to_string(), error_msg.clone())?;
                    return Ok((assistant_msg, Some(error_msg)));
                }
            }
        }
        
        let fallback_msg = self.add_message(session_id, "assistant".to_string(), "消息已接收".to_string())?;
        Ok((fallback_msg, Some("GoClaw manager not available".to_string())))
    }

    pub fn list_sessions(&self) -> anyhow::Result<Vec<CoworkSession>> {
        let db = self.database.blocking_lock();
        let sessions_json = db.cowork_list_sessions()?;
        let sessions: Vec<CoworkSession> = sessions_json
            .into_iter()
            .filter_map(|s| serde_json::from_value(s).ok())
            .collect();
        Ok(sessions)
    }

    pub fn create_session(
        &self, 
        title: String,
        cwd: Option<String>,
        system_prompt: Option<String>,
        execution_mode: Option<String>
    ) -> anyhow::Result<CoworkSession> {
        let id = format!("session_{}", uuid::Uuid::new_v4());
        let now = chrono::Utc::now().timestamp_millis();
        let db = self.database.blocking_lock();
        
        db.cowork_create_session(
            &id, 
            &title, 
            cwd.as_deref(), 
            system_prompt.as_deref(), 
            execution_mode.as_deref()
        )?;
        
        Ok(CoworkSession {
            id: id.clone(),
            title,
            status: "idle".to_string(),
            pinned: false,
            cwd,
            system_prompt,
            execution_mode,
            active_skill_ids: None,
            created_at: now,
            updated_at: now,
        })
    }

    pub fn delete_session(&self, id: String) -> anyhow::Result<()> {
        let db = self.database.blocking_lock();
        db.cowork_delete_session(&id)?;
        Ok(())
    }

    pub fn update_session(
        &self,
        id: String,
        title: Option<String>,
        pinned: Option<bool>,
        status: Option<String>,
        cwd: Option<String>,
        system_prompt: Option<String>,
        execution_mode: Option<String>,
    ) -> anyhow::Result<()> {
        let db = self.database.blocking_lock();
        db.cowork_update_session(
            &id,
            title.as_deref(),
            pinned,
            status.as_deref(),
            cwd.as_deref(),
            system_prompt.as_deref(),
            execution_mode.as_deref(),
        )?;
        Ok(())
    }
    
    pub fn update_message(
        &self,
        session_id: String,
        id: String,
        content: Option<String>,
        metadata: Option<String>,
    ) -> anyhow::Result<()> {
        let db = self.database.blocking_lock();
        db.cowork_update_message(
            &id,
            &session_id,
            content.as_deref(),
            metadata.as_deref(),
        )?;
        Ok(())
    }
    
    pub fn get_config(&self) -> anyhow::Result<serde_json::Value> {
        let db = self.database.blocking_lock();
        let configs = db.cowork_config_get_all()?;
        let mut result = serde_json::Map::new();
        for config in configs {
            if let (Some(key), Some(value)) = (config.get("key"), config.get("value")) {
                if let (Some(k), Some(v)) = (key.as_str(), value.as_str()) {
                    result.insert(k.to_string(), serde_json::Value::String(v.to_string()));
                }
            }
        }
        Ok(serde_json::Value::Object(result))
    }
    
    pub fn set_config(&self, key: String, value: String) -> anyhow::Result<()> {
        let db = self.database.blocking_lock();
        db.cowork_config_set(&key, &value)?;
        Ok(())
    }
    
    pub fn update_user_memory(
        &self,
        id: String,
        text: Option<String>,
        confidence: Option<f64>,
        status: Option<String>,
        is_explicit: Option<bool>,
    ) -> anyhow::Result<()> {
        let db = self.database.blocking_lock();
        db.user_memory_update(&id, text.as_deref(), confidence, status.as_deref(), is_explicit)?;
        Ok(())
    }
    
    pub fn delete_user_memory(&self, id: String) -> anyhow::Result<bool> {
        let db = self.database.blocking_lock();
        Ok(db.user_memory_delete(&id)?)
    }
    
    pub fn get_user_memory_stats(&self) -> anyhow::Result<serde_json::Value> {
        let db = self.database.blocking_lock();
        Ok(db.user_memory_get_stats()?)
    }

    pub fn list_messages(&self, session_id: String) -> anyhow::Result<Vec<CoworkMessage>> {
        let db = self.database.blocking_lock();
        let messages_json = db.cowork_list_messages(&session_id)?;
        let messages: Vec<CoworkMessage> = messages_json
            .into_iter()
            .filter_map(|m| serde_json::from_value(m).ok())
            .collect();
        Ok(messages)
    }

    pub fn add_message(
        &self,
        session_id: String,
        msg_type: String,
        content: String,
    ) -> anyhow::Result<CoworkMessage> {
        let id = format!("msg_{}", uuid::Uuid::new_v4());
        let now = chrono::Utc::now().timestamp_millis();
        let db = self.database.blocking_lock();
        
        db.cowork_add_message(&id, &session_id, &msg_type, &content)?;
        
        Ok(CoworkMessage {
            id: id.clone(),
            session_id,
            r#type: msg_type,
            content,
            timestamp: now,
            metadata: None,
            sequence: None,
        })
    }

    pub fn list_user_memories(&self) -> anyhow::Result<Vec<UserMemory>> {
        let db = self.database.blocking_lock();
        let memories_json = db.user_memories_list()?;
        let memories: Vec<UserMemory> = memories_json
            .into_iter()
            .filter_map(|m| serde_json::from_value(m).ok())
            .collect();
        Ok(memories)
    }

    pub fn create_user_memory(
        &self,
        text: String,
        confidence: f64,
        is_explicit: bool,
    ) -> anyhow::Result<UserMemory> {
        let id = format!("memory_{}", uuid::Uuid::new_v4());
        let now = chrono::Utc::now().timestamp_millis();
        let db = self.database.blocking_lock();
        
        db.user_memory_create(&id, &text, confidence, is_explicit)?;
        
        Ok(UserMemory {
            id: id.clone(),
            text,
            confidence,
            is_explicit,
            status: "created".to_string(),
            created_at: now,
            updated_at: now,
            last_used_at: None,
        })
    }
}
