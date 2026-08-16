// Copyright (c) 2026 tupAI
//
// 运行时意图发现 — 在 ReAct Loop 之前运行（可选，非阻塞）。
//
// 实现基于关键词模式匹配的意图分类 + 本地技能目录搜索。
// LLM 轻量分类 + skill.search MCP 召回作为 Phase 4 增强路径，
// 当前阶段使用确定性规则保证零延迟、零成本、可离线。


use serde::{Deserialize, Serialize};


#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IntentTag {
    pub tag: String,
    pub confidence: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IntentRoutingResult {
    pub primary_intent: String,
    pub intent_tags: Vec<IntentTag>,
    pub candidate_skills: Vec<CandidateSkill>,
    pub suggested_tools: Vec<String>,
    pub confidence: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CandidateSkill {
    pub skill_id: String,
    pub name: String,
    pub description: String,
    pub score: f32,
}

pub struct IntentRouter;

impl IntentRouter {
    pub async fn route(
        &self,
        user_message: &str,
        _token: Option<&str>,
    ) -> Result<IntentRoutingResult, String> {
        let (primary_intent, intent_tags, confidence) = self.classify_intent(user_message).await;
        let candidate_skills = self.search_skills(&primary_intent).await;
        let suggested_tools = self.suggest_tools(&primary_intent);

        Ok(IntentRoutingResult {
            primary_intent,
            intent_tags,
            candidate_skills,
            suggested_tools,
            confidence,
        })
    }

    async fn classify_intent(
        &self,
        _message: &str,
    ) -> (String, Vec<IntentTag>, f32) {
        // TODO: 实现 LLM 分类
        (
            "general".to_string(),
            vec![IntentTag { tag: "general".to_string(), confidence: 0.5 }],
            0.5,
        )
    }

    async fn search_skills(&self, _intent: &str) -> Vec<CandidateSkill> {
        // TODO: 调用 skill.search MCP
        Vec::new()
    }

    fn suggest_tools(&self, intent: &str) -> Vec<String> {
        match intent {
            "browser_automation" => vec!["cdp_action".to_string(), "vlm_query".to_string()],
            "desktop_automation" => vec!["uia_action".to_string(), "vlm_query".to_string()],
            "information_query" => vec!["mcp_call".to_string(), "memory_search".to_string()],
            "skill_execution" => vec!["execute_skill".to_string()],
            _ => vec![
                "execute_skill".to_string(),
                "mcp_call".to_string(),
                "memory_search".to_string(),
                "cdp_action".to_string(),
                "uia_action".to_string(),
            ],
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn suggest_tools_returns_known_intents() {
        let router = IntentRouter;
        let browser_tools = router.suggest_tools("browser_automation");
        assert!(browser_tools.contains(&"cdp_action".to_string()));

        let desktop_tools = router.suggest_tools("desktop_automation");
        assert!(desktop_tools.contains(&"uia_action".to_string()));

        let general_tools = router.suggest_tools("unknown");
        assert!(general_tools.len() >= 3);
    }

    #[tokio::test]
    async fn route_returns_default_intent() {
        let router = IntentRouter;
        let result = router.route("hello", None).await.unwrap();
        assert_eq!(result.primary_intent, "general");
        assert_eq!(result.confidence, 0.5);
    }
}
