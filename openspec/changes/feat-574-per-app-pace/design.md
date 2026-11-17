# 设计：操作节奏按应用覆盖（#574）

## Context

现状链路（explore 结论）：

```
设置›干活时›操作节奏（单选 快/标准/像人） FeatureFlags.operatingPace()（prefs "operating_pace"）
  └─ TaskRun.kt:699  pace = evalHooks.operatingPace ?: 设置值
       ├─ pace.appPacing() → AppPacing 实例（FAST→Off；STANDARD→正常两套 limits；HUMAN→全 CAREFUL limits）
       │    └─ PhoneCapability → AppDwell(pacing, pace=lambda)
       │         pace lambda = PlacePace.of(catalog.appPace(pkg))  ← App 知识 APP.md `pace: careful`
       │         CAREFUL → careful limits（停更久、按字数读、屏数上限 24、打字更慢）
       └─ pace.typing(placePace) → TypingPacing
```

痛点：careful 名单（8 个内置 App）对用户不可见不可改；全局「快」时 `AppPacing.Off` 让 careful 判定一并失效；单 App 无法独立放慢/放快。

先例：`AppAccessStore`（JSON 文件 + StateFlow）是按应用设置的存储先例；`AppAccessScreen` 是按应用列表页先例（`launchableApps` 列 App + 搜索 + ListRow/MikaDialog）；`SettingsScreen` 用 `SettingsPage` 枚举路由子页。

## Goals / Non-Goals

**Goals：**
- 每个 App 可独立设 快/标准/像人；未覆盖跟随全局；内置 careful 名单变成列表初始内容（可见可改可删）。
- 手机的停留、打字、屏数上限三件事按「生效档位」解析；评测钩子优先且确定。
- 改动集中在 core 节奏引擎入参与 app 设置/接线，网页（sitePace）不动。

**Non-Goals：**
- 不做按任务类型/时间段/网络环境的更细维度（issue 明说可后置）。
- 不改网页 PagePacing/站点 careful 判定。
- 不改手势本身的节奏（GesturePacing 三档都像人，与本设置正交）。

## Decisions

### D1：生效档位解析——「覆盖值 ?: 全局」，快照时机与全局一致

任务开始时解析一次：`effective(pkg) = overrides[pkg] ?: global`。与全局档位「下一个任务起生效」一致，任务中改设置不影响当次。评测钩子设了档位时 overrides 视为空（确定性）。

### D2：三档语义全走 per-app 解析，AppPacing 实例不再按全局档位变形

实例统一 `AppPacing.human(random)`（正常 limits + careful limits 两套），全局档位只进 `effective()`：
- effective=FAST → 该 App 不停留（引擎的 off）、打字 Instant、不计屏不设上限；
- effective=STANDARD → PlacePace.NORMAL（正常 limits）；
- effective=HUMAN → PlacePace.CAREFUL（careful limits + 读时间 + 打字更慢）。

全员 FAST（全局 FAST 且无覆盖）时保持 `AppPacing.Off` 快路径，行为与今天一致。
替代方案（保留 `OperatingPace.appPacing()` 的实例变形）被否：全局 HUMAN + 单 App 覆盖 STANDARD 无法用一套实例 limits 表达。

### D3：屏数上限与「慢」解耦——上限只跟「覆盖为像人」走

现状：全局 HUMAN 时所有 App 用 CAREFUL limits，但只有 knowledge-careful App 计屏受上限。若把 effective=HUMAN 一律映射 PlacePace.CAREFUL，全局 HUMAN 会让所有 App 突然受 24 屏上限（行为回归）。因此 core 侧把「节奏快慢」与「是否限屏」拆成两个入参：
- 节奏：`AppTempo`（新三态 FAST/NORMAL/CAREFUL，仅 phone 域；`PlacePace` 保持两态不动，网页侧继续用）；
- 限屏：`capped: (String) -> Boolean`，由 app 层给 `overrides[pkg] == HUMAN`。

seed 后 capped 只来源于用户覆盖，内置 8 App 预置 HUMAN → 行为与今天 STANDARD 下一致；删除预置即无上限（符合「删掉=跟随全局」）。

### D4：存储用 SharedPreferences（JSON 串），lazy seed

`FeatureFlags` 同一 prefs 文件新增 `app_pace_overrides`（`{"pkg":"HUMAN",...}` JSON）。首次读取（值为空）时把 `GuideCatalog.carefulApps()`（新增查询）写入并返回，之后运行时不再查知识。列表预计 <20 项，prefs 足够；不做 AppAccessStore 式文件存储（那份有复杂 classifications 结构，这里没有）。写操作 `commit()`（与 FeatureFlags 现有写法一致）。

### D5：core 接线保持默认行为兼容

`TaskTools`/`PhoneCapability` 的 per-app pace 参数默认值仍从 App 知识解析（`AppTempo.of(skills?.appPace(pkg))`、capped 同源），core 单测/CLI 默认路径不变；`TaskRun` 显式传入按应用解析结果。`AppDwell.pace` 参数类型 `(String) -> PlacePace` 改为 `(String?) -> AppTempo`（FAST 在引擎内表达，不再需要独立 off lambda）。

### D6：UI——设置子页 + 添加对话框

`SettingsPage` 加 `AppPace`，从 `DuringWorkScreen` 操作节奏组下新入口进入。子页复用 AppAccessScreen 的形态：覆盖项 ListRow（label + 档位，点击弹 快/标准/像人/删除 四选），「添加」进 MikaDialog（launchableApps + 搜索，排除已覆盖项）。新字符串补齐全部 11 个语言目录。

## Risks / Trade-offs

- [App 知识未来新增 careful App 不再自动进已有用户列表] → issue 作者明确接受（「不再存在用户无法覆盖的例外」）；新装用户仍会 seed 最新名单。
- [prefs JSON 手写/损坏解析失败] → 解析失败按空处理（下次读取会重新 seed），不崩溃。
- [AppDwell/AppPacing 签名变更波及现有单测] → 同步改 AppPacingTest/AppDwellTest 用例语义（tempo/capped），core 全量测试把关。
- [M3 RadioRow 在部分 ROM 上 adb 不可点（AGENTS 已知坑）] → 真机验证以直写 prefs + 任务行为证据为准，辅以 UI 截图。

## Migration Plan

无存量数据迁移：prefs 无键即首次 seed。回滚 = 撤销提交，prefs 里多出的键无人读取，无副作用。

## Open Questions

无——issue 已给出可接受简化范围，本设计覆盖其完整诉求（三档、可增改删、内置预置）。
