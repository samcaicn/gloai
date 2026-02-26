use super::*;
use tempfile::tempdir;
use std::path::PathBuf;

#[test]
async fn test_database_initialization() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    assert!(true, "Database initialization should succeed");
}

#[test]
async fn test_kv_operations() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 测试设置值
    db.kv_set("test_key", "test_value").unwrap();
    
    // 测试获取值
    let value = db.kv_get("test_key").unwrap();
    assert_eq!(value, Some("test_value".to_string()));
    
    // 测试更新值
    db.kv_set("test_key", "updated_value").unwrap();
    let value = db.kv_get("test_key").unwrap();
    assert_eq!(value, Some("updated_value".to_string()));
    
    // 测试删除值
    db.kv_remove("test_key").unwrap();
    let value = db.kv_get("test_key").unwrap();
    assert_eq!(value, None);
}

#[test]
async fn test_cowork_sessions() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 测试创建会话
    let session_id = "test_session_1";
    db.cowork_create_session(session_id, "Test Session").unwrap();
    
    // 测试列出会话
    let sessions = db.cowork_list_sessions().unwrap();
    assert!(!sessions.is_empty(), "Should have at least one session");
    
    // 测试删除会话
    db.cowork_delete_session(session_id).unwrap();
    let sessions = db.cowork_list_sessions().unwrap();
    assert!(sessions.is_empty(), "Should have no sessions after deletion");
}

#[test]
async fn test_cowork_messages() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 创建会话
    let session_id = "test_session_1";
    db.cowork_create_session(session_id, "Test Session").unwrap();
    
    // 测试添加消息
    let message_id = "test_message_1";
    db.cowork_add_message(message_id, session_id, "user", "Hello world").unwrap();
    
    // 测试列出消息
    let messages = db.cowork_list_messages(session_id).unwrap();
    assert!(!messages.is_empty(), "Should have at least one message");
}

#[test]
async fn test_user_memories() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 测试创建记忆
    let memory_id = "test_memory_1";
    db.user_memory_create(memory_id, "Test memory content", 0.9, false).unwrap();
    
    // 测试列出记忆
    let memories = db.user_memories_list().unwrap();
    assert!(!memories.is_empty(), "Should have at least one memory");
}

#[test]
async fn test_scheduled_tasks() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 测试创建任务
    let task_id = "test_task_1";
    db.scheduled_task_create(task_id, "Test Task", "* * * * *").unwrap();
    
    // 测试列出任务
    let tasks = db.scheduled_tasks_list().unwrap();
    assert!(!tasks.is_empty(), "Should have at least one task");
}

#[test]
async fn test_task_runs() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 创建任务
    let task_id = "test_task_1";
    db.scheduled_task_create(task_id, "Test Task", "* * * * *").unwrap();
    
    // 测试创建任务运行
    let run_id = "test_run_1";
    db.task_run_create(run_id, task_id).unwrap();
    
    // 测试列出任务运行
    let runs = db.task_runs_list(Some(task_id)).unwrap();
    assert!(!runs.is_empty(), "Should have at least one task run");
    
    // 测试完成任务运行
    db.task_run_complete(run_id, "completed", Some("Task output"), None).unwrap();
    let runs = db.task_runs_list(Some(task_id)).unwrap();
    assert!(!runs.is_empty(), "Should have at least one completed task run");
}
