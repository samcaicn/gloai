// Copyright (c) 2026 tupAI
//
// Tool schema compiler — 把所有可用能力翻译成 OpenAI function calling schema。
// AgentLoop 在每次 LLM 调用前用这些 schema 注入 tools 字段。

// ── execute_skill ────────────────────────────────────────────────────────────

pub fn execute_skill_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "execute_skill",
            "description": "执行一个已安装的技能。技能会按预定义的步骤序列自动完成操作，\
                如打开应用、填写表单、点击按钮等。适合重复性多步骤任务。",
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_id": {
                        "type": "string",
                        "description": "技能 ID，如 'wechat-publisher', 'open-notepad'"
                    },
                    "params": {
                        "type": "object",
                        "description": "技能输入参数，JSON 对象",
                        "additionalProperties": true
                    }
                },
                "required": ["skill_id"]
            }
        }
    })
}

// ── mcp_call ───────────────────────────────────────────────────────────────

pub fn mcp_call_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "mcp_call",
            "description": "调用云端 MCP 工具，执行搜索、任务管理、日历、文档等操作。",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "description": "MCP action 名称，如 'skill.search', 'task.poll_pending'",
                        "enum": [
                            "skill.search",
                            "skill.scene_tags",
                            "skill.top_by_tags",
                            "model.list",
                            "task.poll_pending",
                            "task.complete",
                            "calendar.list",
                            "calendar.create",
                            "doc.read",
                            "doc.write"
                        ]
                    },
                    "params": {
                        "type": "object",
                        "description": "action 参数",
                        "additionalProperties": true
                    }
                },
                "required": ["action", "params"]
            }
        }
    })
}

// ── CDP 操作 ────────────────────────────────────────────────────────────────

pub fn cdp_action_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "cdp_action",
            "description": "通过 Chrome DevTools Protocol 控制浏览器（Electron/Chrome）。\
                适用于 Web 应用自动化。",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["navigate", "click", "type", "screenshot", "evaluate"]
                    },
                    "target": {
                        "type": "string",
                        "description": "CSS 选择器或 XPath"
                    },
                    "value": {
                        "type": "string",
                        "description": "操作值（如输入文本、URL）"
                    }
                },
                "required": ["action"]
            }
        }
    })
}

// ── UIA 操作 ───────────────────────────────────────────────────────────────

pub fn uia_action_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "uia_action",
            "description": "通过 Windows UI Automation 控制桌面应用。适用于原生 Windows 应用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["click", "type", "hotkey", "wait"]
                    },
                    "window_title": {
                        "type": "string",
                        "description": "窗口标题（部分匹配）"
                    },
                    "control_id": {
                        "type": "string",
                        "description": "控件 AutomationId"
                    },
                    "value": {
                        "type": "string",
                        "description": "操作值"
                    }
                },
                "required": ["action"]
            }
        }
    })
}

// ── VLM 查询 ───────────────────────────────────────────────────────────────

pub fn vlm_query_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "vlm_query",
            "description": "用视觉语言模型分析屏幕截图并回答问题。用于复杂界面或 CDP/UIA 都无法处理的场景。",
            "parameters": {
                "type": "object",
                "properties": {
                    "question": {
                        "type": "string",
                        "description": "要回答的问题"
                    },
                    "region": {
                        "type": "object",
                        "description": "截图区域坐标 {x, y, width, height}，不填则截全屏"
                    }
                },
                "required": ["question"]
            }
        }
    })
}

// ── 记忆搜索 ───────────────────────────────────────────────────────────────

pub fn memory_search_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "memory_search",
            "description": "搜索本地的长记忆，查找之前完成的任务、操作步骤或决策记录。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "搜索关键词"
                    },
                    "limit": {
                        "type": "integer",
                        "description": "返回结果数量上限",
                        "default": 5
                    }
                },
                "required": ["query"]
            }
        }
    })
}

// ── memory_search_schema end ────────────────────────────────────────────────

// ── ensure_cdp_browser ──────────────────────────────────────────────────────

