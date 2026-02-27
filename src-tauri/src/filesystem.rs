use anyhow::Result;
use std::fs;
use std::path::PathBuf;

pub struct FileSystem {
    base_dir: PathBuf,
}

impl FileSystem {
    pub fn new(base_dir: PathBuf) -> Self {
        Self { base_dir }
    }

    pub async fn read_file(&self, path: &str) -> Result<String> {
        let full_path = self.base_dir.join(path);
        let content = fs::read_to_string(full_path)?;
        Ok(content)
    }

    pub async fn write_file(&self, path: &str, content: &str) -> Result<()> {
        let full_path = self.base_dir.join(path);
        if let Some(parent) = full_path.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(full_path, content)?;
        Ok(())
    }

    pub async fn delete_file(&self, path: &str) -> Result<()> {
        let full_path = self.base_dir.join(path);
        if full_path.is_file() {
            fs::remove_file(full_path)?;
        } else if full_path.is_dir() {
            fs::remove_dir_all(full_path)?;
        }
        Ok(())
    }

    pub async fn file_exists(&self, path: &str) -> bool {
        let full_path = self.base_dir.join(path);
        full_path.exists()
    }

    pub async fn create_dir(&self, path: &str) -> Result<()> {
        let full_path = self.base_dir.join(path);
        fs::create_dir_all(full_path)?;
        Ok(())
    }

    pub async fn list_dir(&self, path: &str) -> Result<Vec<String>> {
        let full_path = self.base_dir.join(path);
        let mut entries = Vec::new();

        if full_path.is_dir() {
            for entry in fs::read_dir(full_path)? {
                let entry = entry?;
                let file_name = entry.file_name();
                entries.push(file_name.to_string_lossy().into());
            }
        }

        Ok(entries)
    }

    pub fn get_base_dir(&self) -> &PathBuf {
        &self.base_dir
    }
}
