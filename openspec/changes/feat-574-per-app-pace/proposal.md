# 操作节奏支持按应用单独设置（#574）

## Why

「操作节奏」只有全局一档（快/标准/像人），而 App 知识里另有一份用户看不见、改不了的 `pace: careful` 名单（8 个内置 App）：风控严的 App 在全局「快」下反而被放快，用户也无法给某个 App 单独放慢或放快。issue #574 要求把这份隐藏判定变成可见、可编辑、可撤销的按应用列表，未列入的 App 跟随全局。

## What Changes

- 新增按应用操作节奏覆盖：每个 App 可单独设「快 / 标准 / 像人」，未覆盖的 App 跟随全局档位；生效档位 = 覆盖值 ?: 全局（任务开始时快照，与现有「下一个任务起生效」一致）。
- 内置 `pace: careful` 的 8 个 App 在列表首次初始化时预置为「像人」，之后运行时不再查 App 知识的 pace（删掉预置项即回到跟随全局，不再有用户覆盖不了的例外）；站点知识（网页）的 pace 判定不变。
- 设置 › 干活时 › 操作节奏下新增「按应用设置」入口：列表展示各 App 当前档位，可增（从已安装 App 里选，带搜索）、可改档、可删。
- core 节奏引擎支持按应用三态：快 = 该 App 不停留、打字一次写入、无屏数上限；标准/像人 = 现有正常/careful 停留与打字。屏数上限（page_limit 闸）只跟「覆盖为像人」走——全局像人不再隐式给所有 App 加上限（保持现状：上限只给限制浏览快慢的 App）。
- 评测钩子设置了操作节奏时保持确定性：忽略按应用覆盖，全部按钩子档位执行。

## Capabilities

### New Capabilities

- `per-app-pace`: 按应用解析操作节奏——覆盖存储与初始化、生效档位/上限判定、与全局档位及评测钩子的合成规则。

### Modified Capabilities

（无现有规格目录，不适用。）

## Impact

- core：`AppPacing`/`AppDwell` 的 pace 入参从 `PlacePace` 换为按应用三态 + 上限判定分离（`PlacePace` 本身不动，网页侧不受影响）；`PhoneCapability`/`TaskTools` 增加对应透传参数；`GuideCatalog` 增加 careful App 清单查询；`OperatingPace` 增加按档位的打字节奏辅助。
- app：新增覆盖存储（SharedPreferences）、`TaskRun` 三处接线改为按应用解析、设置页新增入口与按应用子页、新字符串 ×11 语言。
- 行为变化：全局「快」下覆盖为「像人」的 App 会被放慢（正是 issue 要的）；App 知识新增 careful App 不再自动进已有用户的列表（issue 明确接受）。
