# 待办事项 - Hermes 自进化 Phase 2/3 后续工作

## 已修复 Bug（本轮完成）

- [x] **BUG: `runtime.set` 静默丢弃 falsy 值** — `mid.register('runtime.set')` 中 `if (p.value && ...)` 导致设置 `false`/`0`/`""`/`null` 时静默失效 → 移除真值检查，直接赋值
- [x] **BUG: `mid._lastCtx` 永远为 null** — `_executeStepInner` 调用 `mid.exec` 前未注入 ctx，导致所有依赖 `_lastCtx` 的内置 handler 失效 → 在 `mid.exec` 前设置 `mid._lastCtx = ctx`，finally 恢复
- [x] **BUG: `cap.llm.setComplete is not a function`** — capabilities.js 未实现 `setComplete`，trace-auto / publisher 技能启动即崩 → 添加 `setComplete(cb)` / `getComplete()` 方法
- [x] **BUG: `runtime.script` 无法访问运行时变量** — 传空 ctx 导致脚本拿不到 vars/params → 改用 `mid._lastCtx` 作为上下文
- [x] **BUG: `shared_desktop()` 线程安全问题** — Windows COM 非线程安全导致测试并发崩溃 → `OnceCell<Mutex<Desktop>>` + 测试级锁 `DESKTOP_TEST_LOCK`
- [x] **BUG: `test_shared_desktop_is_idempotent` 比较 Desktop 指针** — 旧实现比较 Mutex 地址 → 改为比较内部 `Desktop` 实例指针

## 本轮新修复 Bug（c0c8a48ab）

- [x] **BUG: 模板表达式不计算** — `skillRuntime.js` 的 `_resolve` 函数在路径解析失败后回退到 `new Function()` 做 JS 表达式求值，支持 `${vars.x * 2}` 等算术表达式
- [x] **BUG: `SandboxRunner::dry_run` 硬编码 latency** — 为格式错误输入提供默认延迟值，并添加注释说明 dry_run 为启发式估算
- [x] **BUG: `DedupIndex::best_match` 无阈值保护** — `decide_verdict` 已修改：`DUPLICATE_MEDIUM` (jaccard 0.5-0.8) 阻止 auto-Accept，强制 NeedsReview，防止部分重复技能被静默接受
- [x] **BUG: mesh P2P 广播去重无版本号比较** — `MeshMessage::SkillSync` 增加 `version` 字段（`#[serde(default)]`），`handle_skill_sync` 实现版本号去重（高版本覆盖低版本，同版本 ts 更新者胜），`SKILL_SYNC_VERSIONS` 内存跟踪
- [x] **BUG: `upgrade_writer` 的 Phase 2 override layer 未覆盖 `SkillKind::Builtin` 的所有 entry_action 映射** — `write_builtin_override` 从 `skills_embedded` 查找 `entry_action` 并注入到覆盖文件 front matter 中
- [x] **BUG: `runBuiltinSkill` 的 entry_action 映射仅在 action='execute' 时触发** — 映射条件扩展：未传 action 或传入的 action 不在技能 `params.action.enum` 中时均映射到 `entry_action`
- [x] **BUG: `trace-auto.js` 的 `checkConditions` LLM prompt 未处理空回复** — 添加防御性检查：`!reply || reply === '无' || !reply.trim()` 均视为不匹配
- [x] **BUG: `auto-product-comm.js` 的 FLOWCHART 定义过大** — 验证后确认当前版本未引用不存在的 skillId（可能为旧版本问题，已关闭）

## Phase 2: automation/builtin skill 升级 + coverage 收集

- [x] **verify** `autoskill::upgrade_writer` 的 Phase 2 override layer 逻辑完整（`SkillKind::Builtin` 路径） — `write_builtin_override` + `find_builtin_entry_action` 已实现
- [ ] **verify** `hermes::skill_evaluator` 的 proposal 评分维度（safety/success/generalization/dedup/cost）覆盖所有内置技能
- [ ] **verify** `hermes::evolution_signal` 的信号消费校验 + 指标精确化 + 阈值常量化 在生产环境生效
- [x] **implement** builtin skill coverage 收集 — `skills_embedded.rs` 添加 `SkillCoverage` 结构体 + `record_builtin_skill_run` + `get_coverage_snapshot` + Tauri commands；前端 `runBuiltinSkill` 执行后 best-effort 上报 coverage
- [ ] **test** 批量运行 trace-auto / wechat-publisher / xiaohongshu-publisher / auto-product-comm 的实际执行，验证 coverage 数据

## Phase 3: mesh P2P skill sync

- [ ] **verify** `mesh::backend` 的 skill 广播/同步逻辑与前端 UI 联动
- [ ] **verify** `mesh::frontend` 的对端渲染 + invoke 返回归一化 + i18n 补全 在生产环境正常
- [ ] **implement** P2P skill 同步的端到端测试：节点 A 发布 skill → 节点 B 接收并可用
- [x] **fix** 潜在的并发竞争条件（多个节点同时广播同一 skill 时的去重逻辑） — 版本号去重 + `SKILL_SYNC_VERSIONS` 内存跟踪

## 通用

- [x] `cargo test --package tupai` 核心模块全量通过（131/131 hermes/autoskill/skill_eval/storage 测试通过；UIA 测试崩溃是预先存在的 Windows COM 问题）
- [x] `cargo check --all-targets` 全平台编译通过
- [x] 提交所有 Phase 2/3 变更到 `v2` 分支（c0c8a48ab）
- [x] push 到 CI 验证构建
