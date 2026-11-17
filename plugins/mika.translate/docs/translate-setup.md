# 页面翻译：接口怎么配

插件本身不带任何密钥。翻译走**你自己配的 OpenAI 兼容接口**，三个环境变量：

| 变量 | 必填 | 说明 | 例子 |
|---|---|---|---|
| `MIKA_TRANSLATE_BASE` | 是 | 完整的 chat/completions 地址 | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` |
| `MIKA_TRANSLATE_KEY` | 是 | 那个服务的 API Key | `ark-xxxx` |
| `MIKA_TRANSLATE_MODEL` | 是 | 模型名（要能在上面那个地址调通） | `doubao-seed-2-0-mini-260428` |

## 在 Mika 里怎么设

1. 打开「设置 → 环境变量」。
2. 依次新建上面三个变量，值原样填进去。
3. 新建后**重启一次 Mika**，让 App 进程读到新环境。
4. 回到浏览器打开一个外文页，跟 Mika 说「翻译这页」，它先调 `translate` 看通道通不通，再干活。

## 没配也能用

`translate` 发现 `MIKA_TRANSLATE_BASE` 为空时不会报错，而是把正文按自然边界切成编号段落，
标 `mode: segments` 交回来，由 Mika 用对话里当前的主模型自己翻。区别只有三点：

- 用你自己在用的模型，**页面正文会经过对话**（就是你平时聊天那个模型服务商）；
- 长页面会更慢；
- 质量取决于当前主模型。

不想让正文离开你选的通道，就把三个变量配好。

## 常见服务的写法

| 服务 | BASE | MODEL |
|---|---|---|
| 火山方舟 Ark | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | `doubao-seed-2-0-mini-260428` |
| DeepSeek | `https://api.deepseek.com/chat/completions` | `deepseek-chat` |
| 通义千问（兼容模式） | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `qwen-plus` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `glm-4-flash` |
| 本地 Ollama | `http://127.0.0.1:11434/v1/chat/completions` | `qwen2.5` |

## 出错了看什么

`translate` 的返回里 `error` 字段：

- `api_failed` + `message` 带 HTTP 状态码和服务端原话——多半是 Key 无效、模型名不对、余额不足；
- `too_long`——一次超过 40 段，改用 `web_read_page` 的 `offset` 分页读、分两次翻；
- `empty_text`——正文没传进来；
- `mode: segments`——见上面「没配也能用」。

## 隐私

配好变量后，页面上**被翻译的那些字**会发到你指定的那个接口。整页正文不会进 Mika 的对话记录，
也不会发给 Mika 的主模型。
