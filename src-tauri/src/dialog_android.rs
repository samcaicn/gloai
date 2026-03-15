use serde::Serialize;
use tauri::AppHandle;

#[derive(Serialize)]
pub struct DialogResult {
    pub success: bool,
    pub path: Option<String>,
}

#[tauri::command]
pub async fn dialog_select_directory(_app: AppHandle) -> Result<DialogResult, String> {
    Ok(DialogResult {
        success: false,
        path: None,
    })
}

#[tauri::command]
pub async fn dialog_select_file(
    _app: AppHandle,
    _title: Option<String>,
    _filters: Option<Vec<serde_json::Value>>,
) -> Result<DialogResult, String> {
    Ok(DialogResult {
        success: false,
        path: None,
    })
}
