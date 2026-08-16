# 升级流程（UPGRADE）

本文档描述 trace-auto 技能的版本管理：SemVer 规范、灰度发布、回滚、市场元数据。所有能力建在 `cap.skillMarket` / `cap.server` 之上。

## 1. 版本号 — SemVer 规范

trace-auto 的 `SKILL.md` 的 `version` 字段遵循 [Semantic Versioning](https://semver.org/)：`MAJOR.MINOR.PATCH`。当前版本 `6.0.0`。

| 变更类型 | 升哪个位 | trace-auto 示例 |
|----------|----------|-----------------|
| 不兼容的 API 变更（capabilities inputs/outputs 改了） | MAJOR | `6.0.0` → `7.0.0`：删除 v5 旧版兼容层 |
| 向后兼容的新功能 | MINOR | `6.0.0` → `6.1.0`：新增断点管理动作 |
| 向后兼容的 bug 修复 | PATCH | `6.0.0` → `6.0.1`：修复 stuck 判断阈值 |

`runtime.caps` 里的版本约束用 npm 风格：`cap.cdp@^1.0.0`（兼容 1.x.x）。trace-auto 声明了 13 个 cap 依赖（见 `SKILL.md`）。

## 2. 检查升级 — cap.skillMarket.checkUpgrade

```json
{ "action": "check_upgrade" }
{ "action": "check_upgrade", "skillId": "com.tupautochrome.skills.trace-auto" }
```

handler 内部：

```js
return await cap.skillMarket.checkUpgrade(params.skillId || FLOWCHART.skillId)
```

返回：

```js
{ ok, hasUpdate, local: '6.0.0', remote: '6.1.0', changelog }
```

内部实现：从本地 `_installedSkills` 拿当前版本 → 调 `cap.server.getLatestVersion(skillId)` 拿远端最新版本 → 比对。

## 3. 升级 — cap.skillMarket.upgrade

```json
{ "action": "upgrade" }
```

内部流程：
1. **归档旧版**：`cap.storage.set('skill_archive:com.tupautochrome.skills.trace-auto:6.0.0', { meta, flowchart, handler, archivedAt })`
2. **下载新版**：`cap.server.downloadPackage(skillId)` 拿 zip 包
3. **加载新版**：实际生产由 Rust 侧沙箱注入 handler
4. **上报结果**：`cap.server.reportUpgrade(skillId, '6.0.0', '6.1.0', true, null)`

## 4. 回滚 — cap.skillMarket.rollback

```json
{ "action": "rollback" }
```

内部流程：
1. 从 `cap.storage.keys()` 找 `skill_archive:com.tupautochrome.skills.trace-auto:*` 的归档 key
2. 取最新归档版本（如 `6.0.0`）
3. 用归档的 meta/flowchart/handler 覆盖当前 `_installedSkills` 条目
4. 上报：`cap.server.reportUpgrade(skillId, '6.1.0', '6.0.0', true, 'rollback')`

## 5. distribution.rollout 字段语义

trace-auto 的 `SKILL.md` 声明：

```yaml
distribution:
  channel: "stable"        # stable | beta | nightly
  minAppVersion: "0.5.0"   # 兼容的最低 App 版本
  maxAppVersion: "2.0.0"   # 兼容的最高 App 版本（exclusive）
  rollout:
    percentage: 100        # 0-100，灰度比例
    targetUsers: []        # 白名单用户 id 列表
```

trace-auto 当前 `percentage: 100` 表示全量发布。灰度阶段可先设 `percentage: 10` + `targetUsers: [内部测试用户 id]`。

| 字段 | trace-auto 值 | 说明 |
|------|---------------|------|
| `channel` | `stable` | 正式通道（trace-auto 已稳定） |
| `minAppVersion` | `0.5.0` | 低于此版本的 App 不展示 trace-auto |
| `maxAppVersion` | `2.0.0` | 高于等于此版本的 App 不展示（需重新验证兼容性） |
| `rollout.percentage` | `100` | 全量下发 |
| `rollout.targetUsers` | `[]` | 无白名单（全量时无需白名单） |

## 6. 兼容性声明

trace-auto 的 `runtime.caps` 声明 13 个 cap 依赖（完整列表见 `SKILL.md`），用 npm 风格版本约束（如 `cap.cdp@^1.0.0`）。

加载时 `cap.skillMarket.load` 检查 App 提供的 cap 版本是否满足约束；不满足则拒绝加载并返回 `{ ok: false, error: 'incompatible caps' }`。

**注意**：v6 依赖 `cap.recognize` / `cap.control` / `cap.flowchart` / `cap.skillMarket` 等新 cap，旧版 App（< 0.5.0）会加载失败。

## 7. 服务器灰度策略

服务器侧（见「服务器需求.md」）按以下顺序判断是否给某用户下发 trace-auto 某版本：

1. 用户在 `rollout.targetUsers` 白名单里 → 直接下发
2. 否则对 `userId` 做哈希取模 `hash(userId) % 100 < rollout.percentage` → 下发
3. 否则不下发（用户继续用旧版）

灰度比例从 0 逐步拉到 100，观察 `cap.server.reportUpgrade` 上报的成功率，发现回归就回滚。

## 8. 回滚到归档旧版

`cap.skillMarket.upgrade` 每次升级前都会归档旧版到 storage，key 格式 `skill_archive:com.tupautochrome.skills.trace-auto:<oldVersion>`。`rollback` 取最新归档恢复。

如果需要回滚到非最近版本（如从 6.1.0 回滚到 6.0.0 而不是 6.0.1），可手动从 storage 读指定版本的归档 key：

```js
const archived = cap.storage.get('skill_archive:com.tupautochrome.skills.trace-auto:6.0.0')
// archived = { meta, flowchart, handler, archivedAt }
```

## 升级速查

| 场景 | 动作 |
|------|------|
| 检查是否有新版 | `check_upgrade` |
| 升级到最新 | `upgrade` |
| 回滚到上一版 | `rollback` |
| 查已装列表 | `cap.skillMarket.listInstalled()` |
| 查 trace-auto 是否已装 | `cap.skillMarket.isInstalled('com.tupautochrome.skills.trace-auto')` |
| 上报升级结果 | `cap.server.reportUpgrade(skillId, from, to, ok, err)` |

## v6 升级注意事项

从 v5 升级到 v6.0.0 的破坏性变更：
- frontmatter 从简单 `name/description/version` 升级为标准格式（含 `id` 反域名 + `capabilities` + `runtime.caps` + `distribution` + `signing`）
- `index.js` 移除 `_control` / `cap.recognize` / `cap.flowchart` 临时桩，改用 `capabilities.js` 标准能力
- 新增 `lifecycle` / `debug` 三段式导出
- 新增断点管理动作（`add_breakpoint` / `remove_breakpoint` / `clear_breakpoints`）
- 新增升级管理动作（`check_upgrade` / `upgrade` / `rollback`）

v5 旧版动作（CDP 检测 / 页面读取 / 页面操作 / 条件回复）通过 `_legacyAction` 完全保留，向后兼容。
