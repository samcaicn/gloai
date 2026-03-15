#[cfg(not(target_os = "android"))]
pub fn open_path(path: &std::path::Path) -> Result<(), String> {
    open::that(path).map_err(|e| format!("Failed to open: {}", e))
}

#[cfg(not(target_os = "android"))]
pub fn open_url(url: &str) -> Result<(), String> {
    open::that(url).map_err(|e| format!("Failed to open URL: {}", e))
}

#[cfg(target_os = "android")]
pub fn open_path(_path: &std::path::Path) -> Result<(), String> {
    Ok(())
}

#[cfg(target_os = "android")]
pub fn open_url(_url: &str) -> Result<(), String> {
    Ok(())
}
