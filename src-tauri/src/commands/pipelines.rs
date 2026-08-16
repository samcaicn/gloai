use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tauri::{AppHandle, Manager};

use crate::storage::pipeline as storage;

// ── 前端类型 ────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineStepDef {
    pub skill_id: String,
    pub skill_name: String,
    pub params: serde_json::Value,
    pub order: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineDef {
    pub id: String,
    pub name: String,
    pub scene: String,
    pub steps: Vec<PipelineStepDef>,
    pub rounds: i32,
    pub current_round: i32,
    pub status: String,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatePipelineInput {
    pub name: String,
    pub scene: String,
    pub steps: Vec<PipelineStepDef>,
    pub rounds: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdatePipelineInput {
    pub id: String,
    pub name: String,
    pub steps: Vec<PipelineStepDef>,
    pub rounds: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordStepInput {
    pub pipeline_id: String,
    pub step_index: i32,
    pub skill_id: String,
    pub params: serde_json::Value,
    pub result: String,
    pub duration_ms: i64,
    pub status: String,
}

// ── 辅助函数 ────────────────────────────────────────────

fn get_db(app: &AppHandle) -> Result<Arc<crate::storage::DuckDBPool>, String> {
    app.try_state::<Arc<crate::storage::DuckDBPool>>()
        .map(|s| s.inner().clone())
        .ok_or_else(|| "DuckDB 未初始化".to_string())
}

fn steps_to_json(steps: &[PipelineStepDef]) -> String {
    serde_json::to_string(steps).unwrap_or_else(|_| "[]".to_string())
}

fn row_to_def(row: &storage::PipelineRow) -> PipelineDef {
    let steps: Vec<PipelineStepDef> =
        serde_json::from_str(&row.steps_json).unwrap_or_default();
    PipelineDef {
        id: row.id.clone(),
        name: row.name.clone(),
        scene: row.scene.clone(),
        steps,
        rounds: row.rounds,
        current_round: row.current_round,
        status: row.status.clone(),
        created_at: row.created_at.clone(),
        updated_at: row.updated_at.clone(),
    }
}

// ── IPC 命令 ────────────────────────────────────────────

#[tauri::command]
pub async fn pipeline_create(
    app: AppHandle,
    input: CreatePipelineInput,
) -> Result<PipelineDef, String> {
    let db = get_db(&app)?;
    let id = uuid::Uuid::new_v4().to_string();
    let steps_json = steps_to_json(&input.steps);

    storage::insert_pipeline(
        &db,
        &storage::PipelineInsert {
            id: id.clone(),
            name: input.name.clone(),
            scene: input.scene.clone(),
            steps_json,
            rounds: input.rounds,
            status: storage::STATUS_IDLE.to_string(),
        },
    )
    .map_err(|e| format!("创建流水线失败: {}", e))?;

    let row = storage::get_pipeline(&db, &id)
        .map_err(|e| format!("查询流水线失败: {}", e))?
        .ok_or_else(|| "流水线未找到".to_string())?;
    Ok(row_to_def(&row))
}

#[tauri::command]
pub async fn pipeline_list(app: AppHandle, scene: String) -> Result<Vec<PipelineDef>, String> {
    let db = get_db(&app)?;
    let rows = storage::list_pipelines(&db, &scene)
        .map_err(|e| format!("列出流水线失败: {}", e))?;
    Ok(rows.iter().map(row_to_def).collect())
}

#[tauri::command]
pub async fn pipeline_get(app: AppHandle, id: String) -> Result<Option<PipelineDef>, String> {
    let db = get_db(&app)?;
    let row = storage::get_pipeline(&db, &id)
        .map_err(|e| format!("查询流水线失败: {}", e))?;
    Ok(row.map(|r| row_to_def(&r)))
}

#[tauri::command]
pub async fn pipeline_update(
    app: AppHandle,
    input: UpdatePipelineInput,
) -> Result<PipelineDef, String> {
    let db = get_db(&app)?;
    let steps_json = steps_to_json(&input.steps);

    storage::update_pipeline(
        &db,
        &input.id,
        &input.name,
        &steps_json,
        input.rounds,
        storage::STATUS_IDLE,
    )
    .map_err(|e| format!("更新流水线失败: {}", e))?;

    let row = storage::get_pipeline(&db, &input.id)
        .map_err(|e| format!("查询流水线失败: {}", e))?
        .ok_or_else(|| "流水线未找到".to_string())?;
    Ok(row_to_def(&row))
}

#[tauri::command]
pub async fn pipeline_delete(app: AppHandle, id: String) -> Result<bool, String> {
    let db = get_db(&app)?;
    storage::delete_pipeline(&db, &id).map_err(|e| format!("删除流水线失败: {}", e))
}

#[tauri::command]
pub async fn pipeline_start(app: AppHandle, id: String) -> Result<PipelineDef, String> {
    let db = get_db(&app)?;
    storage::update_pipeline_round(&db, &id, 1, storage::STATUS_RUNNING)
        .map_err(|e| format!("启动流水线失败: {}", e))?;
    let row = storage::get_pipeline(&db, &id)
        .map_err(|e| format!("查询流水线失败: {}", e))?
        .ok_or_else(|| "流水线未找到".to_string())?;
    Ok(row_to_def(&row))
}

#[tauri::command]
pub async fn pipeline_pause(app: AppHandle, id: String) -> Result<PipelineDef, String> {
    let db = get_db(&app)?;
    storage::update_pipeline_status(&db, &id, storage::STATUS_PAUSED)
        .map_err(|e| format!("暂停流水线失败: {}", e))?;
    let row = storage::get_pipeline(&db, &id)
        .map_err(|e| format!("查询流水线失败: {}", e))?
        .ok_or_else(|| "流水线未找到".to_string())?;
    Ok(row_to_def(&row))
}

#[tauri::command]
pub async fn pipeline_stop(app: AppHandle, id: String) -> Result<PipelineDef, String> {
    let db = get_db(&app)?;
    storage::update_pipeline_status(&db, &id, storage::STATUS_STOPPED)
        .map_err(|e| format!("停止流水线失败: {}", e))?;
    let row = storage::get_pipeline(&db, &id)
        .map_err(|e| format!("查询流水线失败: {}", e))?
        .ok_or_else(|| "流水线未找到".to_string())?;
    Ok(row_to_def(&row))
}

#[tauri::command]
pub async fn pipeline_complete_round(app: AppHandle, id: String) -> Result<PipelineDef, String> {
    let db = get_db(&app)?;
    let row = storage::get_pipeline(&db, &id)
        .map_err(|e| format!("查询流水线失败: {}", e))?
        .ok_or_else(|| "流水线未找到".to_string())?;

    let next_round = row.current_round + 1;
    let done = next_round > row.rounds;
    let status = if done {
        storage::STATUS_COMPLETED
    } else {
        storage::STATUS_RUNNING
    };

    storage::update_pipeline_round(&db, &id, next_round, status)
        .map_err(|e| format!("完成轮次失败: {}", e))?;

    let updated = storage::get_pipeline(&db, &id)
        .map_err(|e| format!("查询流水线失败: {}", e))?
        .ok_or_else(|| "流水线未找到".to_string())?;
    Ok(row_to_def(&updated))
}

#[tauri::command]
pub async fn pipeline_record_step(
    app: AppHandle,
    input: RecordStepInput,
) -> Result<(), String> {
    let db = get_db(&app)?;
    // 写入 worker_task_log 供 AutoSkill 后台挖掘
    let conn = db.get_conn();
    conn.execute(
        "INSERT INTO worker_task_log
            (id, scene, task_type, skill_id, skill_version, status, params, result, duration_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        duckdb::params![
            uuid::Uuid::new_v4().to_string(),
            "work",
            format!("pipeline_step:{}:{}", input.pipeline_id, input.step_index),
            input.skill_id,
            "1.0.0",
            input.status,
            serde_json::to_string(&input.params).unwrap_or_default(),
            input.result,
            input.duration_ms,
        ],
    )
    .map_err(|e| format!("记录步骤执行失败: {}", e))?;
    Ok(())
}

// ── 运行时占位符解析 ──────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolveParamsInput {
    /// 步骤参数（含 $steps[i].field 占位符）
    pub params: serde_json::Value,
    /// 前序步骤输出列表（index 对齐 step order）
    pub outputs: Vec<serde_json::Value>,
}

/// 将 params 中 `$steps[i].field` 占位符替换为 outputs[i] 对应值。
/// 不改技能接口，不改 DuckDB schema，纯运行时字符串替换。
#[tauri::command]
pub fn pipeline_resolve_params(input: ResolveParamsInput) -> Result<serde_json::Value, String> {
    Ok(crate::pipeline::resolver::resolve_refs(input.params, &input.outputs))
}

// ── 内置流水线模板 ────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineTemplate {
    pub id: String,
    pub name: String,
    pub description: String,
    pub steps: Vec<PipelineStepDef>,
    pub rounds: i32,
}

/// 返回预定义内置流水线模板，前端"快速创建"用
#[tauri::command]
pub fn pipeline_get_templates() -> Vec<PipelineTemplate> {
    vec![
        // ── 1. 企业热点监测视频流水线 ──
        PipelineTemplate {
            id: "template-hot-video".to_string(),
            name: "企业热点监测视频流水线".to_string(),
            description: "① 监测企业相关热点文章 → ② AI 改写为口播文案 → ③ 生成短视频。适用于品牌营销、公关热点快速响应。".to_string(),
            steps: vec![
                PipelineStepDef {
                    skill_id: "builtin-hot-content-monitor".to_string(),
                    skill_name: "热点内容监测".to_string(),
                    params: serde_json::json!({
                        "action": "search",
                        "keywords": [],
                        "maxResults": 10,
                        "region": "zh"
                    }),
                    order: 0,
                },
                PipelineStepDef {
                    skill_id: "builtin-script-rewriter".to_string(),
                    skill_name: "口播文案改写".to_string(),
                    params: serde_json::json!({
                        "action": "rewrite",
                        "sourceContent": "$steps[0].articles[0].summary",
                        "platform": "douyin",
                        "duration": 60,
                        "tone": "自然口语化"
                    }),
                    order: 1,
                },
                PipelineStepDef {
                    skill_id: "builtin-seedance".to_string(),
                    skill_name: "Seedance 视频生成".to_string(),
                    params: serde_json::json!({
                        "action": "guide",
                        "prompt": "$steps[1].script"
                    }),
                    order: 2,
                },
            ],
            rounds: 1,
        },
        // ── 2. 跨境电商选品流水线 ──
        PipelineTemplate {
            id: "template-cross-border-sourcing".to_string(),
            name: "跨境电商选品流水线".to_string(),
            description: "① 热销趋势监测 → ② 亚马逊选品调研 → ③ 竞品分析 → ④ 1688 供应商搜索 → ⑤ 利润计算 → 生成完整选品报告。".to_string(),
            steps: vec![
                PipelineStepDef {
                    skill_id: "builtin-tiktok-trend-tracker".to_string(),
                    skill_name: "TikTok 趋势追踪器".to_string(),
                    params: serde_json::json!({
                        "action": "trending",
                        "category": "",
                        "marketplace": "US"
                    }),
                    order: 0,
                },
                PipelineStepDef {
                    skill_id: "builtin-amazon-product-research".to_string(),
                    skill_name: "亚马逊选品调研".to_string(),
                    params: serde_json::json!({
                        "action": "search",
                        "keywords": ["$steps[0].category", "$steps[0].products[0].title"],
                        "marketplace": "US"
                    }),
                    order: 1,
                },
                PipelineStepDef {
                    skill_id: "builtin-cross-border-competitor".to_string(),
                    skill_name: "跨境竞品分析".to_string(),
                    params: serde_json::json!({
                        "action": "search",
                        "keywords": ["$steps[1].products[0].title"],
                        "platforms": ["amazon"]
                    }),
                    order: 2,
                },
                PipelineStepDef {
                    skill_id: "builtin-alibaba-1688-sourcing".to_string(),
                    skill_name: "1688 跨境寻源".to_string(),
                    params: serde_json::json!({
                        "action": "search",
                        "keywords": ["$steps[1].products[0].title", "$steps[2].competitors[0].title"]
                    }),
                    order: 3,
                },
                PipelineStepDef {
                    skill_id: "builtin-profit-calculator".to_string(),
                    skill_name: "利润计算器".to_string(),
                    params: serde_json::json!({
                        "action": "analyze",
                        "productInfo": {
                            "purchasePrice": "$steps[3].products[0].price",
                            "sellingPrice": "$steps[2].competitors[0].price",
                            "platform": "amazon"
                        },
                        "market": "US"
                    }),
                    order: 4,
                },
            ],
            rounds: 1,
        },
    ]
}
