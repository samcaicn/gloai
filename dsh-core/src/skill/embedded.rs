// Embedded skills — compile-time built-in skills via include_str!.
//
// Inspired by safeopcapp's skills_embedded.rs.
// These skills are baked into the binary at compile time, no filesystem needed.

use serde::{Deserialize, Serialize};

/// A built-in skill definition (metadata + YAML content).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddedSkill {
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: String,
    pub category: String,
    pub tags: Vec<String>,
    /// YAML content of the skill manifest.
    pub yaml: String,
}

// ── Compile-time embedded skill YAML ─────────────────────────────────

const ECHO_SAMPLE_YAML: &str = r#"id: "com.dsh.skills.echo-sample"
name: "Echo Sample"
version: "1.0.0"
description: "A simple echo skill for testing the execution engine"
category: "data"
tags: ["sample", "test", "echo"]
preferred_execution_type: system_software
software_name: cmd.exe
permissions: []
steps:
  - id: echo-hello
    description: Echo hello world
    exec:
      type: echo
      message: "Hello from DSH Skill Platform!"
  - id: shell-echo
    description: Run shell echo
    exec:
      type: shell
      command: cmd
      args: ["/c", "echo Hello from shell!"]
"#;

const FILE_DEMO_YAML: &str = r#"id: "com.dsh.skills.file-demo"
name: "File Demo"
version: "1.0.0"
description: "Demonstrates file read/write operations"
category: "data"
tags: ["sample", "file", "demo"]
preferred_execution_type: system_software
software_name: cmd.exe
permissions: []
steps:
  - id: write-temp
    description: Write a temporary file
    exec:
      type: file_write
      path: "%TEMP%/dsh-demo.txt"
      content: "DSH Skill Platform was here!"
  - id: read-temp
    description: Read it back
    exec:
      type: file_read
      path: "%TEMP%/dsh-demo.txt"
  - id: echo-content
    description: Echo the file content
    exec:
      type: echo
      message: "File read complete"
"#;

const HTTP_DEMO_YAML: &str = r#"id: "com.dsh.skills.http-demo"
name: "HTTP Demo"
version: "1.0.0"
description: "Demonstrates HTTP GET request"
category: "web"
tags: ["sample", "http", "web"]
preferred_execution_type: system_software
software_name: cmd.exe
permissions: []
steps:
  - id: fetch-example
    description: Fetch example.com
    exec:
      type: http_get
      url: "https://example.com"
  - id: echo-result
    description: Echo completion
    exec:
      type: echo
      message: "HTTP request complete"
"#;

const DIR_LIST_YAML: &str = r#"id: "com.dsh.skills.dir-list"
name: "Directory Listing"
version: "1.0.0"
description: "List directory contents"
category: "data"
tags: ["file", "directory", "list"]
preferred_execution_type: system_software
software_name: cmd.exe
permissions: []
steps:
  - id: list-temp
    description: List temp directory
    exec:
      type: dir_list
      path: "%TEMP%"
      recursive: false
  - id: echo-done
    description: Echo completion
    exec:
      type: echo
      message: "Directory listing complete"
"#;

const SYSTEM_INFO_YAML: &str = r#"id: "com.dsh.skills.system-info"
name: "System Info"
version: "1.0.0"
description: "Display system information (OS, CPU, memory)"
category: "desktop"
tags: ["system", "info", "diagnostics"]
preferred_execution_type: system_software
software_name: cmd.exe
permissions: []
steps:
  - id: os-info
    description: Get OS information
    exec:
      type: shell
      command: cmd
      args:
        - "/c"
        - "systeminfo"
  - id: cpu-info
    description: Get CPU info
    exec:
      type: shell
      command: cmd
      args:
        - "/c"
        - "wmic cpu get name /value"
  - id: mem-info
    description: Get memory info
    exec:
      type: shell
      command: cmd
      args:
        - "/c"
        - "wmic memorychip get capacity /value"
  - id: echo-done
    description: Echo completion
    exec:
      type: echo
      message: "System info retrieved"
"#;

