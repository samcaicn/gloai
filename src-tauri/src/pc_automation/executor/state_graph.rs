// Copyright (c) 2026 AIMarketing
//
// ============================================================================
// 可 checkpoint 的执行图 (StateGraph) — AIMarketing v5 中期 8
// ============================================================================
//
// StateGraph 是 AdaptiveExecutor 主循环的"投影":把当前 Skill 的
// 线性步骤列表映射成一个有状态的执行图(本期只支持线性,后续
// 接入 Skill.branches 时再扩展为 DAG)。
//
// 每个 StepNode 跟踪自己的运行态(Pending / Running / Completed /
// Failed / Skipped)、attempts、最近一次选用的 strategy、错误信息
// 和起止时间戳。StepGraph 还维护一个 `cursor`,指向当前正在跑
// 的 step 在 `order` 中的位置 —— 这就是主循环"下一步执行什么"
// 的唯一真源。
//
// 设计要点:
//   * **零侵入集成**:本期不重写 `AdaptiveExecutor::execute_skill`
//     主循环,只暴露 `checkpoint_current` / `restore_from_snapshot`
//     两个 API,在每个 step 完成或失败时把当前快照写出去。
//   * **线性优先**:`from_skill_linear` 只取 `skill.steps` 的顺序
//     作为 `order`,不展开 `branches`(branches 在 v5 仍是空 stub)。
//   * **快照自描述**:`GraphSnapshot` 包含 `exec_id` 和
//     `last_checkpoint_at`,外部持久化层只需把它当 blob 存,恢复
//     时调 `restore` 即可。
//   * **serde camelCase**:对齐项目其它 IPC 数据结构。
//   * **错误用中文**:与项目约定一致。
//
// 后续(本期不实现):
//   * 把 `execute_skill` 主循环切换为 `StepGraph::next()` 驱动;
//   * 在 `restore` 后跳过已 Completed 的 step 直接续跑;
//   * 支持 branches(条件 / 并行)。
// ============================================================================

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::pc_automation::skill::types::Skill;
use crate::pc_automation::step::StepStrategy;

// ============================================================================
// 数据结构
// ============================================================================

/// 单个 step 在图中的运行时状态。Pure data,serde 友好。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StepNode {
    pub step_id: String,
    pub state: NodeState,
    pub attempts: u32,
    pub last_error: Option<String>,
    pub started_at: Option<i64>,
    pub finished_at: Option<i64>,
    pub chosen_strategy: Option<StepStrategy>,
    pub chosen_action: Option<String>,
}

/// 节点状态机:Pending → Running → {Completed | Failed | Skipped}。
/// `Failed` 是终态 —— step 失败后不会再回 Running(由 error_handler
/// chain 负责"复活",那是另一个 StepNode 的事)。
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum NodeState {
    Pending,
    Running,
    Completed,
    Failed,
    Skipped,
}

/// 整个 skill 执行的图。本期只支持线性,所以 `nodes` 的拓扑序
/// 由 `order` 这个 Vec 显式表达 —— 后续接入 branches 后,
/// `order` 会被替换成拓扑排序结果。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StepGraph {
    pub skill_id: String,
    pub exec_id: String,
    pub nodes: HashMap<String, StepNode>,
    pub order: Vec<String>,
    /// `order` 中"下一个要执行"的下标。完成时 == order.len()。
    pub cursor: usize,
    pub created_at: i64,
    pub last_checkpoint_at: Option<i64>,
}

/// StepGraph 的不可变快照,用于序列化到磁盘 / 跨进程恢复。
/// 与 `StepGraph` 字段完全一致,只是类型上独立(便于未来增
/// 加"快照生成时刻的额外元信息",如 `checkpoint_reason`)。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GraphSnapshot {
    pub skill_id: String,
    pub exec_id: String,
    pub nodes: HashMap<String, StepNode>,
    pub order: Vec<String>,
    pub cursor: usize,
    pub created_at: i64,
    pub last_checkpoint_at: Option<i64>,
}

