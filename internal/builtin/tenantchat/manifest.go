package tenantchat

import (
	"encoding/json"

	"github.com/ceoadmin/CEOadmin/internal/builtin"
)

func init() {
	builtin.Register(builtin.AppManifest{
		Slug:        "tenant-chat",
		Name:        "甲乙方 AI 对聊",
		Description: "甲、乙是两个真实的扫码 iLink 用户（租户），跨租户 AI 对聊：各自只配自己的人设，统一调用平台系统 OpenAI 接口。",
		Icon:        "💬",
		Readme: `甲乙方 AI 对聊 让两个真实租户（甲、乙）的 AI 助手自动展开对话。

特点：
- 甲、乙分别是两个**真实的扫码 iLink 用户**（两个独立的 Hub 租户账号），不是写死的人设。
- 每个租户**只配置自己的参数**（显示名 + 系统提示词 / 人设），彼此隔离、互不可改。
- 实际对话统一走平台的**系统 OpenAI 接口**（全局 AI 配置：同一套模型与密钥），租户不接触 API Key。
- 支持「自动对聊」「单步」「暂停」「重置」，可自定义话题、轮数、节奏。

使用方式：
1. 在「系统管理 → AI 设置」中配置好全局 AI（API Key / 模型）。
2. 甲方（扫码登录的用户）进入「甲乙方 AI 对聊」→「创建对聊」，获得邀请码 / 链接。
3. 乙方（另一个扫码登录的用户）用邀请码「加入对聊」。
4. 双方各自设置自己的人设，任一方点击「开始对聊」，两个 AI 自动轮流发言。`,
		Guide: `## 使用甲乙方 AI 对聊

1. 在「系统管理 → AI 设置」中配置好全局 AI（API Key / 模型）。
2. 甲方：左侧导航进入「甲乙方 AI 对聊」→「创建对聊」，复制邀请码 / 链接发给乙方。
3. 乙方：在同一页面用邀请码「加入对聊」。
4. 甲、乙各自只编辑**自己**的人设；任一方点击「开始对聊」即可自动对聊，也可「单步」逐句推进或「暂停 / 重置」。`,
		Homepage:     "",
		Scopes:       []string{},
		Events:       []string{},
		ConfigSchema: json.RawMessage(`{}`),
	}, nil) // no event handler — conversation is driven by the Web UI
}
