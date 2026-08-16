# 升级流程（UPGRADE）

本文档描述标准技能的版本管理：SemVer 规范、灰度发布、回滚、市场元数据。所有能力建在 `cap.skillMarket` / `cap.server` 之上。

## 1. 版本号 — SemVer 规范

`SKILL.md` 的 `version` 字段遵循 [Semantic Versioning](https://semver.org/)：`MAJOR.MINOR.PATCH`。

| 变更类型 | 升哪个位 | 示例 |
|----------|----------|------|
| 不兼容的 API 变更（capabilities inputs/outputs 改了） | MAJOR | `1.2.3` → `2.0.0` |
| 向后兼容的新功能 | MINOR | `1.2.3` → `1.3.0` |
| 向后兼容的 bug 修复 | PATCH | `1.2.3` → `1.2.4` |

`runtime.caps` 里的版本约束用 npm 风格：`cap.cdp@^1.0.0`（兼容 1.x.x）、`cap.flowchart@>=1.0.0 <2.0.0`。

## 2. 检查升级 — cap.skillMarket.checkUpgrade

```js
const r = await cap.skillMarket.checkUpgrade(skillId)
// 返回：
// { ok, hasUpdate, local, remote, changelog }
//   hasUpdate: true 表示远端有新版本
//   local / remote: 版本号字符串
//   changelog: 远端版本的更新日志
```

内部实现：从本地 `_installedSkills` 拿当前版本 → 调 `cap.server.getLatestVersion(skillId)` 拿远端最新版本 → 比对。

## 3. 升级 — cap.skillMarket.upgrade

```js
const r = await cap.skillMarket.upgrade(skillId)
// 返回：{ ok, fromVersion, toVersion } 或 { ok: false, error, local }
```

内部流程：
1. **归档旧版**：`cap.storage.set('skill_archive:<skillId>:<oldVersion>', { meta, flowchart, handler, archivedAt })`
2. **下载新版**：`cap.server.downloadPackage(skillId)` 拿 zip 包
3. **加载新版**：实际生产由 Rust 侧沙箱注入 handler
4. **上报结果**：`cap.server.reportUpgrade(skillId, fromVersion, toVersion, ok, error)`

## 4. 回滚 — cap.skillMarket.rollback

```js
const r = await cap.skillMarket.rollback(skillId)
// 返回：{ ok, fromVersion, toVersion } 或 { ok: false, error: 'no archive' }
```

内部流程：
1. 从 `cap.storage.keys()` 找 `skill_archive:<skillId>:*` 的归档 key
2. 取最新归档版本
3. 用归档的 meta/flowchart/handler 覆盖当前 `_installedSkills` 条目
4. 上报：`cap.server.reportUpgrade(skillId, fromVersion, archivedVersion, true, 'rollback')`

## 5. distribution.rollout 字段语义

`SKILL.md` 的 `distribution.rollout` 字段控制灰度发布：

```yaml
distribution:
  channel: "stable"        # stable | beta | nightly
  minAppVersion: "0.5.0"   # 兼容的最低 App 版本
  maxAppVersion: "2.0.0"   # 兼容的最高 App 版本（exclusive）
  rollout:
    percentage: 100        # 0-100，灰度比例
    targetUsers: []        # 白名单用户 id 列表（不受 percentage 限制）
```

| 字段 | 说明 |
|------|------|
| `channel` | 发布通道；`nightly` 自动构建，`beta` 预览，`stable` 正式 |
| `minAppVersion` / `maxAppVersion` | App 版本兼容区间；不兼容时市场不展示给用户 |
| `rollout.percentage` | 灰度比例，0-100；服务器按用户 id 哈希取模判断是否下发 |
| `rollout.targetUsers` | 白名单用户 id；这些用户无视 percentage 限制，总是能拿到新版 |

## 6. 兼容性声明

`runtime.caps` 声明技能依赖的 cap 版本约束：

```yaml
runtime:
  caps:
    - "cap.cdp@^1.0.0"
    - "cap.recognize@^1.0.0"
    - "cap.control@^1.0.0"
    - "cap.flowchart@^1.0.0"
```

加载技能时，`cap.skillMarket.load` 会检查当前 App 提供的 cap 版本是否满足约束；不满足则拒绝加载并返回 `{ ok: false, error: 'incompatible caps' }`。

## 7. 服务器灰度策略

服务器侧（见「服务器需求.md」）按以下顺序判断是否给某用户下发某版本：

1. 用户在 `rollout.targetUsers` 白名单里 → 直接下发
2. 否则对 `userId` 做哈希取模 `hash(userId) % 100 < rollout.percentage` → 下发
3. 否则不下发（用户继续用旧版）

灰度比例从 0 逐步拉到 100，观察 `cap.server.reportUpgrade` 上报的成功率，发现回归就回滚。

## 8. 回滚到归档旧版

`cap.skillMarket.upgrade` 每次升级前都会归档旧版到 storage，key 格式 `skill_archive:<skillId>:<oldVersion>`。`rollback` 取最新归档恢复。

如果需要回滚到非最近版本，可手动从 storage 读指定版本的归档 key：

```js
const archived = cap.storage.get('skill_archive:com.tupautochrome.skills.template:1.0.0')
// archived = { meta, flowchart, handler, archivedAt }
```

## 升级速查

| 场景 | 动作 |
|------|------|
| 检查是否有新版 | `cap.skillMarket.checkUpgrade(skillId)` |
| 升级到最新 | `cap.skillMarket.upgrade(skillId)` |
| 回滚到上一版 | `cap.skillMarket.rollback(skillId)` |
| 查已装列表 | `cap.skillMarket.listInstalled()` |
| 查某技能是否已装 | `cap.skillMarket.isInstalled(skillId)` |
| 上报升级结果 | `cap.server.reportUpgrade(skillId, from, to, ok, err)` |
