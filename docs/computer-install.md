# 电脑端 CLI / Skill：安装、升级和卸载

[English](computer-install.en.md) · [连接与任务命令](cli.md) · [MCP 配置](mcp.md)

从电脑使用 Mika 的总入口是[从电脑使用 Mika](computer.md)：在那里选好用 CLI + Skill 还是 MCP、经 USB 还是 Wi-Fi / VPN 连接。这一页只讲 CLI 和 Skill 怎么装、怎么升级和卸载；用 MCP 客户端的不用看本文，按 [MCP 指南](mcp.md)配置。

Agent 想走固定的最短路径：见 [Agent 快速路径](agent-quickstart.md)。

## 开始之前

- 让能执行命令、支持 Skill 的电脑 Agent 用 Mika：装 [Skill](#skill安装)，它自带 CLI 脚本，不必另装 CLI。
- 在终端或脚本里派任务：装 [CLI](#cli安装)，使用 `mika` 命令。

手机端的前提：装好 APK（[Releases](https://github.com/wangzan101/Mika/releases/latest)）、打开过一次 Mika、配好模型。**只让 Mika 聊天、上网和处理文件时，可以不开无障碍、不启动手机助手，安卓 11 也能用**；要操作手机 App，才需要按[设置指南](setup-guide.md)完成手机操作设置（安卓 12+）。电脑端工具的安装和升级不会安装或更新手机上的 APK。

下文的 `<tag>` 指 [最新 release](https://github.com/wangzan101/Mika/releases/latest) 的版本标签（页面标题里的 `v0.1.x`）。电脑端和手机上的 APK 尽量用同一个版本。

## CLI：安装

需要 Python 3.10+、Git 和 pipx；ADB 连接还需要 Android platform-tools。安装：

```bash
pipx install 'git+https://github.com/wangzan101/Mika.git@<tag>#subdirectory=companions/mika'
mika --version
```

pipx 里的包名是 `mika-phone-cli`，命令是 `mika`。CLI 有自己的版本号，和 APK 版本不同。已有仓库检出时，也可以在仓库根目录 `pipx install ./companions/mika`，或者不安装、直接运行 `python3 companions/mika/scripts/mika_cli.py`（Windows 用 `py -3`）。

## CLI：升级、回退和卸载

升级换成新的 `<tag>`，回退换成原来的 `<tag>`，用 `--force` 覆盖安装：

```bash
pipx install --force 'git+https://github.com/wangzan101/Mika.git@<tag>#subdirectory=companions/mika'
```

`pipx upgrade` 不会切换到别的 tag，换版本时用上面的命令。卸载：

```bash
pipx uninstall mika-phone-cli
```

## Skill：安装

Skill 就是仓库里的 `companions/mika` 文件夹：`SKILL.md` 告诉 Agent 怎么用，`scripts/mika_cli.py` 是自带的 CLI，只依赖 Python 3.10+（ADB 连接另需 platform-tools）。

1. 在 [最新 release](https://github.com/wangzan101/Mika/releases/latest) 下载 **Source code (zip)** 并解压。
2. 把其中**整个** `companions/mika` 文件夹复制到你的 Agent 的技能目录（位置见该 Agent 自己的说明）。结果应该是 `<技能目录>/mika/SKILL.md`，不要多嵌套一层 `mika`。
3. 检查自带脚本能运行：

   ```bash
   python3 <技能目录>/mika/scripts/mika_cli.py --version   # Windows 用 py -3 代替 python3
   ```

4. 重开 Agent 会话，确认它能看到名为 `mika` 的 Skill。

电脑上已有 `mika` 命令时，Skill 会优先用它；更新 Skill 不会更新全局 CLI，两者各自维护。

## Skill：升级、回退和卸载

- **升级或回退**：把旧的 `mika` 文件夹移到技能目录之外备份，再复制目标版本的整个 `companions/mika` 文件夹，不要在旧文件夹上合并复制。
- **卸载**：只删除技能目录里的 `mika` 文件夹。

改完都要重开 Agent 会话。

## 装好之后：连接检查

只装了 Skill 时，下面的 `mika` 换成 `python3 <技能目录>/mika/scripts/mika_cli.py`（Windows 用 `py -3`）。

经 USB 或已连接的无线 ADB：

```bash
mika devices --json
mika connect --device SERIAL --json
mika doctor --device SERIAL --json
```

直接经 Wi-Fi／私有网络（先在手机的 设置 › 连接电脑 里开启网络访问）：

```bash
mika --url http://PHONE:8765 --token-file /path/to/private-token-file doctor --json
```

**`connect` 和 `doctor` 成功不等于任务做成。** 它们只说明接口连得上、能读到设备状态；`doctor` 把模型配置记为 unverified，也不说明 Agent 已加载 Skill。要验证真实任务，用 `mika task run` 派一个简单任务，再用 `mika task wait` 看到 `done` 并核对结果，见[任务命令](cli.md#任务命令)。

| 现象 | 处理 |
| --- | --- |
| `incompatible_api` | 手机 APK 太旧。报错会写明手机版本与缺失的能力：更新 APK，或按报错去掉对应参数重试（如 `--request-id`，去掉后任务照常提交，只是丢失断线找回）。 |
| `stale_connection` | 手机重启过，重新运行 `connect`。 |
| `device_selection` | 不止一台手机在线，用 `mika devices` 查到序列号后加 `--device SERIAL`。 |
| `connection_info` | 读不到 Mika 的连接信息（手机锁着、还没打开过 Mika 等）：解锁手机、打开一次 Mika 后再运行 `connect`。 |
| `adb` / `adb_unavailable` | 确认 adb 在 PATH 里、手机在 `adb devices` 里显示为 `device`，在手机上打开一次 Mika 后再运行 `connect`。 |
| Agent 看不到 Skill | 检查是否多嵌套了一层文件夹、`scripts/` 是否一起复制，然后重开会话。 |

卸载电脑端工具不会删除手机数据，也不会关闭手机上的电脑连接。要停止远程访问，在手机的 设置 › 连接电脑 里关闭对应开关。
