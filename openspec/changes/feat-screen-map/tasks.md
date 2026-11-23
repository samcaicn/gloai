# 任务：屏图（screen map）

> **实施依据**：`openspec/changes/feat-screen-map/design.md`（D1–D10），本计划逐条对应。
> **分支**：`worktree-screen-map`（独立 worktree，不动 main）。一次性做完，不分期。
> 任务按依赖顺序排列，每个任务独立可测、独立提交；用 TDD（先写失败测试再实现）。

## 全局约定

- 新模块：core 侧 `com.wangzan101.mika.core.screen.map`；app 侧落盘 `app/src/main/java/com/wangzan101/mika/knowledge/`。
- 认屏口径复用 `RouteAnchors.texts(snapshot)`（固定骨架：去数字、去列表/可滚动内容、去输入框、去用户打的字，只留标题栏/标签/按钮）。
- 屏身份确定性：屏 id = `sha256(app + "\u0000" + 首次骨架锚点签名)` 截前 16 hex；同一批运行记录重建两遍 → 逐字节相同（可整体 `rebuild`）。
- 序列化用 kotlinx.serialization（先例：`core/learn/replay/Route.kt` 的 `RouteCodec`）。
- 测试命令：core 单测 `./gradlew :core:test`；app 单测 `./gradlew :app:testDebugUnitTest`。
- 中文提交信息，每个任务单独 commit。

## Review Focus（规格没写、但最容易咬到人的输入，每条由归属任务的测试钉住）

1. **认屏误认**：两屏共享「确定/返回/设置」这类通用文字时，绝不能靠它把两屏判成同一屏——稀有度必须把它降权（任务 2 测试）。
2. **低于阈值不猜**：一屏只有模糊锚点、或图里没这屏时，`identify` 必须返回 None，而不是硬匹配最近的一屏（任务 2 测试）。
3. **一页两种结构**：缺 banner/空态时骨架仍匹配（覆盖阈值 < 1.0）；骨架真 disjoint 时拆成两屏，不合错（任务 2、3 测试）。
4. **页面更新后的旧路线**：回放落错屏重试一次仍不对 → Diverged，绝不继续点（任务 7 测试）。
5. **读写失败等于没有图**：图文件损坏/冲突时，认屏与注入照常空转，任务不受影响（任务 6 测试）。

---

### Task 1：屏实体 + 确定性屏 id（core）

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenNode.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenNodeTest.kt`

**Interfaces:**
- Produces（后续任务都用）:
  - `enum class TransitionEffect { NAVIGATED, STATE_CHANGED, NONE, LEFT_APP, UNKNOWN }`
  - `data class Transition(val action: String, val target: String?, val effect: TransitionEffect, val to: String?, val count: Int = 0, val offTrack: Int = 0)`，`key()` = `(action, target, effect, to)`
  - `data class ScreenNode(val id: String, val app: String, val name: String = "", val anchors: Map<String, Int> = emptyMap(), val levels: Map<String, Int> = emptyMap(), val transitions: Map<String, Transition> = emptyMap(), val visits: Int = 0, val status: String = "provisional")`
  - `object ScreenId { fun of(app: String, signature: String): String }` —— sha256 截前 16 hex
  - `object ScreenNodeCodec { fun encode(n: ScreenNode): String; fun decode(s: String): ScreenNode }`

- [ ] **1.1 写失败测试**：`ScreenNodeTest`——`ScreenId.of` 同输入两次相同、不同 app/签名不同、稳定（不随时间变）；`ScreenNodeCodec` round-trip（encode→decode 等于原对象）；`Transition.key()` 五元组一致。
- [ ] **1.2 跑测试确认失败**（类不存在）。Run: `./gradlew :core:test --tests "*ScreenNodeTest"`，期望 FAIL。
- [ ] **1.3 实现 `ScreenNode.kt`**：上表类型 + `@Serializable` 注解，`ScreenId.of` 用 `sha256(app + "\u0000" + signature)` 截前 16 hex，`key()` 由 `Transition` 内部算。
- [ ] **1.4 跑测试确认通过**。
- [ ] **1.5 提交**：`feat(screenmap): 屏实体与确定性屏 id`

---

### Task 2：认屏 `ScreenRecognizer`（core，纯函数零模型调用）

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenRecognizer.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenRecognizerTest.kt`

