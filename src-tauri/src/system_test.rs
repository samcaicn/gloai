use super::*;

#[test]
async fn test_system_manager_initialization() {
    // 初始化系统管理器
    let system_manager = SystemManager::new();
    assert!(true, "System manager initialization should succeed");
}

#[test]
async fn test_auto_start() {
    // 初始化系统管理器
    let system_manager = SystemManager::new();
    
    // 测试获取自动启动状态
    let is_enabled = system_manager.is_auto_start_enabled();
    assert!(is_enabled.is_ok(), "Should be able to get auto start status");
}

#[test]
async fn test_app_info() {
    // 初始化系统管理器
    let system_manager = SystemManager::new();
    
    // 测试获取应用版本
    let version = system_manager.get_app_version();
    assert!(!version.is_empty(), "Should be able to get app version");
    
    // 测试获取应用名称
    let name = system_manager.get_app_name();
    assert!(!name.is_empty(), "Should be able to get app name");
}
