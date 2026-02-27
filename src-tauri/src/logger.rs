use chrono::Local;
use directories_next::ProjectDirs;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

impl LogLevel {
    fn as_str(&self) -> &'static str {
        match self {
            LogLevel::Debug => "DEBUG",
            LogLevel::Info => "INFO",
            LogLevel::Warn => "WARN",
            LogLevel::Error => "ERROR",
        }
    }
}

pub struct Logger {
    log_file: Arc<Mutex<Option<File>>>,
    log_path: PathBuf,
    max_size: u64,
}

impl Logger {
    pub fn new() -> anyhow::Result<Self> {
        let proj_dirs = ProjectDirs::from("com", "ggai", "ggai")
            .ok_or_else(|| anyhow::anyhow!("Failed to get project directories"))?;

        let log_dir = proj_dirs.data_dir().join("logs");
        std::fs::create_dir_all(&log_dir)?;

        let log_path = log_dir.join("main.log");

        Ok(Logger {
            log_file: Arc::new(Mutex::new(None)),
            log_path,
            max_size: 10 * 1024 * 1024,
        })
    }

    fn ensure_log_file(&self) -> anyhow::Result<()> {
        let mut log_file_guard = self.log_file.lock().unwrap();

        if log_file_guard.is_none() {
            let file = OpenOptions::new()
                .create(true)
                .append(true)
                .open(&self.log_path)?;
            *log_file_guard = Some(file);
        }

        Ok(())
    }

    fn rotate_if_needed(&self) -> anyhow::Result<()> {
        if let Ok(metadata) = std::fs::metadata(&self.log_path) {
            if metadata.len() > self.max_size {
                let old_log_path = self.log_path.with_file_name("main.old.log");
                if old_log_path.exists() {
                    std::fs::remove_file(&old_log_path)?;
                }
                std::fs::rename(&self.log_path, &old_log_path)?;

                let mut log_file_guard = self.log_file.lock().unwrap();
                *log_file_guard = None;
            }
        }
        Ok(())
    }

    pub fn log(&self, level: LogLevel, message: &str) {
        let _ = self.rotate_if_needed();
        let _ = self.ensure_log_file();

        let now = Local::now();
        let timestamp = now.format("[%Y-%m-%d %H:%M:%S%.3f]");

        let log_line = format!("{} [{}] {}\n", timestamp, level.as_str(), message);

        if let Ok(mut log_file_guard) = self.log_file.lock() {
            if let Some(file) = log_file_guard.as_mut() {
                let _ = file.write_all(log_line.as_bytes());
                let _ = file.flush();
            }
        }

        match level {
            LogLevel::Debug => println!("[DEBUG] {}", message),
            LogLevel::Info => println!("[INFO] {}", message),
            LogLevel::Warn => eprintln!("[WARN] {}", message),
            LogLevel::Error => eprintln!("[ERROR] {}", message),
        }
    }

    pub fn debug(&self, message: &str) {
        self.log(LogLevel::Debug, message);
    }

    pub fn info(&self, message: &str) {
        self.log(LogLevel::Info, message);
    }

    pub fn warn(&self, message: &str) {
        self.log(LogLevel::Warn, message);
    }

    pub fn error(&self, message: &str) {
        self.log(LogLevel::Error, message);
    }

    pub fn get_log_file_path(&self) -> String {
        self.log_path.to_string_lossy().into_owned()
    }

    pub fn get_logs_dir(&self) -> anyhow::Result<PathBuf> {
        let proj_dirs = ProjectDirs::from("com", "ggai", "ggai")
            .ok_or_else(|| anyhow::anyhow!("Failed to get project directories"))?;
        Ok(proj_dirs.data_dir().join("logs"))
    }
}

impl Default for Logger {
    fn default() -> Self {
        Self::new().expect("Failed to create logger")
    }
}