**Interfaces:**
- Consumes: `ScreenNode`、`RouteAnchors.texts`（`core/learn/replay/Route.kt`，签名 `fun texts(s: ScreenSnapshot): List<String>`）
- Produces:
  - `data class Recognition(val id: String, val confidence: Double, val matched: Set<String>)`
  - `fun identify(texts: Set<String>, nodes: List<ScreenNode>, confidenceMin: Double = 0.5): Recognition?` —— 按 `(重合数, 覆盖率, 稀有度)` 排序取最像，置信度低于阈值返回 null；见 design D2。

**稀有度**：`rarity(text) = 1.0 / max(1, 该 text 在多少个节点的 anchors 里出现)`；`confidence = 覆盖率`（|S∩anchors| / |anchors|）与稀有度加权后的分，具体权重先给 `0.5*覆盖率 + 0.5*稀有度归一`，真机再调（design D10）。

- [ ] **2.1 写失败测试**：`ScreenRecognizerTest` 覆盖——
  - 同屏多锚点命中 → 认对；缺一个锚点（覆盖率仍达标）→ 仍认对（一页两结构的「缺 banner」）；
  - 通用文字（「确定」「返回」）在多屏都出现 → 稀有度降权，不因它把两屏认成同一屏（Review Focus #1）；
  - 只有模糊锚点 / 图里没这屏 → 返回 null（Review Focus #2）。
- [ ] **2.2 跑测试确认失败**。
- [ ] **2.3 实现 `identify`**：先按 `RouteAnchors.texts` 的口径过滤出骨架文字集合，再算重合/覆盖/稀有度排序；低于 `confidenceMin` 返回 null。
- [ ] **2.4 跑测试确认通过**。
- [ ] **2.5 提交**：`feat(screenmap): 认屏（骨架锚点+覆盖+稀有度+置信度）`

---

### Task 3：建图 `ScreenMapBuilder`（从运行记录确定性重建）

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapBuilder.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapBuilderTest.kt`

**Interfaces:**
- Consumes: `ScreenNode`、`Transition`、`RunEvent.ActionTarget`（`core/run/record/RunEvent.kt`，含 `before/after: Anchors`、`tool`、`target`）、`RouteAnchors`
- Produces:
  - `class ScreenGraph(val app: String, val nodes: Map<String, ScreenNode>)`，`fun nodes(): List<ScreenNode>`
  - `class ScreenMapBuilder { fun apply(runId: String, events: List<RunEvent>): Map<String, ScreenGraph> }` —— 返回「app slug → 图」；visit/建屏、加转移边（effect 分类见 design D1）。

**effect 分类**（`RunEvent.ActionTarget` 相邻两锚点）：before 包名≠after 包名 → LEFT_APP；after 屏 id≠before 屏 id → NAVIGATED；effective 且同屏 → STATE_CHANGED；否则 NONE。

- [ ] **3.1 写失败测试**：给一段合成 `RunEvent.ActionTarget` 序列（两屏、一次点击换屏），断言产出的图：屏 id 确定、转移边 action/target/effect/to 正确、visits 计数正确；**同一批 events 跑两遍 → 两个 `Map` 序列化后逐字节相同**（确定性，design D4）。
- [ ] **3.2 跑测试确认失败**。
- [ ] **3.3 实现 `apply`**：按事件顺序 visit before/after（`ScreenId.of(app, 首次骨架锚点签名)` 建屏，签名 = 稳定骨架文字排序拼接）；matched 时 status provisional→confirmed、anchors 计数 +1、levels 计票；转移边 key 去重计数。
- [ ] **3.4 跑测试确认通过**。
- [ ] **3.5 提交**：`feat(screenmap): 从运行记录确定性重建屏图`

---

### Task 4：相对导航 `ScreenGraph.path`

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapBuilder.kt`（或拆 `ScreenGraph.kt`）
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenGraphTest.kt`

**Interfaces:**
- Consumes: `ScreenGraph`（任务 3）
- Produces:
  - `fun ScreenGraph.path(from: String, to: String): List<Transition>?` —— BFS 最短路径；不可达返回 null
  - `fun commonPrefix(a: List<Transition>, b: List<Transition>): Int` —— 公共前缀长度（供回放接图裁公共部分，design D6）

- [ ] **4.1 写失败测试**：存在路径返回正确转移序列；不可达返回 null；两条路有公共前缀时 `commonPrefix` 给出正确长度。
- [ ] **4.2 跑测试确认失败**。
- [ ] **4.3 实现**。
- [ ] **4.4 跑测试确认通过**。
- [ ] **4.5 提交**：`feat(screenmap): 图上相对导航与公共前缀`

---

### Task 5：图渲染 `ScreenMapRender`（给模型的紧凑地图文字）

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapRender.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapRenderTest.kt`

