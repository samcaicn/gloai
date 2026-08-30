// DSH Skill Platform - CLI tool
//
// Subcommands:
//   list      - List installed skills (from registry)
//   embedded  - List compile-time embedded skills
//   install   - Install a skill from YAML file
//   uninstall - Remove a skill from registry
//   execute   - Execute a skill by name
//   info      - Show skill details
//   validate  - Validate a skill YAML file
//   memory    - Memory management (list/search)
//   init-db   - Initialize local database

use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "dsh", version, about = "DSH Skill Platform CLI")]
struct Cli {
    #[command(subcommand)]
    command: Commands,

    /// Database path (defaults to ~/.dsh/dsh.db)
    #[arg(long, global = true)]
    db_path: Option<String>,
}

#[derive(Subcommand)]
enum Commands {
    /// List installed skills (from in-memory registry + filesystem)
    List,

    /// List compile-time embedded skills
    Embedded,

    /// Install a skill from a YAML file
    Install {
        /// Path to SKILL.md / YAML file
        #[arg(short, long)]
        file: String,
    },

    /// Remove a skill from registry
    Uninstall {
        /// Skill name
        name: String,
    },

    /// Execute a skill by name
    Execute {
        /// Skill name
        name: String,
        /// Scene identifier
        #[arg(short, long, default_value = "cli")]
        scene: String,
    },

    /// Show skill details
    Info {
        /// Skill name
        name: String,
    },

    /// Validate a skill YAML file (no installation)
    Validate {
        /// Path to YAML file
        file: String,
    },

    /// Memory management
    Memory {
        #[command(subcommand)]
        action: MemoryAction,
    },

    /// Initialize the local database
    InitDb,
}

#[derive(Subcommand)]
enum MemoryAction {
    /// List memories
    List {
        /// Limit results
        #[arg(short, long, default_value = "20")]
        limit: usize,
    },
    /// Search memories
    Search {
        /// Search query
        query: String,
    },
    /// Stats
    Stats,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    env_logger::init();
    let cli = Cli::parse();

    // Resolve db path
    let db_path = cli.db_path.unwrap_or_else(|| {
        if let Some(home) = std::env::var_os("USERPROFILE")
            .or_else(|| std::env::var_os("HOME"))
        {
            std::path::PathBuf::from(home)
                .join(".dsh")
                .join("dsh.db")
                .to_string_lossy()
                .to_string()
        } else {
            "dsh.db".to_string()
        }
    });

    // Ensure parent directory exists
    if let Some(parent) = std::path::Path::new(&db_path).parent() {
        std::fs::create_dir_all(parent).ok();
    }

    let storage = std::sync::Arc::new(dsh_core::storage::Storage::open(&db_path)?);