const POWERSHELL_CMD_YAML: &str = r#"id: "com.dsh.skills.powershell-cmd"
name: "PowerShell Command"
version: "1.0.0"
description: "Run a PowerShell command and return output"
category: "desktop"
tags: ["powershell", "shell", "command"]
preferred_execution_type: system_software
software_name: powershell.exe
permissions: []
steps:
  - id: run-ps
    description: Execute PowerShell Get-Date
    exec:
      type: shell
      command: powershell
      args:
        - "-Command"
        - "Get-Date -Format 'yyyy-MM-dd HH:mm:ss'"
  - id: get-processes
    description: List top 5 processes by CPU
    exec:
      type: shell
      command: powershell
      args:
        - "-Command"
        - "Get-Process | Sort-Object CPU -Descending | Select-Object -First 5 Name,CPU | Format-Table -AutoSize"
  - id: echo-done
    description: Echo completion
    exec:
      type: echo
      message: "PowerShell commands executed"
"#;

const CLIPBOARD_DEMO_YAML: &str = r#"id: "com.dsh.skills.clipboard-demo"
name: "Clipboard Demo"
version: "1.0.0"
description: "Demonstrate clipboard read/write via PowerShell"
category: "desktop"
tags: ["clipboard", "powershell", "data"]
preferred_execution_type: system_software
software_name: powershell.exe
permissions: []
steps:
  - id: write-clipboard
    description: Write to clipboard
    exec:
      type: shell
      command: powershell
      args:
        - "-Command"
        - "Set-Clipboard -Value 'Hello from DSH Skill Platform!'"
  - id: read-clipboard
    description: Read from clipboard
    exec:
      type: shell
      command: powershell
      args:
        - "-Command"
        - "Get-Clipboard"
  - id: echo-done
    description: Echo completion
    exec:
      type: echo
      message: "Clipboard demo complete"
"#;

const SCREENSHOT_YAML: &str = r#"id: "com.dsh.skills.screenshot"
name: "Screenshot"
version: "1.0.0"
description: "Take a screenshot and save to temp directory"
category: "desktop"
tags: ["screenshot", "image", "capture"]
preferred_execution_type: system_software
software_name: powershell.exe
permissions: []
steps:
  - id: take-screenshot
    description: Capture screen and save
    exec:
      type: shell
      command: powershell
      args:
        - "-Command"
        - "Add-Type -AssemblyName System.Windows.Forms; $screen = [System.Windows.Forms.Screen]::PrimaryScreen; $bounds = $screen.Bounds; $bitmap = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height); $graphics = [System.Drawing.Graphics]::FromImage($bitmap); $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size); $bitmap.Save('%TEMP%/dsh-screenshot.png'); $graphics.Dispose(); $bitmap.Dispose(); Write-Output 'Screenshot saved'"
  - id: echo-done
    description: Echo completion
    exec:
      type: echo
      message: "Screenshot saved to TEMP directory"
"#;

const OPEN_APP_YAML: &str = r#"id: "com.dsh.skills.open-app"
name: "Open Application"
version: "1.0.0"
description: "Open common Windows applications"
category: "desktop"
tags: ["app", "launch", "open"]
preferred_execution_type: system_software
software_name: cmd.exe
permissions: []
steps:
  - id: open-notepad
    description: Open Notepad
    exec:
      type: shell
      command: cmd
      args:
        - "/c"
        - "start notepad.exe"
  - id: open-calc
    description: Open Calculator
    exec:
      type: shell
      command: cmd
      args:
        - "/c"
        - "start calc.exe"
  - id: echo-done
    description: Echo completion
    exec:
      type: echo
      message: "Applications opened"
"#;

const NETWORK_INFO_YAML: &str = r#"id: "com.dsh.skills.network-info"
name: "Network Info"
version: "1.0.0"
description: "Display network configuration and connectivity"
category: "web"
tags: ["network", "ip", "connectivity"]
preferred_execution_type: system_software
software_name: cmd.exe
permissions: []
steps:
  - id: ip-config
    description: Get IP configuration
    exec:
      type: shell
      command: cmd
      args:
        - "/c"
        - "ipconfig /all"
  - id: test-connectivity
    description: Test internet connectivity
    exec:
      type: shell
      command: cmd
      args:
        - "/c"
        - "ping -n 2 8.8.8.8"
  - id: echo-done
    description: Echo completion
    exec:
      type: echo
      message: "Network info retrieved"
"#;