**Interfaces:**
- Consumes: `ScreenGraph`、`ScreenNode`
- Produces:
  - `fun renderPosition(graph: ScreenGraph, currentId: String?): String` —— 「你现在在 X 屏」（或认不出时的空）
  - `fun renderMap(graph: ScreenGraph, currentId: String?, maxScreens: Int = 12, maxChars: Int = 1200): String` —— 屏清单 + 从当前屏出发的转移边（「点 X → 到 Y」）；超限截断（不能挤掉元素列表，design D8）

- [ ] **5.1 写失败测试**：渲染出「屏名 + 从当前屏出发的边」；超 `maxScreens`/`maxChars` 截断；图空/认不出时返回空串或「没有图」占位。
- [ ] **5.2 跑测试确认失败**。
- [ ] **5.3 实现**。
- [ ] **5.4 跑测试确认通过**。
- [ ] **5.5 提交**：`feat(screenmap): 屏图渲染成给模型的紧凑地图`

---

### Task 6：存储 `ScreenMapStore` / `FileScreenMapStore`（core 接口 + app 落盘）

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapStore.kt`（接口）
- Create: `app/src/main/java/com/wangzan101/mika/knowledge/FileScreenMapStore.kt`
- Test: `app/src/test/java/com/wangzan101/mika/knowledge/FileScreenMapStoreTest.kt`

**Interfaces:**
- Consumes: `ScreenGraph`、`ScreenNodeCodec`
- Produces:
  - `interface ScreenMapStore { fun load(app: String): ScreenGraph?; fun save(app: String, graph: ScreenGraph); fun apps(): List<String> }`
  - `FileScreenMapStore(dir: File)` —— 落盘 `files/knowledge/apps/<app-slug>/screens.json`，原子写 + revision 校验（先例 `AppKnowledgeFiles`）；读失败/损坏 → `load` 返回 null

- [ ] **6.1 写失败测试**：save→load round-trip；原子写（写一半不坏旧文件）；revision 冲突不覆盖；损坏文件 → load 返回 null 不抛（Review Focus #5）。
- [ ] **6.2 跑测试确认失败**。
- [ ] **6.3 实现接口与落盘**。
- [ ] **6.4 跑测试确认通过**。
- [ ] **6.5 提交**：`feat(screenmap): 屏图落盘（原子写+revision）`

---

### Task 7：回放接图（`Replayer` 认屏 + 落错屏重试 + 交还文案）

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/learn/replay/Replayer.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/RouteReplay.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/learn/replay/ReplayerScreenMapTest.kt`

**Interfaces:**
- Consumes: `ScreenRecognizer.identify`（任务 2）、`ScreenGraph`/`ScreenMapStore`（任务 3/6）、现有 `Replayer`（`core/learn/replay/Replayer.kt`）
- Produces: `Replayer` 每步改用 `identify` 核对 before 屏（认出的屏 == 预期，或锚点仍命中）；动作后 `identify` 认落屏，非预期落屏重试一次（仍不对 Diverged）；`RouteReplay` 交还文案从「第 N 步失败」改为「落在屏 X + 从这屏出发的路」（`ScreenMapRender.renderMap`）。

- [ ] **7.1 写失败测试**：落错屏 → 重试一次 → 仍错则 Diverged 且 `executed` 语义正确（Review Focus #4）；对得上 → Completed；交还文案含「落在屏 X / 可从这里走」。
- [ ] **7.2 跑测试确认失败**。
- [ ] **7.3 实现**：在 `Replayer.run` 的每步 before/after 核对处接入 `identify`；加落错屏重试一次；`RouteReplay.run` 的 Diverged 分支拼 `renderMap`。
- [ ] **7.4 跑测试确认通过**（并跑 `:core:test` 全量，确认没改坏现有回放测试）。
- [ ] **7.5 提交**：`feat(screenmap): 回放接图（认屏+落错重试+交还地图）`