// ============================================================================
// StepGraph 行为
// ============================================================================

impl StepGraph {
    /// 从一个线性 Skill 构建 StateGraph(本期不展开 branches)。
    /// 所有节点初始为 `Pending`,attempts = 0,cursor = 0。
    /// `created_at` 由调用方传入,避免内部依赖 `SystemTime`(测试
    /// 时更可控)。
    pub fn from_skill_linear(skill: &Skill, exec_id: &str, now_ms: i64) -> Self {
        let mut nodes = HashMap::with_capacity(skill.steps.len());
        let mut order = Vec::with_capacity(skill.steps.len());
        for step in &skill.steps {
            order.push(step.id.clone());
            nodes.insert(
                step.id.clone(),
                StepNode {
                    step_id: step.id.clone(),
                    state: NodeState::Pending,
                    attempts: 0,
                    last_error: None,
                    started_at: None,
                    finished_at: None,
                    chosen_strategy: None,
                    chosen_action: None,
                },
            );
        }
        Self {
            skill_id: skill.skill_id.clone(),
            exec_id: exec_id.to_string(),
            nodes,
            order,
            cursor: 0,
            created_at: now_ms,
            last_checkpoint_at: None,
        }
    }

    /// 当前 cursor 指向的 step 的不可变引用。如果已经走完,
    /// 返回 `None`。
    pub fn next(&self) -> Option<&StepNode> {
        self.order
            .get(self.cursor)
            .and_then(|id| self.nodes.get(id))
    }

    /// 当前 cursor 指向的 step 的可变引用。
    pub fn next_mut(&mut self) -> Option<&mut StepNode> {
        let id = self.order.get(self.cursor)?.clone();
        self.nodes.get_mut(&id)
    }

    /// 把当前节点标记为完成。`strategy` / `action` 是为了
    /// 让图能"回忆"上一步用了什么 —— 后续 System 1 cache 命中
    /// 时可以参考。
    ///
    /// **注意语义**:`advance` 干了两件事 —— 把当前节点切到
    /// Running 并把 cursor 推进一格。所以"刚跑完"的那一步实际
    /// 是 `cursor - 1` 指向的那一个(主循环刚刚 advance 进入
    /// 的就是它)。本方法找的就是这个最近被进入的 step。
    pub fn mark_current_completed(
        &mut self,
        strategy: StepStrategy,
        action: String,
        now_ms: i64,
    ) {
        if let Some(node) = self.last_started_mut() {
            if node.state == NodeState::Failed {
                return;
            }
            node.state = NodeState::Completed;
            node.chosen_strategy = Some(strategy);
            node.chosen_action = Some(action);
            node.finished_at = Some(now_ms);
            node.last_error = None;
        }
    }

    /// 把当前节点标记为失败。语义同 `mark_current_completed`:
    /// 找 `cursor - 1` 那一个(刚被 advance 进入的 step)。
    /// 失败后 cursor **不** 自动推进 —— 主循环可能还要让
    /// error_handler chain 介入,这种"复活"表现为同一个 node
    /// 的 attempts++ 后再跑。
    pub fn mark_current_failed(&mut self, err: String, now_ms: i64) {
        if let Some(node) = self.last_started_mut() {
            node.state = NodeState::Failed;
            node.last_error = Some(err);
            node.finished_at = Some(now_ms);
        }
    }

    /// "最近一次被 advance 进入"的那一个 step 的可变引用。
    /// 即 `order[cursor - 1]`(若 cursor > 0,否则 None)。
    fn last_started_mut(&mut self) -> Option<&mut StepNode> {
        if self.cursor == 0 {
            return None;
        }
        let id = self.order.get(self.cursor - 1)?.clone();
        self.nodes.get_mut(&id)
    }

