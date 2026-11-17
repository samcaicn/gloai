# 插件运行时(P1b)设计文档

日期:2026-11-18
状态:待批准(批准后进 writing-plans 拆实现计划)

## 意图(为什么做)

让 Mika **可插拔**:第三方(以及 Mika 自己)通过安装一个文件夹(zip),就能给 Mika 增加
**工具**(会干活的脚本)、**技能**(说明书)、**提示词改写**(调人设),而不用改 Mika 源码、不用重新打包 APK。

这是生态接口,不是一次性功能——它决定 Mika 以后能不能"装出来的能力"壮大。

## 目标 / 非目标 / 反目标

### 目标
1. 插件能声明并运行 shell 工具(`manifest.tools[].script` → 模型可调用的工具)。
2. 插件能改写系统提示词(`promptTransform` 钩子,按模型作用域,超时回退)。
3. 插件管理 UI 从 P1a 扩到:装前看工具+风险、启停、卸载、详情、提示词规则查看/编辑/还原。
4. 插件配置:环境变量(让 translate 这类要 key 的插件能用)。
5. 两个真实插件跑通:`mika.translate`(页面翻译)、`com.mika.purge-lite`(提示词体检),作为运行时的"一号客户"验证它真能用。
6. 干净对齐 Mika 现有架构:复用沙箱的命令运行,走能力/工具注册表,不另起一套。

### 非目标(这次不做)
- 工作流引擎、本地模型、模型列表 UI、侧栏分类、音视频预览、`shell_run_command`——独立决策,后置。
- 重型运行时沙箱/权限隔离(见"安全"一节)。

### 反目标
- 不把运行时做成"给 shell 脚本兜底的逃生通道",而是对齐 Mika 的能力/工具模型。
- 不让插件的 shell 实现成为 Mika 核心功能的长期形态(translate 先插件跑通,原生化留作后续优化)。

## 约束
- 只收 `.sh`(App 进程没有 python3/node)。
- 脚本跑在 App 进程内(能读私有目录;也因此能读到对话/记忆/模型密钥——装前必须诚实披露)。
- 10 种 UI 语言:每个新字符串都要 10 份翻译。
- 通用安卓(AOSP),不针对任何品牌。
- 清掉外部项目字样("Mika Ultra"、"dsh-purge")。
- 真机验证(模拟器只是占位)。

## 方案(为什么选 C)

三条路:A 找 kkkaqi 要 r102 源码 / B 反编译 APK / C 自己写。
**选 C**:kkkaqi 的"Ultra"树已与主线分叉很深,c从里面 cherry-pick 插件系统不省事;而运行时该做什么
**已有完整规格**(两个插件的 manifest 把 `tools[].script`/`timeoutSec`/`risk`/`inputSchema`/`permissions`/`promptTransform`
全写死了,changelog 那 9 条坑就是边界情况),且"跑 `.sh`/掐超时/读 stdout+stderr" Mika 沙箱早就在做。
自己写才对齐 harness v2 的"能力 + 工具 + 注册表",不把 fork 的东西硬贴上来。

## 架构(怎么做的关键决定)

### 1. 执行器(核心)
- 把已有的 `PluginToolDecl`(数据模型已在 `PluginManifest.kt`)包成一个 `AgentTool`。
- `execute()` 做:参数 JSON 写临时文件 → `sh <插件目录>/scripts/xx.sh <argsFile>`(同时设 `MIKA_ARGS_FILE`
  环境变量,和插件脚本约定一致)→ 读 stdout(+stderr)当 JSON 结果 → 套 `ToolExecution`。
- 复用沙箱的命令运行/超时/输出截断逻辑(`SandboxTools.kt`),不是新造。
- 工具命名:`plugin_<插件id去点>_<工具名>`(如 `plugin_mika_translate_translate`),避免和核心工具撞名。

### 2. 提示词钩子 `promptTransform`
- `PluginManifest` 加 `promptTransform: String?` 字段。
- 在系统提示词拼好后、发模型前,对每个声明了 `promptTransform` 的启用插件跑一次:stdin 进原提示词,stdout 出改写后。
- 3 秒超时回退原文;按(内容 + 模型名)缓存;`MIKA_MODEL` 传模型名(支持 `[模型片段]` 作用域)。
- 注入面已知,功能前置接受,记为"安全后置"待办。

### 3. 插件配置(环境变量)
- 新增"设置 → 环境变量"页(名字=值列表),本地存储。
- 跑插件脚本 / 提示词钩子时注入进程环境。
- translate 因此能读 `MIKA_TRANSLATE_BASE/KEY/MODEL/TARGET`;没配就退回"切段交主模型翻",不报错。
- 未来:`manifest.settings[]` 做 per-plugin 作用域,现在不做。

### 4. 插件管理 UI
- 扩展现有 `PluginsScreen`:装 zip 前弹窗列工具+风险;启停/卸载/详情;详情含提示词规则"查看/编辑/还原"。
- 诚实披露文案:"能读到对话、记忆和模型密钥"。

### 5. manifest 校验放开
- `PluginManifest.validate()` 接受 `tools`/`permissions`/`promptTransform`。
- `permissions` 白名单:`run-scripts`(工具)、`rewrite-prompt`(钩子)。
- `tools[].script` 必须是 `.sh`、路径必须落在包内。

### 6. 两个插件移植
- `mika.translate` v1.1.0:源码已到手,直接抄,清 "Mika Ultra" 字样。
- `com.mika.purge-lite` 0.5.3:源码已到手,直接抄,清 "Mika Ultra"、"dsh-purge" 字样;权限对齐(run-scripts + rewrite-prompt)。
- 打包成可安装 zip 放进仓库,**不预置进 APK**(保持 APK 干净,和 fork 的决定一致)。

## 安全(功能前置,安全后置——明确的范围)

- 现在只做:权限声明 + 装前诚实披露 + 工具 `risk` 分级标记(read/write/external/rewrite-prompt)。
- **不做**(后置):运行时按 risk 拦截、进程隔离、密钥不进插件环境等。
- 明写为"安全后置"待办,不假装做了。

## 我自己做的决定(可翻)
1. 翻译**先做插件**(狗粮运行时、验证它真能用),原生化后置。
2. 工具命名 `plugin_<id>_<name>`。
3. 环境变量用**全局设置页**,不用 per-plugin `settings[]`(后者后置)。
4. `promptTransform` 纳入,注入面接受(安全后置)。
5. 两个插件打进仓库作可安装 zip,不预置 APK。

## 留给实现计划的(接下来 writing-plans 拆)
- 具体文件清单、改动点(执行器、钩子挂点、UI、校验、环境变量 store)。
- 测试:JVM(校验/参数文件/结果解析)+ 真机(装插件、translate 真跑、purge-lite 改提示词)。
- i18n 10 语言、版本号(0.1.N 递增)。
