# 提案：产出引用的 `/workspace` 链接目标按百分号转义解不出来

## Why

main `b63f358d`（产出引用，尚未随版本发布）有两个缺口，真机走查（PR #748）先后撞上：

1. **主回答根本不渲染引用卡片**：`OutputRefs` 只接在插话回复（`FinishedReply`）上；
   任务回答（`ExchangeStatus.Done`）与停止后的交代（`Cancelled.summary`）只画
   `MarkdownText`，提示词 `6e927608` 明明是对任务沙箱说「结果文件用 Markdown 引用交付」——
   模型照做了，用户却看不到卡片。走查实测：任务回答含
   `![…](/workspace/%E6%8A%A5%E5%91%8A.png)` 与 `[my report.csv](/workspace/my%20report.csv)`，
   界面只有内联文字，无缩略图、无文件卡。
2. **链接目标百分号转义不解码**：`MarkdownModel.workspaceRefs` 对 Markdown 链接/图片目标
   不做百分号解码（commonmark-java 0.30.1 实测原样返回 `my%20file.csv`、`%E6%8A%A5%E5%91%8A.png`）。
   模型按 CommonMark 规则引用带空格的文件名时必须写 `%20`（裸空格在 `[](...)` 目标里非法），
   按URL 习惯引用中文文件名时常写 `%E6%8A%A5` 形式——这些引用即使渲染出来也是名字带编码串的
   卡片，点开走文件闸门按字面路径找不到文件，必弹「文件不存在」；图片缩略图空占位。趁未发版修掉。

## What Changes

- `StatusCard`（`ChatScreen`）补接 `OutputRefs`：`Done` 的回答、`Cancelled` 的交代（summary）
  在 `MarkdownText` 下方渲染引用卡片，点开走既有 `AttachFiles.openSent`（照 `FinishedReply`
  的样子，`conversationId` 作为新参传入）。
- `MarkdownModel.workspaceRefs` 抽出的链接/图片目标先做**宽容百分号解码**（`%HH` 按 UTF-8
  解；非法转义原样保留），再进入去重与后续 `SandboxPaths.normalize` 过滤。
- 解码沿用仓库既有先例（`ProxyAddress.decodeCredential`）：`URLDecoder` 前把 `+` 替换成
  `%2B`，文件名里的字面 `+` 不许被转成空格；解码抛异常时保留原串（字面 `%` 文件名仍可用
  `[](<...>)` 裸形式引用）。
- 补 `MarkdownModelTest`（解码形状）与 `WorkspaceRefTest`（解码后路径进 `Item.path`、照片
  分类正确）；接线按维护者对该功能的测试口径（解析层单测 + 真机走查）。

## Capabilities

### New Capabilities
- `workspace-ref-parsing`: 回答里 `/workspace` 产出引用的抽取与渲染前解析（含百分号转义
  解码语义、越界过滤）。

### Modified Capabilities

（无——该功能刚在 main 落地，openspec/specs 下尚无对应规格。）

## Impact

- `app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt`（workspaceRefs 一处，约 +10 行）
- `app/src/test/.../MarkdownModelTest.kt`、`WorkspaceRefTest.kt`（新增用例）
- 下游 `WorkspaceRef.kt`/`ChatAttachments.kt`/`AttachSources.openSent` 无需改动，拿到解码后
  路径即恢复显示与打开。