---

### Task 8：知识注入（随 APP.md 一起给屏图）

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt`（`injectKnowledge` 与 `appendRuntimeState` 一带）
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/ContextBuilder.kt`（若注入在此）
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/agent/AgentLoopScreenMapTest.kt`

**Interfaces:**
- Consumes: `ScreenMapStore.load`、`ScreenRecognizer.identify`、`ScreenMapRender.renderPosition/renderMap`
- Produces: 进入某 App（已有图）时，`APP.md` 之后追加一段屏图（页面结构）；认得出当前屏时先给「你在哪」，再给「屏清单 + 从这怎么走」。

- [ ] **8.1 写失败测试**：给一个已有图的 App 与一帧认得出的快照，断言注入上下文含 APP.md 文字 + 屏图文字；图空/读失败时不注入、任务照常。
- [ ] **8.2 跑测试确认失败**。
- [ ] **8.3 实现**：在现有 `injectKnowledge` 路径（`APP.md` 注入点）后接 `renderMap`；`runtimeState` 里带 `renderPosition`。
- [ ] **8.4 跑测试确认通过**。
- [ ] **8.5 提交**：`feat(screenmap): 进入 App 时随 APP.md 注入页面结构`

---

### Task 9：收尾长图（`RunLearning` 重放运行记录更新图）

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/RunLearning`（或 `app/.../task/RunLearning.kt` 对应接线）
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`（交出 `RunAftermath` 的地方，把 `events` 传给长图）
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/agent/RunLearningScreenMapTest.kt`

**Interfaces:**
- Consumes: `ScreenMapBuilder.apply`（任务 3）、`ScreenMapStore.save`（任务 6）、`RunAftermath.events`
- Produces: 任务收尾后台阶段，重放本次 `events` 得到**本次运行看到的屏/边片段**，与已存图**按屏 id 合并**（同 id → anchors/visits/transitions 计数累加、status provisional→confirmed；新 id → 追加）→ 落盘；失败只计数不影响任务（design D4）。

- [ ] **9.1 写失败测试**：给一段成功运行的 `events`，断言收尾后该 App 图新增了对应屏/转移边；`events` 为空或图写失败不抛、不影响任务结局。
- [ ] **9.2 跑测试确认失败**。
- [ ] **9.3 实现**：在 `RunLearning.learn`（或收尾后台协程）里调 `ScreenMapBuilder.apply` + `ScreenMapStore.save`，包在 `guarded` 里。
- [ ] **9.4 跑测试确认通过**。
- [ ] **9.5 提交**：`feat(screenmap): 任务收尾重放运行记录长图`

---

### Task 10：merge gate 自检（wrong-merge / wrong-owner 归零）

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapMergeGate.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapMergeGateTest.kt`

**Interfaces:**
- Consumes: `ScreenNode`、`ScreenRecognizer`（任务 2）
- Produces:
  - `fun wrongMerge(nodes: List<ScreenNode>, labeled: Map<String, String>): List<Pair<String, String>>` —— 返回「被认成同一屏但标注不同名」的对（应为空）
  - `fun wrongOwner(events: List<RunEvent>, graphs: Map<String, ScreenGraph>): List<String>` —— 返回「事件归错 App」的条目（应为空）

- [ ] **10.1 写失败测试**：构造两个标不同名、但锚点重合度高到会被 `identify` 认成同一屏的节点 → `wrongMerge` 非空（门禁拦下）；事件归错 App → `wrongOwner` 非空；正常数据 → 两者皆空。
- [ ] **10.2 跑测试确认失败**。
- [ ] **10.3 实现**。
- [ ] **10.4 跑测试确认通过**。
- [ ] **10.5 提交**：`feat(screenmap): merge gate 自检`

---

## 收尾

- [ ] 全量回归：`./gradlew :core:test :app:testDebugUnitTest`（必要时 `:core:cliTest`）绿。
- [ ] 真机冒烟（发版前）：装一个带图的任务跑一遍，回看确认「进入 App 注入屏图 / 回放走图 / 收尾长图」三处生效；取证进 `evidence/`。
- [ ] 中文提交历史已按任务分好；`openspec/changes/feat-screen-map/` 下 proposal/design/tasks 三份齐备。