/// Get all built-in embedded skills.
pub fn get_embedded_skills() -> Vec<EmbeddedSkill> {
    vec![
        EmbeddedSkill {
            id: "com.dsh.skills.echo-sample".into(),
            name: "Echo Sample".into(),
            version: "1.0.0".into(),
            description: "A simple echo skill for testing the execution engine".into(),
            category: "data".into(),
            tags: vec!["sample".into(), "test".into(), "echo".into()],
            yaml: ECHO_SAMPLE_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.file-demo".into(),
            name: "File Demo".into(),
            version: "1.0.0".into(),
            description: "Demonstrates file read/write operations".into(),
            category: "data".into(),
            tags: vec!["sample".into(), "file".into(), "demo".into()],
            yaml: FILE_DEMO_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.http-demo".into(),
            name: "HTTP Demo".into(),
            version: "1.0.0".into(),
            description: "Demonstrates HTTP GET request".into(),
            category: "web".into(),
            tags: vec!["sample".into(), "http".into(), "web".into()],
            yaml: HTTP_DEMO_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.dir-list".into(),
            name: "Directory Listing".into(),
            version: "1.0.0".into(),
            description: "List directory contents".into(),
            category: "data".into(),
            tags: vec!["file".into(), "directory".into(), "list".into()],
            yaml: DIR_LIST_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.system-info".into(),
            name: "System Info".into(),
            version: "1.0.0".into(),
            description: "Display system information (OS, CPU, memory)".into(),
            category: "desktop".into(),
            tags: vec!["system".into(), "info".into(), "diagnostics".into()],
            yaml: SYSTEM_INFO_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.powershell-cmd".into(),
            name: "PowerShell Command".into(),
            version: "1.0.0".into(),
            description: "Run a PowerShell command and return output".into(),
            category: "desktop".into(),
            tags: vec!["powershell".into(), "shell".into(), "command".into()],
            yaml: POWERSHELL_CMD_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.clipboard-demo".into(),
            name: "Clipboard Demo".into(),
            version: "1.0.0".into(),
            description: "Demonstrate clipboard read/write via PowerShell".into(),
            category: "desktop".into(),
            tags: vec!["clipboard".into(), "powershell".into(), "data".into()],
            yaml: CLIPBOARD_DEMO_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.screenshot".into(),
            name: "Screenshot".into(),
            version: "1.0.0".into(),
            description: "Take a screenshot and save to temp directory".into(),
            category: "desktop".into(),
            tags: vec!["screenshot".into(), "image".into(), "capture".into()],
            yaml: SCREENSHOT_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.open-app".into(),
            name: "Open Application".into(),
            version: "1.0.0".into(),
            description: "Open common Windows applications".into(),
            category: "desktop".into(),
            tags: vec!["app".into(), "launch".into(), "open".into()],
            yaml: OPEN_APP_YAML.into(),
        },
        EmbeddedSkill {
            id: "com.dsh.skills.network-info".into(),
            name: "Network Info".into(),
            version: "1.0.0".into(),
            description: "Display network configuration and connectivity".into(),
            category: "web".into(),
            tags: vec!["network".into(), "ip".into(), "connectivity".into()],
            yaml: NETWORK_INFO_YAML.into(),
        },
    ]
}

/// Get the manifest.json-style registry of embedded skills.
pub fn get_embedded_registry() -> serde_json::Value {
    serde_json::json!({
        "skill_set": "dsh-builtin-skills",
        "version": "1.0.0",
        "description": "DSH built-in skills (compile-time embedded)",
        "skills": get_embedded_skills().into_iter().map(|s| {
            serde_json::json!({
                "id": s.id,
                "name": s.name,
                "version": s.version,
                "description": s.description,
                "category": s.category,
                "tags": s.tags,
                "source": "embedded"
            })
        }).collect::<Vec<_>>()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn embedded_skills_parseable() {
        for skill in get_embedded_skills() {
            let manifest = crate::skill::manifest::SkillManifest::from_yaml(&skill.yaml);
            assert!(
                manifest.is_ok(),
                "Failed to parse {}: {:?}",
                skill.id,
                manifest.err()
            );
        }
    }

    #[test]
    fn embedded_count() {
        assert!(get_embedded_skills().len() >= 10);
    }
}
