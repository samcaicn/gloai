## Why

#747 给密钥池接上了动态轮询：`GeminiModel` / `OpenAiCompatModel` 在 HTTP 429、401、403 时回调 `onRateLimit`，`ProviderStore` 接线处一律调 `KeyPoolManager.markRateLimited`（60 秒冷却后自动复活）。但 `KeyPoolManager.markExhausted`（401/403 永久失效，#747 提交信息里写明"Automatically mark rate-limited **or exhausted** keys on HTTP 429, 401, 403"）在生产代码里**零调用**——401/403 的坏 key 只会被冷却 60 秒，到期复活，再次被轮询选中，再次 401，永远循环。

后果：多 key 池里混进一个失效 key（过期/删号/抄错）时，每次轮到它都白烧一次请求往返；60 秒一轮，永不自愈。回调签名 `(key, retryAfterMs) -> Unit` 不带 HTTP 状态，接线处想区分也区分不了。

## What Changes

- `GeminiModel` / `OpenAiCompatModel` 的回调带上 HTTP 状态码：`onRateLimit: ((key: String, retryAfterMs: Long?) -> Unit)?` 改为 `onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)?`（#747 未发版，改名无兼容负担；原名对 401/403 本就是误导）。触发集合仍是 429/401/403，模型内换 key 重试逻辑不变。
- `ProviderStore.buildModel` 接线处按状态分流：429 → `markRateLimited(key, retryAfterMs)`；401/403 → `markExhausted(key)`，把"耗尽"语义真正接上。

## Capabilities

### New Capabilities

- `key-pool-auth-exhaustion`: 密钥池对 401/403（认证失败/封禁）的 key 永久标记耗尽，不再以 60 秒限流冷却的方式反复复活；429 仍按 Retry-After/默认冷却临时避开。

### Modified Capabilities

（无——#747 尚未发版，`key-pooling` 的对外行为在发版前修正，不构成已发布能力的变更。）
