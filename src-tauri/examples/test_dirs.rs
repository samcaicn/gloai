fn main() {
    if let Some(proj_dirs) = directories_next::ProjectDirs::from("com", "ggai", "app") {
        println!("Data dir: {:?}", proj_dirs.data_dir());
        println!("Config dir: {:?}", proj_dirs.config_dir());
        
        // 尝试创建目录
        let data_dir = proj_dirs.data_dir();
        let skills_dir = data_dir.join("skills");
        
        println!("Skills dir: {:?}", skills_dir);
        
        match std::fs::create_dir_all(&skills_dir) {
            Ok(_) => println!("Skills directory created successfully"),
            Err(e) => println!("Failed to create skills directory: {}", e),
        }
    } else {
        println!("Failed to get project directories");
    }
}
