# 设计：产出引用链接目标的百分号解码

## Context

- 产出引用链路：`MarkdownModel.workspaceRefs`（commonmark-java 遍历 Link/Image 目标）→
  `outputRefsParsed`（`SandboxPaths.normalize` 过滤越界）→ `AttachmentMessage.Item(path)` →
  卡片名 / 缩略图（`AttachImage.Sent` → `AttachFiles.relative` + 文件闸门）/ 点开
  （`AttachFiles.openSent` 同一闸门）。
- 实证（本机 JDK + 项目所用 commonmark-java 0.30.1）：`[x](/workspace/my%20file.csv)` 的
  destination 原样返回 `my%20file.csv`，`[](<...>)` 裸形式原样返回——解析器不负责解码，
  应用层拿到什么就用什么。当前代码没有解码，编码目标与真实文件名对不上。
- 仓库先例：`ProxyAddress.decodeCredential` 用 `URLDecoder.decode(v.replace("+", "%2B"),
  "UTF-8")`；`WebUrlPolicy`/`SearchEngine` 用 `runCatching { URLDecoder.decode(...) }` 容错。

## Goals / Non-Goals

**Goals:**
- 模型按 CommonMark/URL 习惯写的 `%20`、`%E6%8A%A5` 等转义引用能显示正确文件名、能点开、
  缩略图能加载。
- 文件名里字面 `+`、字面非法 `%` 序列不被破坏。

**Non-Goals:**
- 不改 `openSent`/缩略图的路径解析（它们拿到的已是最终路径，无需双候选回退）。
- 不处理 `/opt/mika/skills` 引用可渲染不可打开的边界（另一件事，本变更不碰）。

## Decisions

1. **解码点放在 `workspaceRefs` 抽出处**（而非 `outputRefsParsed` 或更下游）。
   理由：单一调用方（`WorkspaceRef.kt`），一处解码全链路（名字、分类、缩略图、打开）自然
   拿到正确路径；且去重也在解码后做，`a%20b` 与 `a b` 不会重复计两张卡。
2. **用 `java.net.URLDecoder` + `+` 预替换，失败保留原串**，照 `ProxyAddress` 先例：
   `runCatching { URLDecoder.decode(dest.replace("+", "%2B"), "UTF-8") }.getOrDefault(dest)`。
   - `+` 必须保护：文件名可含字面 `+`，URLDecoder 会把它当空格。
   - 失败（非法 `%` 序列抛 IllegalArgumentException）保留原串：文件名字面含 `%`（如
     `100%.csv`）仍可用 `[](<...>)` 裸引用，不会被整个丢弃。
3. **解码先于 `SandboxPaths.normalize`**：`%2F` 解出 `/` 会按路径分隔符参与规范化，仍被
   圈在 `/workspace` 内，无越界新增面。
4. **主回答接线照 `FinishedReply` 的样子补在 `StatusCard`**：`Done` 的回答与
   `Cancelled` 的交代（summary）都是「回答样文本」，在 `MarkdownText` 下、操作区
   （`ReplyActions`）上画 `OutputRefs`，点开照抄 `FinishedReply` 的
   `AttachFiles.openSent` + 失败 toast。`conversationId` 作为 `StatusCard` 新参从
   `exchange()` 传入（该值在那里已存在）。`Failed.reason` 是失败原因不是回答，不接；
   流式草稿（`DraftText`）不接（`FinishedReply` 也只在说完后画）。
   测试口径照维护者本功能的先例（7edc0747）：解析层 JVM 单测 + 真机走查，渲染层不加
   Compose 测试。

## Risks / Trade-offs

- [字面 `%20` 文件名经 `<>` 裸引用会被误解码成空格] → 接受：链接目标里 `%` 按 URI 语义就是
  转义符（与浏览器一致）；模型生成字面 `%` 文件名的概率远低于按规则编码空格/中文的概率。
  设计与测试里记录该边界。
- [解码后 `Item.path` 变了，历史会话里已渲染的旧卡会不会失效] → 旧卡来自旧版本渲染，本就
  受同一缺陷影响（打不开）；解码后重渲染反而从打不开变成打得开，无回退面。

## Migration Plan

单提交修复 + 单测；无数据迁移。回滚即还原 `workspaceRefs` 一处。

## Open Questions

（无）
