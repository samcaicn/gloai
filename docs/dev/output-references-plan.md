# 产出引用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让模型在 `/workspace` 里产出的文件（图表/报表/音频/截图）能在聊天里像「用户附件」一样被引用、被渲染、被点开。

**Architecture:** 三小块——① 从模型回复的 Markdown 里抽出 `/workspace/...` 引用（纯逻辑，JVM 可测）；② 把引用解析成「类型 + 宿主文件」；③ 聊天页复用 `AttachmentMessage` 的照片格子/文件卡片渲染、点开走 `FileViewerScreen`。另加一条提示词引导模型这么用。

**Tech Stack:** Kotlin + Jetpack Compose；commonmark（已依赖）做 Markdown 解析；`SandboxPort.hostPath` 做路径解析；`ViewerKinds`/`AttachmentMessage`/`FileViewerScreen` 复用。

**Spec:** `docs/dev/output-references.md`

## Global Constraints

- 引用就是 `/workspace/...` 路径本身，不引入 URL scheme、不新增一行文本约定。
- 会话级：复用现状 `workspace: () -> String`（对话 ID），对话结束即清，不做跨会话持久。
- 不改沙箱安全边界：解析后的路径仍要过 `SandboxPaths.normalize` / `hostPath`（越界、链接逃逸一律不算引用）。
- 所有新字符串走 `R.string`，补 11 种语言（i18n 测试会查）。

## Review Focus

| 输入/情形 | 期望行为 |
|---|---|
| 引用是 `![alt](/workspace/a.png)`（图片）或 `[名](/workspace/a.csv)`（链接） | 两种都抽出 `/workspace/...` |
| 正文里裸提 `/workspace/c`（不是 Markdown 图片/链接） | 不当作引用 |
| 路径带 `..`、或 `hostPath` 返回 null（越界/逃逸/不存在） | 该引用不渲染，不崩溃 |
| 文件是 .csv/.pdf/.py 等非媒体 | 画文件卡片（不是照片格子），点开预览 |
| 文件已不存在（会话清掉后） | 卡片显示「已失效」或直接不渲染，不崩 |

---

### Task 1: 抽出 Markdown 里的 `/workspace` 引用（纯逻辑）

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt`
- Test: `app/src/test/java/com/wangzan101/mika/ui/MarkdownModelTest.kt`（若已存在则追加）

**Interfaces:**
- Produces: `fun MarkdownModel.workspaceRefs(markdown: String): List<String>` — 返回 Markdown 里所有 `Image`/`Link` 节点的 `destination` 中以 `/workspace` 开头的路径（按出现顺序，去重，保留原样含 `/workspace` 前缀）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun workspaceRefsExtractsImageAndLink() {
    assertEquals(
        listOf("/workspace/a.png", "/workspace/b.csv"),
        MarkdownModel.workspaceRefs("看图 ![图](/workspace/a.png) 和 [报告](/workspace/b.csv)"),
    )
}
@Test fun workspaceRefsIgnoresBareTextAndHttp() {
    assertEquals(emptyList<String>(), MarkdownModel.workspaceRefs("文件在 /workspace/c 里，外链 https://x.com/a.png"))
}
@Test fun workspaceRefsDeduplicates() {
    assertEquals(listOf("/workspace/a.png"), MarkdownModel.workspaceRefs("![x](/workspace/a.png) ![y](/workspace/a.png)"))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wangzan101.mika.ui.MarkdownModelTest"`
Expected: 编译失败 / `workspaceRefs` 未定义

- [ ] **Step 3: 实现 `MarkdownModel.workspaceRefs(markdown: String): List<String>`**

用已用的 commonmark `Parser` 解析，遍历 `Image` 与 `Link` 节点，取 `destination` 且以 `/workspace` 开头者，按序去重。其余 Markdown（代码块、纯文本）自然不产生 Image/Link 节点，无需特殊处理。

- [ ] **Step 4: 跑测试确认通过**

Run: 同上
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt app/src/test/java/com/wangzan101/mika/ui/MarkdownModelTest.kt
git commit -m "feat(markdown): 抽出回复里的 /workspace 引用"
```

---

### Task 2: 把引用解析成「类型 + 宿主文件」

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/chat/ui/WorkspaceRef.kt`（或并入现有 attachment 文件，见下）
- Test: `app/src/test/java/com/wangzan101/mika/chat/ui/WorkspaceRefTest.kt`