    match cli.command {
        Commands::InitDb => {
            println!("✓ Database initialized at: {}", db_path);
        }

        Commands::List => {
            // Load filesystem skills
            let loader = dsh_core::skill::loader::SkillLoader::new();
            let fs_skills = loader.load_all();
            if fs_skills.is_empty() {
                println!("No filesystem skills found in ~/.dsh/skills/");
            } else {
                println!("Filesystem Skills ({})", fs_skills.len());
                println!("{:<30} {:<15} {}", "NAME", "VERSION", "PATH");
                println!("{}", "-".repeat(70));
                for s in &fs_skills {
                    println!(
                        "{:<30} {:<15} {}",
                        s.manifest.name,
                        s.manifest.version.as_deref().unwrap_or("—"),
                        s.path.display()
                    );
                }
            }

            // Load memory count
            let mem_count = storage.conn().query_row(
                "SELECT COUNT(*) FROM hermes_memories",
                [],
                |row| row.get::<_, i64>(0),
            ).unwrap_or(0);
            println!("\n Memories: {}", mem_count);
        }

        Commands::Embedded => {
            let skills = dsh_core::skill::embedded::get_embedded_skills();
            println!("Embedded Skills ({})", skills.len());
            println!("{:<30} {:<10} {:<12} {}", "ID", "VERSION", "CATEGORY", "DESCRIPTION");
            println!("{}", "-".repeat(85));
            for s in &skills {
                println!(
                    "{:<30} {:<10} {:<12} {}",
                    s.id, s.version, s.category, s.description
                );
            }
        }

        Commands::Install { file } => {
            let content = std::fs::read_to_string(&file)?;
            // Validate
            dsh_core::skill::compiler::validate(&content)?;
            // Parse to get name
            let manifest = dsh_core::skill::manifest::SkillManifest::from_yaml(&content)
                .map_err(|e| anyhow::anyhow!("YAML parse error: {}", e))?;
            let name = manifest.name.clone();

            // Install to filesystem
            let loader = dsh_core::skill::loader::SkillLoader::new();
            let path = loader.install(&name, &content)?;
            println!("✓ Skill '{}' installed to: {}", name, path.display());
        }

        Commands::Uninstall { name } => {
            let loader = dsh_core::skill::loader::SkillLoader::new();
            if loader.uninstall(&name)? {
                println!("✓ Skill '{}' removed from filesystem", name);
            } else {
                println!("✗ Skill '{}' not found", name);
            }
        }

        Commands::Execute { name, scene } => {
            // Load skill from filesystem
            let loader = dsh_core::skill::loader::SkillLoader::new();
            let skills = loader.load_all();
            let skill = skills.iter().find(|s| s.manifest.name == name);
            let yaml = match skill {
                Some(s) => s.yaml.clone(),
                None => {
                    // Try embedded
                    let embedded = dsh_core::skill::embedded::get_embedded_skills();
                    let emb = embedded.iter().find(|s| s.name == name || s.id == name);
                    match emb {
                        Some(e) => e.yaml.clone(),
                        None => return Err(anyhow::anyhow!("Skill '{}' not found", name)),
                    }
                }
            };

            let manifest = dsh_core::skill::manifest::SkillManifest::from_yaml(&yaml)
                .map_err(|e| anyhow::anyhow!("Parse error: {}", e))?;

            // Create executor with permissive sandbox (CLI is trusted)
            let executor = dsh_core::skill::executor::SkillExecutor::new(storage.clone())
                .with_sandbox(dsh_core::skill::sandbox::Sandbox::permissive());

            println!("Executing '{}' in scene '{}'...", name, scene);
            match executor.execute(&scene, &manifest).await {
                Ok(result) => {
                    println!("\n✓ Execution {} in {}ms", result.status, result.total_duration_ms);
                    for (i, step) in result.steps.iter().enumerate() {
                        let icon = if step.success { "✓" } else { "✗" };
                        println!("  {} Step {}: {} → {} ({}ms)",
                            icon, i + 1, step.action, step.target, step.duration_ms);
                        if let Some(val) = &step.value {
                            // Truncate long output
                            let val_display = if val.len() > 200 {
                                format!("{}... ({} chars total)", &val[..200], val.len())
                            } else {
                                val.clone()
                            };
                            println!("    Output: {}", val_display);
                        }
                        if let Some(err) = &step.error {
                            println!("    Error: {}", err);
                        }
                    }
                }
                Err(e) => {
                    println!("✗ Execution failed: {}", e);
                    std::process::exit(1);
                }
            }
        }

        Commands::Info { name } => {
            // Search embedded first
            let embedded = dsh_core::skill::embedded::get_embedded_skills();
            if let Some(s) = embedded.iter().find(|s| s.name == name || s.id == name) {
                println!("Embedded Skill: {}", s.name);
                println!("  ID:          {}", s.id);
                println!("  Version:     {}", s.version);
                println!("  Category:    {}", s.category);
                println!("  Tags:        {}", s.tags.join(", "));
                println!("  Description: {}", s.description);
                return Ok(());
            }

            // Search filesystem
            let loader = dsh_core::skill::loader::SkillLoader::new();
            let skills = loader.load_all();
            if let Some(s) = skills.iter().find(|s| s.manifest.name == name) {
                println!("Filesystem Skill: {}", s.manifest.name);
                println!("  Path: {}", s.path.display());
                let m = &s.manifest;
                if let Some(v) = &m.version { println!("  Version:     {}", v); }
                println!("  Category:    {}", m.category);
                if !m.tags.is_empty() { println!("  Tags:        {}", m.tags.join(", ")); }
                if let Some(d) = &m.description { println!("  Description: {}", d); }
                println!("  Permissions: {:?}", m.effective_permissions());
                println!("  Steps:       {}", m.steps.len());
                return Ok(());
            }

            println!("Skill '{}' not found", name);
        }

        Commands::Validate { file } => {
            let content = std::fs::read_to_string(&file)?;
            dsh_core::skill::compiler::validate(&content)
                .map_err(|e| anyhow::anyhow!("Validation error: {}", e))?;
            println!("✓ {} is a valid skill definition", file);
        }

        Commands::Memory { action } => match action {
            MemoryAction::List { limit } => {
                let conn = storage.conn();
                let mut stmt = conn.prepare(
                    "SELECT id, summary, importance, confidence, source, created_at FROM hermes_memories ORDER BY created_at DESC LIMIT ?",
                )?;
                let rows = stmt.query_map([limit], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, f64>(3)?,
                        row.get::<_, String>(4)?,
                        row.get::<_, String>(5)?,
                    ))
                })?;
                println!(" Memories (latest {})", limit);
                println!("{:<15} {:<8} {:<8} {:<10} {}", "SUMMARY", "IMPORT", "CONF", "SOURCE", "DATE");
                println!("{}", "-".repeat(70));
                for row in rows {
                    let (id, summary, importance, confidence, source, created_at) = row?;
                    let _ = id;
                    println!(
                        "{:<15} {:<8} {:<8.2} {:<10} {}",
                        if summary.len() > 14 { &summary[..14] } else { &summary },
                        importance,
                        confidence,
                        source,
                        created_at
                    );
                }
            }
            MemoryAction::Search { query } => {
                let conn = storage.conn();
                let pattern = format!("%{}%", query);
                let mut stmt = conn.prepare(
                    "SELECT summary, importance, confidence FROM hermes_memories WHERE summary LIKE ? OR content LIKE ?",
                )?;
                let rows = stmt.query_map([&pattern, &pattern], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, f64>(2)?,
                    ))
                })?;
                println!(" Search results for '{}':", query);
                for row in rows {
                    let (summary, importance, confidence) = row?;
                    println!("  - {} ({} , {:.0}% confidence)", summary, importance, confidence * 100.0);
                }
            }
            MemoryAction::Stats => {
                let conn = storage.conn();
                let total: i64 = conn.query_row("SELECT COUNT(*) FROM hermes_memories", [], |r| r.get(0)).unwrap_or(0);
                let hot: i64 = conn.query_row("SELECT COUNT(*) FROM hermes_memories WHERE importance = 'hot'", [], |r| r.get(0)).unwrap_or(0);
                let warm: i64 = conn.query_row("SELECT COUNT(*) FROM hermes_memories WHERE importance = 'warm'", [], |r| r.get(0)).unwrap_or(0);
                let cold: i64 = conn.query_row("SELECT COUNT(*) FROM hermes_memories WHERE importance = 'cold'", [], |r| r.get(0)).unwrap_or(0);
                println!(" Memory Statistics");
                println!("  Total: {}", total);
                println!("  🔥 Hot:  {}", hot);
                println!("  ☀️ Warm: {}", warm);
                println!("  ❄️ Cold: {}", cold);
            }
        },
    }

    Ok(())
}

