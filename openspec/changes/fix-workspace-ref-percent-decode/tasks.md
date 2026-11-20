## 1. 修复

- [x] 1.1 `MarkdownModel.workspaceRefs`：抽出 Link/Image 目标后按 `ProxyAddress` 先例做
  宽容百分号解码（`+` 预替换成 `%2B`、`runCatching` 失败保留原串），解码后去重返回
- [x] 1.2 确认 `WorkspaceRef.outputRefsParsed` 无需改动（normalize 过滤在解码后仍把关
  `..` 与越界）

## 2. 测试

- [x] 2.1 `MarkdownModelTest`：`%20`、中文 `%E6%8A%A5`、字面 `+`、非法 `%` 序列、
  `<>` 裸形式不受影响、`%2F` 解码后仍被 normalize 圈住
- [x] 2.2 `WorkspaceRefTest`：编码引用解析出的 `Item.path` 为解码路径、照片分类正确、
  越界编码引用（如 `%2E%2E`）被丢弃
- [x] 2.3 跑 `:app:testDebugUnitTest` 相关过滤与 `:core:test` 全量，确认无回归

## 3. 主回答接线（真机走查发现：Done/Cancelled 不渲染引用卡片）

- [x] 3.1 `StatusCard` 加 `conversationId` 参数（`exchange()` 调用处传入），`Done` 的回答
  与 `Cancelled` 的 summary 在 `MarkdownText` 下、`ReplyActions` 上画 `OutputRefs`，
  点开照 `FinishedReply` 的 `openSent` + 失败 toast
- [x] 3.2 重装修复版真机复测：任务回答含 `%20`/`%E6` 引用时卡片出现、名字解码、点开能打开，
  截图取证

## 4. 交付

- [ ] 4.1 提交（中文提交信息）、推分支 `fix/workspace-ref-percent-decode`、建 PR 关联
  产出引用功能背景，正文附单测结果与 commonmark 0.30.1 行为实证说明
- [ ] 4.2 等 CI 四组（app/core/cli/relay）全绿；真机走查取证存 `evidence/` 并补进 PR
