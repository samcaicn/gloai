# 项目经验

| 机制 | 适用信号 | 验证范围 |
| --- | --- | --- |
| [回放画面与实际动作关联](patterns/task-playback-frames.md) | 点击后立即返回、同轮多个动作、标记出现在结果图 | 事件与画面关联有单测；原 #348 曾在一台手机上验证 |
| [Compose 真机测试在输入初始化时失败](patterns/espresso-input-initialization.md) | 用例尚未操作界面，Espresso 就报 `InputManager.getInstance` 不存在 | #644 在一台 SDK 37 手机上复现，升级测试依赖后两条用例通过 |
