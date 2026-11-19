# 项目经验

| 机制 | 适用信号 | 验证范围 |
| --- | --- | --- |
| [任务目标计数与动作记录](patterns/task-goal-progress.md) | 跨应用任务停在 0/N，或整次只有动作没有分数 | 两次运行区分首轮漏建与后续漏报，脚本覆盖补建、计数与收尾 |
| [回放画面与实际动作关联](patterns/task-playback-frames.md) | 点击后立即返回、同轮多个动作、标记出现在结果图 | 事件与画面关联有单测；原 #348 曾在一台手机上验证 |
| [Compose 真机测试在输入初始化时失败](patterns/espresso-input-initialization.md) | 用例尚未操作界面，Espresso 就报 `InputManager.getInstance` 不存在 | #644 在一台 SDK 37 手机上复现，升级测试依赖后两条用例通过 |
| [缓存命中率的分母](patterns/cache-report-coverage.md) | 混用服务商、缓存字段缺失、旧记录只有累计量 | 混合上报已用单测验证，另在一台手机上验证了脚本会话展示 |
| [实时通知与聊天页任务进度](patterns/promoted-task-notifications.md) | 桌面没有胶囊、一直显示「正在执行」，或任务文字已推进但计数仍为 0 | Android 16 真机验证系统展示；真机见切换 App 后停在「查址」；同名词干扰和 AI 分段停滞经单测复现 |
| [进度清单被模型当成工具调用](patterns/progress-as-tool-call.md) | 首轮出现 `task_progress · 没成功`，后续手机操作仍在继续 | 真机联测捕获 4 次同名调用，清单成功更新；脚本覆盖无效参数与并行调用 |