pub fn ensure_cdp_browser_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "ensure_cdp_browser",
            "description": "确保 CDP 浏览器已连接。如果未连接，自动启动 Chrome/Edge 并开启 CDP 端口。\
                用于需要控制浏览器的技能执行前检查。用户可在前端确认浏览器启动。",
            "parameters": {
                "type": "object",
                "properties": {
                    "browser_type": {
                        "type": "string",
                        "description": "浏览器类型：chrome / edge / brave / chromium，留空自动检测",
                        "enum": ["chrome", "edge", "brave", "chromium"]
                    },
                    "notify_user": {
                        "type": "boolean",
                        "description": "是否通知用户浏览器正在重启（默认 true）",
                        "default": true
                    }
                },
                "required": []
            }
        }
    })
}

// ── pc_execute_step ─────────────────────────────────────────────────────────

pub fn pc_execute_step_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "pc_execute_step",
            "description": "通过三策略路由器执行一个自动化步骤，自动选择最佳策略：\
                CDP（浏览器）→ UIA（桌面应用）→ OCR（文字识别）→ VLM（视觉分析）。\
                适用于需要自动选择策略的复杂场景。",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["click", "type", "wait", "screenshot", "parse_screen"],
                        "description": "操作类型"
                    },
                    "selector": {
                        "type": "string",
                        "description": "元素选择器，如 'css:#login-btn' 或 'uia:name=登录' 或 'ocr:登录按钮'"
                    },
                    "value": {
                        "type": "string",
                        "description": "操作值（如输入文本、等待毫秒数）"
                    },
                    "strategy": {
                        "type": "string",
                        "enum": ["auto", "cdp", "uia", "ocr", "vlm"],
                        "description": "指定策略，auto 为自动选择（默认）",
                        "default": "auto"
                    }
                },
                "required": ["action"]
            }
        }
    })
}

// ── search_and_install_skill ────────────────────────────────────────────────

pub fn search_and_install_skill_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "search_and_install_skill",
            "description": "搜索技能市场并安装技能。当用户请求的功能需要未安装的技能时，\
                自动搜索匹配的技能并安装，使 execute_skill 可调用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "搜索关键词，描述用户想要的功能"
                    },
                    "auto_install": {
                        "type": "boolean",
                        "description": "是否自动安装最佳匹配技能（默认 true）",
                        "default": true
                    },
                    "limit": {
                        "type": "integer",
                        "description": "返回结果数量上限",
                        "default": 5
                    }
                },
                "required": ["query"]
            }
        }
    })
}

// ── 聚合 ─────────────────────────────────────────────────────────────────

/// 返回所有内置工具的 OpenAI schema 数组
pub fn builtin_tool_schemas() -> Vec<serde_json::Value> {
    vec![
        execute_skill_schema(),
        mcp_call_schema(),
        cdp_action_schema(),
        uia_action_schema(),
        vlm_query_schema(),
        memory_search_schema(),
        ensure_cdp_browser_schema(),
        pc_execute_step_schema(),
        search_and_install_skill_schema(),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_tool_schemas_are_valid_openai_format() {
        let schemas = builtin_tool_schemas();
        assert!(schemas.len() >= 9, "should have at least 9 builtin schemas, got {}", schemas.len());
        for schema in &schemas {
            assert_eq!(schema["type"], "function");
            let func = &schema["function"];
            assert!(func["name"].is_string(), "function.name must be a string");
            assert!(func["description"].is_string(), "function.description must be a string");
            assert!(func["parameters"].is_object(), "function.parameters must be an object");
            assert_eq!(func["parameters"]["type"], "object");
        }
    }

    #[test]
    fn schema_names_are_unique() {
        let schemas = builtin_tool_schemas();
        let names: Vec<&str> = schemas
            .iter()
            .map(|s| s["function"]["name"].as_str().unwrap())
            .collect();
        let mut seen = std::collections::HashSet::new();
        for name in &names {
            assert!(seen.insert(*name), "duplicate tool name: {}", name);
        }
    }
}
