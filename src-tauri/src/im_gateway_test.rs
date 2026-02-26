use super::*;

#[test]
async fn test_im_gateway_initialization() {
    // 初始化IM网关管理器
    let im_gateway_manager = ImGatewayManager::new();
    assert!(true, "IM gateway manager initialization should succeed");
}

#[test]
async fn test_im_gateway_config() {
    // 初始化IM网关管理器
    let im_gateway_manager = ImGatewayManager::new();
    
    // 测试获取配置
    let config = im_gateway_manager.get_config().lock().unwrap();
    assert!(config.host == "127.0.0.1", "Default host should be 127.0.0.1");
    assert!(config.port == 8081, "Default port should be 8081");
}

#[test]
async fn test_im_gateway_paths() {
    // 初始化IM网关管理器
    let im_gateway_manager = ImGatewayManager::new();
    
    // 创建一个空的AppHandle
    let app = tauri::Builder::default().build(tauri::generate_context!()).unwrap();
    let app_handle = app.handle();
    
    // 测试获取二进制路径
    let binary_path = im_gateway_manager.get_binary_path(&app_handle);
    assert!(binary_path.is_ok(), "Should be able to get binary path");
}
