# 产出引用

## 意图

模型在沙箱 `/workspace` 里产出文件（图表、报表、音频、截图等），但现在：

- 聊天里没法直接给用户看/点——只能 `share_file` 拷到「下载/Mika」，用户得去文件夹里找；
- 后续轮次要再引用它，只能自己记 `/workspace/...` 路径，凭记忆、不稳。

而「用户带的附件」早就有这套待遇（`附件:/workspace/inbox/...` 行 + 聊天页 `AttachmentMessage` 卡片）。缺口是「模型产出」这半没有。

## 目标

让「模型产出的东西」和「用户带的附件」享有同等的「可引用 + 聊天里可看/可点」待遇。

## 非目标

- 不引入 URL scheme（不搞 `mika://` 之类）。
- 不做跨会话持久：工作区按对话 ID 隔离、对话结束即清（现状已如此）。
- 不改沙箱安全边界（路径规范化、链接逃逸拦截照旧）。

## 现状（已确认）

- `/workspace` 按「对话 ID」切（`TaskTools` 里 `workspace: () -> String`，注释「本次任务的工作区(对话 ID)」，默认 `"default"`）。
- 输入附件走 `AttachmentLines`（`附件:/workspace/...` 文本行）+ 聊天页 `AttachmentMessage` 渲染。
- `share_file` 把文件拷到宿主「下载/Mika」，返回 `savedAs`（一个名字，不是可渲染引用）。
- 助手回复是 Markdown（`MarkdownText` 渲染），但渲染器不认 `/workspace/...` 的图片/链接。

## 方案

复用 `/workspace` 路径本身作为「产出引用」，不另造格式。三小块：

1. **渲染**：聊天页的 Markdown 渲染把 `/workspace/...` 认出来——图片/音频内联，普通文件画文件卡片（复用 `AttachmentMessage`）。
2. **提示词**：告诉模型「写到 `/workspace` 的文件，用 Markdown 图片/链接写出来就会在聊天里渲染」，展示时优先用它而不是 `share_file`。
3. **工具结果**：产出类工具的结果里把 `/workspace/...` 路径写显式（多为现状已有，统一即可）。

## 决定的点

- 引用 = `/workspace/...` 路径本身，不是 URL scheme、不是新的一行文本约定（助手回复本来就是 Markdown）。
- 会话级：复用现状的「按对话 ID 切」，对话结束即清。
- 渲染层复用 `AttachmentMessage`，图片/音频内联、普通文件画卡片。