**Interfaces:**
- Consumes: `MarkdownModel.workspaceRefs(markdown)`（Task 1）
- Produces:
  ```kotlin
  data class WorkspaceRef(val path: String, val host: java.io.File, val kind: ViewerKind)
  fun resolveWorkspaceRefs(sandbox: SandboxPort, workspace: String, refs: List<String>): List<WorkspaceRef>
  ```
  `kind` 用 `ViewerKinds`（Image / 其它），音频可后续并入；`host` 由 `sandbox.hostPath(workspace, ref, forWrite=false)` 得到，为 null 时该引用丢弃。

- [ ] **Step 1: 写失败测试**（用 fake `SandboxPort`，`hostPath` 对存在的路径返回 `File`、对越界/`..` 返回 null）

```kotlin
@Test fun resolveWorkspaceRefsDropsInvalid() {
    // fake hostPath: /workspace/a.png -> file, /workspace/../x -> null
    val r = resolveWorkspaceRefs(fake, "conv1", listOf("/workspace/a.png", "/workspace/../x"))
    assertEquals(listOf("/workspace/a.png"), r.map { it.path })
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wangzan101.mika.chat.ui.WorkspaceRefTest"`
Expected: FAIL（类型/函数未定义）

- [ ] **Step 3: 实现 `resolveWorkspaceRefs`**

对每个 ref：先 `SandboxPaths.normalize(ref)` 判断仍以 `/workspace` 开头，再 `hostPath`，为 null 丢弃；`kind = ViewerKinds.of(null, host.name)`。

- [ ] **Step 4: 跑测试确认通过** → **Step 5: Commit**（`feat: 解析 /workspace 引用为宿主文件与类型`）

---

### Task 3: 聊天页渲染产出引用

**Files:**
- Modify: 助手消息的渲染入口（`ChatScreen.kt` 或 `MarkdownText.kt` 附近的 Message 渲染），以及 `AttachmentMessage.kt` 的渲染原语
- Test: 手动验证 + Task 1/2 的 JVM 测试兜底（Compose UI 不建 instrumentation）

**Interfaces:**
- Consumes: `MarkdownModel.workspaceRefs`（Task 1）、`resolveWorkspaceRefs`（Task 2）

- [ ] **Step 1: 在助手消息渲染处接上引用**

模型回复里若有 `/workspace` 引用，在其 Markdown 文本下方按 `AttachmentMessage` 的照片格子（图片）+ 文件卡片（非图片）渲染；点开走 `FileViewerScreen`（复用现有打开逻辑，含 workspace 寻址）。

- [ ] **Step 2: 手动/UI 验证**：让模型写 `![图](/workspace/a.png)` 与 `[表](/workspace/b.csv)`，确认聊天里分别渲染成照片缩略图与文件卡片、点开能预览。

- [ ] **Step 3: Commit**（`feat: 聊天渲染 /workspace 产出引用`）

---

### Task 4: 提示词引导

**Files:**
- Modify: 系统提示词里「文件/工作区」相关段落（`core/src/main/resources/prompts/` 下对应文件）

- [ ] **Step 1: 加一句引导**

告诉模型：写到 `/workspace` 的图片/文件，在回复里用 Markdown `![描述](/workspace/xxx.png)` 或 `[文件名](/workspace/xxx.csv)` 写出来，就会在聊天里渲染；展示给用户时优先用它而不是 `share_file`。

- [ ] **Step 2: 跑 prompt-surface / DocsConsistency 相关测试**

Run: `./gradlew :core:test`
Expected: 相关 prompt 快照测试若有则更新，其余 PASS

- [ ] **Step 3: Commit**（`feat(prompt): 引导模型引用 /workspace 产出`）

---

## Self-Review 结论

- 覆盖 spec 三小块 + 提示词，无遗漏。
- Task 1 是纯逻辑、JVM 可测，是整条链路的锚点；Task 3 是 Compose UI，以手动验证为主，故把可测逻辑（解析/解析）都沉到 Task 1/2。
- Review Focus 五条分别落在 Task 1（裸文本/http 不误抽、去重）、Task 2（越界/不存在丢弃）、Task 3（非媒体画卡片、失效不崩）。
