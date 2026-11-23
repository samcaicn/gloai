# 插件示例

这两个示例都以飞书为例，分别对应插件系统的两类形态：

| 目录 | 形态 | 现在能做什么 | 依赖 |
|---|---|---|---|
| [`com.example.feishu/`](com.example.feishu/) | capability pack（能力包） | `manifest.json` 是纯技能插件，P1a 就能装；`manifest.full.json` 加了一个 webhook 工具，等 P1b | P1a（技能）；P1b（工具）、P3（secret） |
| [`com.example.feishu-connector/`](com.example.feishu-connector/) | connector（外部连接器） | 只是 P4 的 manifest 样例，展示安装指引/命令/状态字段；不运行 App 内代码 | P4，以及官方飞书连接器 PR #160 合入 |

## 和官方飞书连接器的关系

官方飞书连接器跑在电脑上（`mika-feishu` / `/api/v1`），负责把飞书私聊转成 Mika 任务、再把结果发回飞书。按规格，它属于 `kind: "connector"`，**不把代码搬进 App**（对应插件计划的 P4 阶段）。

`com.example.feishu/` 是另一条路：一个装在 App 里的能力包，提供飞书操作技能，并可选用自定义机器人 webhook 直接发消息。两者可以并存：

- 私聊消息由电脑端连接器接进来；
- 定时提醒、群通知这类「主动发一条」用能力包里的 webhook 工具；
- 读消息、回消息仍走手机上的飞书 App 或连接器。

## 安装技能版（当前 P1a）

1. 生成 zip：

   ```bash
   python examples/plugins/com.example.feishu/build.py
   ```

   产物在 `examples/plugins/com.example.feishu/dist/com.example.feishu-skill-0.1.0.zip`。

2. 在 App 里打开 设置 › 高级 › 插件 › 安装插件，选这个 zip。
3. 启用后，下一次任务里 `skill_search` 能搜到 `com.example.feishu/feishu`，`skill_read` 能读，正文前会带「第三方插件技能」标记。

## 工具版（等 P1b）

`build.py` 同时生成 `com.example.feishu-full-0.2.0.zip`，它的 manifest 带 `tools`/`permissions`。**当前 P1a 会以 `only_skills_supported` 拒绝安装，这是预期的**；P1b 合入后才会出现 `mcp_com_example_feishu__send` 工具。

## connector 样例（等 P4）

`com.example.feishu-connector/manifest.json` 里的 `installDocs`/`command`/`statusApi` 来自计划中的 P4 形状；等 PR #160 合入后要按它实际的 `docs/feishu.md` 和 `/api/v1` 修正。P1a/P1b 的校验器目前只接受 `kind: "capability"`，所以它也不能安装。

## 安全约定

- 技能和工具都不能改 App 全局设置、模型/Key、个人技能或其他插件的数据。
- webhook、secret 不应写进日志或对话；示例工具从参数读取，P3 会改成插件 secret。
- 示例只调用飞书官方自定义机器人域名；脚本不重试、不跟随任意跳转。
