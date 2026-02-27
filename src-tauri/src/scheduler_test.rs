use super::*;
use tempfile::tempdir;
use std::path::PathBuf;

#[test]
async fn test_scheduler_initialization() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 初始化调度器
    let scheduler = Scheduler::new(db);
    assert!(true, "Scheduler initialization should succeed");
}

#[test]
async fn test_scheduler_tasks() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 初始化调度器
    let scheduler = Scheduler::new(db);
    
    // 测试创建任务
    let task_id = "test_task_1";
    scheduler.create_task(task_id, "Test Task", "* * * * *").unwrap();
    
    // 测试列出任务
    let tasks = scheduler.list_tasks().unwrap();
    assert!(!tasks.is_empty(), "Should have at least one task");
    
    // 测试删除任务
    scheduler.delete_task(task_id).unwrap();
}

#[test]
async fn test_scheduler_task_runs() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 初始化调度器
    let scheduler = Scheduler::new(db);
    
    // 创建任务
    let task_id = "test_task_1";
    scheduler.create_task(task_id, "Test Task", "* * * * *").unwrap();
    
    // 测试执行任务
    let run_id = scheduler.execute_task(task_id).unwrap();
    assert!(!run_id.is_empty(), "Should return a run ID");
    
    // 测试列出任务运行
    let runs = scheduler.list_task_runs(Some(task_id)).unwrap();
    assert!(!runs.is_empty(), "Should have at least one task run");
}

#[test]
async fn test_scheduler_start_stop() {
    // 创建临时目录
    let temp_dir = tempdir().unwrap();
    let db_path = temp_dir.path().join("test.db");
    
    // 初始化数据库
    let db = Database::new(db_path).unwrap();
    
    // 初始化调度器
    let scheduler = Scheduler::new(db);
    
    // 测试启动调度器
    scheduler.start().await.unwrap();
    assert!(scheduler.is_running(), "Scheduler should be running");
    
    // 测试停止调度器
    scheduler.stop().await.unwrap();
    assert!(!scheduler.is_running(), "Scheduler should not be running");
}
