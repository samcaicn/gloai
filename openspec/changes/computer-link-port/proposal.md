# 电脑连接端口可自定义（issue #392）

## Why

部分手机的系统服务占用 8765 端口，Mika 的电脑连接（MCP/CLI）绑定失败就无法使用（issue #392）。设置存储 `McpSettings` 其实已支持 `port` 字段（读取并校验 1024–65535、默认 8765），运行时全链路（服务绑定、`mcp-usb.json` 连接信息、`connect-computer.sh`、`adb forward` 提示、CLI 动态读端口）都已走这个值——唯独没有写入入口：没有 setter、没有设置 UI。补上这最后一环即可。

## What Changes

- 设置 › 连接电脑 › 高级 新增「监听端口」行：点开对话框编辑（1024–65535，恢复默认 8765 的快捷项），保存即生效。
- 保存后：正在监听的服务按新端口热重绑（USB 与网络两种方式都算）；`connect-computer.sh` 按新端口重写；USB 开着时 `mcp-usb.json` 重新发布；前台服务通知里的端口同步更新。
- 端口校验沿用读取侧的既有规则（1024–65535）；不合法输入在对话框里拦下，不落盘。
- 文档（docs/mcp.md 等）补一句端口可在设置里改。

不做：CLI/Skill 侧改动（`mika` 命令已从 `mcp-usb.json` 动态读端口）；中继网关（relay，公网部署，与手机本地端口无关）。

## Capabilities

### New Capabilities
- `computer-link-port`: 电脑连接监听端口的设置、热重绑与脚本/信息发布联动。

### Modified Capabilities

（无既有 specs。）

## Impact

- `:app`
  - `settings/McpSettings.kt`：`port` 可写（`setPort`，范围校验、落盘）
  - `mcp/McpRuntime.kt`：`setPort()`（写设置 + 重写 USB 脚本 + `apply()`）；`sync()` 在端口变化时重绑监听
  - `mcp/ui/McpScreen.kt`：高级区「监听端口」行 + 编辑对话框（`ComputerAccessUi` 增加 `onPortChange`）
  - `res/values*/strings_mcp*.xml`（10 语言）
- `docs/mcp.md`（或 computer.md 相应段落）：一句说明
- 单测：McpSettings 端口存取/校验（app）；`McpUsb` 已有端口透传测试的话补脚本含新端口的断言（core）