    /// 是否已经走完所有 step(即 cursor 已经超过 order 末尾)。
    /// 注意:即便中间有 step Failed,只要 cursor 已经走完,is_done
    /// 也为 true —— 失败是"中途 abort",走完是"全部处理过"。
    pub fn is_done(&self) -> bool {
        self.cursor >= self.order.len()
    }

    /// 生成当前图的一份不可变快照(便于序列化 / 落盘)。
    /// 同时把 `last_checkpoint_at` 设为 `now_ms`,反映"我刚
    /// 做过一次 checkpoint"。
    pub fn checkpoint(&mut self, now_ms: i64) -> GraphSnapshot {
        self.last_checkpoint_at = Some(now_ms);
        GraphSnapshot {
            skill_id: self.skill_id.clone(),
            exec_id: self.exec_id.clone(),
            nodes: self.nodes.clone(),
            order: self.order.clone(),
            cursor: self.cursor,
            created_at: self.created_at,
            last_checkpoint_at: self.last_checkpoint_at,
        }
    }

    /// 从快照恢复。会校验 `order` 与 `nodes` 的对应关系是否
    /// 自洽(每个 order 中的 step_id 都能在 nodes 找到),失败
    /// 时返回中文错误。
    pub fn restore(snap: GraphSnapshot) -> Result<Self, String> {
        for id in &snap.order {
            if !snap.nodes.contains_key(id) {
                return Err(format!(
                    "快照自相矛盾:order 中的 step_id {:?} 在 nodes 里找不到",
                    id
                ));
            }
        }
        if snap.cursor > snap.order.len() {
            return Err(format!(
                "快照 cursor({}) 越界,order 长度={}",
                snap.cursor,
                snap.order.len()
            ));
        }
        Ok(Self {
            skill_id: snap.skill_id,
            exec_id: snap.exec_id,
            nodes: snap.nodes,
            order: snap.order,
            cursor: snap.cursor,
            created_at: snap.created_at,
            last_checkpoint_at: snap.last_checkpoint_at,
        })
    }

    /// 把 cursor 往前推一格。同时把下一格的节点(如果存在)状态
    /// 设为 Running 并打上 `started_at`,以便和 Pending 区分。
    /// 已走完时调用是 no-op(让主循环不必判 is_done)。
    pub fn advance(&mut self, now_ms: i64) {
        if self.is_done() {
            return;
        }
        // 先把当前节点标记一下"开始跑过"(如果还没 Running):
        // 真正的 Running 标记其实在 `mark_current_*` 之前应该
        // 已经设置,这里只做 attempts++ 和 started_at 兜底。
        if let Some(node) = self.next_mut() {
            if node.state == NodeState::Pending {
                node.state = NodeState::Running;
                node.started_at = Some(now_ms);
            }
            if node.state == NodeState::Failed {
                return;
            }
            node.attempts = node.attempts.saturating_add(1);
        }
        self.cursor = self.cursor.saturating_add(1);
    }
}

