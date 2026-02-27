use tauri::AppHandle;
use tauri_plugin_dialog::DialogExt;

#[derive(serde::Serialize)]
pub struct DialogResult {
    pub success: bool,
    pub path: Option<String>,
}

#[tauri::command]
pub async fn dialog_select_directory(app: AppHandle) -> Result<DialogResult, String> {
    println!("[dialog_select_directory] Called");

    // 在 macOS 上，我们需要在主线程上运行对话框
    let path = tokio::task::spawn_blocking(move || {
        println!("[dialog_select_directory] Opening folder picker in blocking thread...");

        // 使用 std::sync::mpsc 来同步等待结果
        let (tx, rx) = std::sync::mpsc::channel();

        app.dialog().file().pick_folder(move |path| {
            println!(
                "[dialog_select_directory] Folder picked callback: {:?}",
                path
            );
            let _ = tx.send(path);
        });

        // 等待结果，设置超时
        match rx.recv_timeout(std::time::Duration::from_secs(60)) {
            Ok(path) => {
                println!("[dialog_select_directory] Got path: {:?}", path);
                path
            }
            Err(e) => {
                println!("[dialog_select_directory] Timeout or error: {:?}", e);
                None
            }
        }
    })
    .await
    .map_err(|e| e.to_string())?;

    Ok(DialogResult {
        success: path.is_some(),
        path: path.map(|p| p.to_string()),
    })
}

#[tauri::command]
pub async fn dialog_select_file(
    app: AppHandle,
    title: Option<String>,
    filters: Option<Vec<serde_json::Value>>,
) -> Result<DialogResult, String> {
    println!("[dialog_select_file] Called with title: {:?}", title);

    let path = tokio::task::spawn_blocking(move || {
        println!("[dialog_select_file] Opening file picker in blocking thread...");

        let mut file_dialog = app.dialog().file();

        if let Some(title) = title {
            file_dialog = file_dialog.set_title(&title);
        }

        if let Some(filters) = filters {
            for filter in filters {
                if let (Some(name), Some(extensions)) = (
                    filter.get("name").and_then(|v| v.as_str()),
                    filter.get("extensions").and_then(|v| v.as_array()),
                ) {
                    let exts: Vec<&str> = extensions.iter().filter_map(|v| v.as_str()).collect();
                    if !exts.is_empty() {
                        file_dialog = file_dialog.add_filter(name, &exts);
                    }
                }
            }
        }

        let (tx, rx) = std::sync::mpsc::channel();

        file_dialog.pick_file(move |path| {
            println!("[dialog_select_file] File picked callback: {:?}", path);
            let _ = tx.send(path);
        });

        match rx.recv_timeout(std::time::Duration::from_secs(60)) {
            Ok(path) => {
                println!("[dialog_select_file] Got path: {:?}", path);
                path
            }
            Err(e) => {
                println!("[dialog_select_file] Timeout or error: {:?}", e);
                None
            }
        }
    })
    .await
    .map_err(|e| e.to_string())?;

    Ok(DialogResult {
        success: path.is_some(),
        path: path.map(|p| p.to_string()),
    })
}
