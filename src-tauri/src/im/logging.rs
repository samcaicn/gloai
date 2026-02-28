use log::{debug, error, info, trace, warn};
use std::sync::Mutex;
use std::time::SystemTime;

// 日志级别
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd)]
pub enum LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
}

// 日志配置
#[derive(Debug, Clone)]
pub struct LogConfig {
    pub enabled: bool,
    pub level: LogLevel,
    pub file_path: Option<String>, // 日志文件路径
}

impl Default for LogConfig {
    fn default() -> Self {
        LogConfig {
            enabled: true,
            level: LogLevel::Info,
            file_path: None,
        }
    }
}

// 日志记录器
pub struct Logger {
    config: Mutex<LogConfig>,
}

impl Logger {
    pub fn new(config: LogConfig) -> Self {
        Self {
            config: Mutex::new(config),
        }
    }

    pub fn new_default() -> Self {
        Self::new(LogConfig::default())
    }

    pub fn set_config(&self, config: LogConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_config(&self) -> LogConfig {
        self.config.lock().unwrap().clone()
    }

    // 记录跟踪级别的日志
    pub fn trace(&self, module: &str, message: &str) {
        let config = self.config.lock().unwrap();
        if !config.enabled || config.level > LogLevel::Trace {
            return;
        }

        let timestamp = self.get_timestamp();
        let log_message = format!("[{}] [TRACE] [{}] {}", timestamp, module, message);
        trace!("{}", log_message);
        self.write_to_file(&log_message);
    }

    // 记录调试级别的日志
    pub fn debug(&self, module: &str, message: &str) {
        let config = self.config.lock().unwrap();
        if !config.enabled || config.level > LogLevel::Debug {
            return;
        }

        let timestamp = self.get_timestamp();
        let log_message = format!("[{}] [DEBUG] [{}] {}", timestamp, module, message);
        debug!("{}", log_message);
        self.write_to_file(&log_message);
    }

    // 记录信息级别的日志
    pub fn info(&self, module: &str, message: &str) {
        let config = self.config.lock().unwrap();
        if !config.enabled || config.level > LogLevel::Info {
            return;
        }

        let timestamp = self.get_timestamp();
        let log_message = format!("[{}] [INFO] [{}] {}", timestamp, module, message);
        info!("{}", log_message);
        self.write_to_file(&log_message);
    }

    // 记录警告级别的日志
    pub fn warn(&self, module: &str, message: &str) {
        let config = self.config.lock().unwrap();
        if !config.enabled || config.level > LogLevel::Warn {
            return;
        }

        let timestamp = self.get_timestamp();
        let log_message = format!("[{}] [WARN] [{}] {}", timestamp, module, message);
        warn!("{}", log_message);
        self.write_to_file(&log_message);
    }

    // 记录错误级别的日志
    pub fn error(&self, module: &str, message: &str) {
        let config = self.config.lock().unwrap();
        if !config.enabled || config.level > LogLevel::Error {
            return;
        }

        let timestamp = self.get_timestamp();
        let log_message = format!("[{}] [ERROR] [{}] {}", timestamp, module, message);
        error!("{}", log_message);
        self.write_to_file(&log_message);
    }

    // 获取当前时间戳
    fn get_timestamp(&self) -> String {
        let now = SystemTime::now();
        let datetime = now
            .duration_since(SystemTime::UNIX_EPOCH)
            .expect("Time went backwards");
        let seconds = datetime.as_secs();
        let nanos = datetime.subsec_nanos();
        format!("{}.{:09}", seconds, nanos)
    }

    // 写入日志到文件
    fn write_to_file(&self, message: &str) {
        let config = self.config.lock().unwrap();
        if let Some(_file_path) = &config.file_path {
            // 这里应该实现文件写入逻辑
            // 为了简化，我们暂时只打印到控制台
            println!("[FILE LOG] {}", message);
        }
    }
}