// ============================================================================
// 单元测试
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::skill::types::Skill;

    fn build_skill_with_steps(step_ids: &[&str]) -> Skill {
        use crate::pc_automation::skill::types::SkillStep;
        let steps: Vec<SkillStep> = step_ids
            .iter()
            .map(|id| SkillStep::single(*id, "desc", "uia:controlType=Button"))
            .collect();
        Skill {
            skill_id: "skill_test".into(),
            version: "1.0.0".into(),
            intent: "测试".into(),
            scene_fingerprint: None,
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
            success_rate: 1.0,
            avg_execution_time_ms: 0,
            parameters: Vec::new(),
            steps,
            error_handlers: Vec::new(),
            branches: Vec::new(),
            name: "skill_test".into(),
            description: String::new(),
            license: None,
        }
    }

    // -------------------------------------------------------------
    // 1. from_skill_linear 顺序与节点数
    // -------------------------------------------------------------
    #[test]
    fn test_from_skill_linear_builds_correct_order() {
        let skill = build_skill_with_steps(&["a", "b", "c"]);
        let graph = StepGraph::from_skill_linear(&skill, "exec-1", 1000);
        assert_eq!(graph.skill_id, "skill_test");
        assert_eq!(graph.exec_id, "exec-1");
        assert_eq!(graph.order, vec!["a", "b", "c"]);
        assert_eq!(graph.nodes.len(), 3);
        assert_eq!(graph.cursor, 0);
        assert_eq!(graph.created_at, 1000);
        assert!(graph.last_checkpoint_at.is_none());
        for node in graph.nodes.values() {
            assert_eq!(node.state, NodeState::Pending);
            assert_eq!(node.attempts, 0);
        }
    }

    // -------------------------------------------------------------
    // 2. next / advance 推进 cursor
    // -------------------------------------------------------------
    #[test]
    fn test_next_and_advance_progresses_cursor() {
        let skill = build_skill_with_steps(&["a", "b"]);
        let mut graph = StepGraph::from_skill_linear(&skill, "exec-2", 2000);
        assert_eq!(graph.next().unwrap().step_id, "a");
        graph.advance(2001);
        assert_eq!(graph.next().unwrap().step_id, "b");
        // 推进后第一个节点应被标记 Running 并 attempts=1
        assert_eq!(graph.nodes["a"].state, NodeState::Running);
        assert_eq!(graph.nodes["a"].attempts, 1);
        assert_eq!(graph.nodes["a"].started_at, Some(2001));
        graph.advance(2002);
        // 第二个节点
        assert_eq!(graph.nodes["b"].state, NodeState::Running);
        assert_eq!(graph.nodes["b"].attempts, 1);
        // 再 advance 一次,走完
        graph.advance(2003);
        assert!(graph.is_done());
        assert!(graph.next().is_none());
    }

    // -------------------------------------------------------------
    // 3. mark_current_completed 更新状态
    // -------------------------------------------------------------
    #[test]
    fn test_mark_current_completed_updates_state() {
        let skill = build_skill_with_steps(&["only"]);
        let mut graph = StepGraph::from_skill_linear(&skill, "exec-3", 3000);
        graph.advance(3001); // 进入 Running, attempts=1
        graph.mark_current_completed(StepStrategy::Uia, "click(target)".into(), 3002);
        let node = &graph.nodes["only"];
        assert_eq!(node.state, NodeState::Completed);
        assert_eq!(node.chosen_strategy, Some(StepStrategy::Uia));
        assert_eq!(node.chosen_action.as_deref(), Some("click(target)"));
        assert_eq!(node.finished_at, Some(3002));
        assert!(node.last_error.is_none());
    }

    // -------------------------------------------------------------
    // 4. mark_current_failed
    // -------------------------------------------------------------
    #[test]
    fn test_mark_current_failed_marks_failed_state() {
        let skill = build_skill_with_steps(&["fail_me"]);
        let mut graph = StepGraph::from_skill_linear(&skill, "exec-4", 4000);
        graph.advance(4001);
        graph.mark_current_failed("selector miss".into(), 4002);
        let node = &graph.nodes["fail_me"];
        assert_eq!(node.state, NodeState::Failed);
        assert_eq!(node.last_error.as_deref(), Some("selector miss"));
        assert_eq!(node.finished_at, Some(4002));
        // failed 后 cursor 不应自动推进 —— 主循环可能要让
        // error_handler chain 介入
        assert_eq!(graph.cursor, 1);
    }

    // -------------------------------------------------------------
    // 5. checkpoint / restore 往返
    // -------------------------------------------------------------
    #[test]
    fn test_checkpoint_and_restore_round_trip() {
        let skill = build_skill_with_steps(&["a", "b", "c"]);
        let mut graph = StepGraph::from_skill_linear(&skill, "exec-5", 5000);
        graph.advance(5001);
        graph.mark_current_completed(StepStrategy::Cdp, "cdp:click".into(), 5002);
        graph.advance(5003);
        graph.mark_current_completed(StepStrategy::Ocr, "ocr:click".into(), 5004);
        let snap = graph.checkpoint(5999);
        // 快照应携带 checkpoint 时间戳
        assert_eq!(snap.last_checkpoint_at, Some(5999));
        assert_eq!(snap.cursor, 2);
        assert_eq!(snap.nodes["a"].state, NodeState::Completed);
        assert_eq!(snap.nodes["b"].state, NodeState::Completed);
        // 恢复
        let restored = StepGraph::restore(snap).expect("restore ok");
        assert_eq!(restored.skill_id, "skill_test");
        assert_eq!(restored.exec_id, "exec-5");
        assert_eq!(restored.cursor, 2);
        assert_eq!(restored.order, vec!["a", "b", "c"]);
        assert_eq!(restored.nodes["a"].chosen_strategy, Some(StepStrategy::Cdp));
        assert_eq!(restored.nodes["b"].chosen_strategy, Some(StepStrategy::Ocr));
        // next() 应当指向 "c"
        assert_eq!(restored.next().unwrap().step_id, "c");
        assert!(!restored.is_done());
    }

    // -------------------------------------------------------------
    // 6. restore exec_id 错配 / 矛盾 order
    // -------------------------------------------------------------
    #[test]
    fn test_restore_with_mismatched_exec_id_errors() {
        // 模拟一个"order 里有但 nodes 里没有"的破损快照
        let mut nodes = HashMap::new();
        nodes.insert(
            "a".to_string(),
            StepNode {
                step_id: "a".into(),
                state: NodeState::Pending,
                attempts: 0,
                last_error: None,
                started_at: None,
                finished_at: None,
                chosen_strategy: None,
                chosen_action: None,
            },
        );
        let bad_snap = GraphSnapshot {
            skill_id: "skill".into(),
            exec_id: "exec-bad".into(),
            order: vec!["a".into(), "ghost".into()], // ghost 不在 nodes
            nodes,
            cursor: 0,
            created_at: 0,
            last_checkpoint_at: None,
        };
        let res = StepGraph::restore(bad_snap);
        assert!(res.is_err(), "破损快照必须报错");
        let err = res.unwrap_err();
        assert!(err.contains("ghost"), "错误信息应提及缺失的 step id, got: {}", err);
    }

    #[test]
    fn test_restore_with_cursor_overflow_errors() {
        // cursor > order.len() 应报错。给一个自洽的 nodes(order
        // 里的 "a" 在 nodes 里存在),让 cursor 检查先于 order
        // 检查触发。
        let mut nodes = HashMap::new();
        nodes.insert(
            "a".to_string(),
            StepNode {
                step_id: "a".into(),
                state: NodeState::Pending,
                attempts: 0,
                last_error: None,
                started_at: None,
                finished_at: None,
                chosen_strategy: None,
                chosen_action: None,
            },
        );
        let bad_snap = GraphSnapshot {
            skill_id: "s".into(),
            exec_id: "e".into(),
            order: vec!["a".into()],
            nodes,
            cursor: 5, // 越界
            created_at: 0,
            last_checkpoint_at: None,
        };
        let res = StepGraph::restore(bad_snap);
        assert!(res.is_err());
        assert!(res.unwrap_err().contains("越界"));
    }

    // -------------------------------------------------------------
    // 7. is_done
    // -------------------------------------------------------------
    #[test]
    fn test_is_done_when_all_completed() {
        let skill = build_skill_with_steps(&["x"]);
        let mut graph = StepGraph::from_skill_linear(&skill, "exec-7", 7000);
        assert!(!graph.is_done(), "空跑前 is_done=false");
        graph.advance(7001);
        graph.mark_current_completed(StepStrategy::Ocr, "act".into(), 7002);
        // 走完
        graph.advance(7003);
        assert!(graph.is_done());
        assert!(graph.next().is_none());
        // 已被标记 Completed
        assert_eq!(graph.nodes["x"].state, NodeState::Completed);
    }
}
