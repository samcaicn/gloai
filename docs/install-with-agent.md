# 用电脑上的 AI 助手安装 Mika

简体中文 | [English](install-with-agent.en.md)

这份文档是写给**电脑上能执行命令的 AI 助手**看的：手机用 USB 连着电脑，助手照着下面的步骤，用 adb 把 Mika 装到手机上并做好设置。人也可以照着做，每条命令都能直接复制。

本指南首先处理手机 APK。手机已经设置好、只需电脑接入时，直接按 [CLI / Skill 安装指南](computer-install.md)或 [MCP 指南](mcp.md)配置所选入口，无需重做手机安装和授权。下面第 10 步也只在用户要求电脑访问时执行。

如果你是用户：把手机用数据线连上电脑，在手机上打开 USB 调试（开发者选项里，本文没写到的开关不要动，有的会清除手机数据），然后把这句话发给电脑上的 AI 助手：

```
请按 https://github.com/wangzan101/Mika/blob/main/docs/install-with-agent.md 的步骤，帮我把 Mika 安装到通过 USB 连着的安卓手机上。
```

如果你是 AI 助手：先读完「规则」，需要安装和设置手机时再从第 1 步开始；仅需电脑接入时按上面的入口执行。

## 规则

1. **只安装和设置 Mika。** 只做本文写到的事，不顺手做别的。
2. **不碰无关的系统设置。** 不改锁屏、屏幕超时、显示、声音、网络、开发者选项里的其他开关等任何本文没写到的设置。
3. **不卸载任何手机 App，不清除手机应用数据。** 连 Mika 自己也不行。需要卸载时，说明原因，让用户自己决定、自己动手。
4. **不碰用户的模型 API Key。** 不向用户索要，不替用户输入，不通过 adb 发送。Key 由用户在 Mika 里自己填。
5. **需要在手机上点的时候，停下来告诉用户。** 说清楚屏幕上会出现什么、该点哪个按钮，等用户说好了再继续。不要用 adb 模拟点击或输入来替用户确认系统安全弹框，包括允许 USB 调试、允许在此网络上使用无线调试。
6. **每一步先用用户的语言简短说明要做什么、为什么。** 命令失败时，把错误原文给用户看，再说可能的原因。
7. 下面的命令按类 Unix 的 shell 写（macOS、Linux、Windows 上的 WSL 或 Git Bash）。在 Windows 的 PowerShell 里，按同样的意思改写即可。

下文把包名写作 `com.wangzan101.mika`。

## 第 1 步：检查前提条件

### 1.1 adb

```
adb version
```

成功：输出 `Android Debug Bridge version ...`。

找不到 adb 时，告诉用户去安卓开发者网站下载「SDK Platform-Tools」（有 Windows、macOS、Linux 三个版本），解压后把目录加进 PATH；也可以用系统自带的包管理器安装 platform-tools。装好后重新检查。

### 1.2 手机已连接、已授权

```
adb devices
```

成功：列表里**正好一台**设备，状态是 `device`。

- 状态是 `unauthorized`：请用户看手机屏幕，在「允许 USB 调试吗？」的弹框里点「允许」（可以勾上「一律允许这台计算机」），然后重新运行 `adb devices`。
- 列表是空的：请用户确认数据线能传数据（有的线只能充电），手机上已经打开「开发者选项」和「USB 调试」，并把 USB 用途选成「文件传输」再试。打开 USB 调试的方法见[手机操作设置指南](setup-guide.md)的第 1、2 步。
- 有多台设备：请用户只留要装 Mika 的那一台，或者问清是哪一台，之后每条命令都写成 `adb -s <序列号> ...`。

### 1.3 安卓版本

```
adb shell getprop ro.build.version.sdk
```

- API 28 及以下（安卓 9 及以下）：提示用户系统版本过低，安装 Mika 至少需要安卓 10，然后停止安装。
- API 29 到 30（安卓 10、11）：可以安装 Mika，用来上网、处理文件和聊天。告诉用户手机操作需要安卓 12（API 31）及以上，询问是否继续安装。
- 31 到 33（安卓 12、13）：可以执行任务，只是后台操作时主屏的输入焦点可能短暂丢失。34 及以上（安卓 14+）最好。

### 1.4 处理器架构

```
adb shell getprop ro.product.cpu.abi
```

- `arm64-v8a`：Linux 沙箱可用。
- 其他值：Mika 照常能装能用，只是没有 Linux 沙箱（设置里会显示「此设备不支持」）。告诉用户一声即可，继续。

## 第 2 步：下载最新的 APK

从 GitHub 接口取最新版本，选名字形如 `Mika-v0.1.N.apk` 的全架构包，用它的 `browser_download_url` 下载：

```
URL=$(curl -fsSL https://api.github.com/repos/wangzan101/Mika/releases/latest \
  | grep -o '"browser_download_url": *"[^"]*/Mika-v[0-9.]*\.apk"' \
  | sed 's/.*"\(https[^"]*\)"/\1/' | head -n 1)
echo "$URL"
curl -fL -o Mika.apk "$URL"
```

成功：`$URL` 形如 `https://github.com/wangzan101/Mika/releases/download/v0.1.N/Mika-v0.1.N.apk`，`curl` 没有报错，当前目录下有下载完的 `Mika.apk`。

电脑上有 jq 的话，用它解析 JSON 更稳；做法一样，取 `assets` 里名字匹配 `Mika-v0.1.N.apk` 的那一项。

第 1.4 步是 `arm64-v8a` 时，也可以改下名字以 `-arm64.apk` 结尾的那个包（把上面的 `[0-9.]*\.apk` 换成 `[0-9.]*-arm64\.apk`）：只带这一种架构的原生库，体积更小，功能一样。

连不上 GitHub：请用户在浏览器里打开 [Releases](https://github.com/wangzan101/Mika/releases/latest)，手动下载 APK，告诉你文件存在哪，再从第 3 步继续。

## 第 3 步：安装

```
adb install -r Mika.apk
```

成功：最后一行是 `Success`。

- 有的手机通过 USB 安装时，会在手机上弹出确认框。请用户看着手机，出现时点「允许」或「安装」。命令会等到用户点完。
- 失败时把错误原文给用户看。常见的：
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：手机上已经装了一份签名不同的 Mika（比如自己编译的版本）。要装新的只能先卸载旧的，**旧的里面的对话和设置会丢**。说明这一点，问用户要不要自己去卸载；不要替用户卸载。用户卸载后再运行安装命令。
  - `INSTALL_FAILED_VERSION_DOWNGRADE`：手机上的 Mika 比要装的更新，不用装了，直接到第 4 步。
  - `INSTALL_FAILED_USER_RESTRICTED` 或 `INSTALL_FAILED_ABORTED`：用户在手机上拒绝了，或者系统禁止通过 USB 安装。请用户在开发者选项里找「通过 USB 安装」之类的开关并打开（部分手机才有），然后重试。
  - `INSTALL_FAILED_INSUFFICIENT_STORAGE`：手机存储不够，请用户清理一些空间。

## 第 4 步：通知权限（安卓 13 及以上）

Mika 用通知告诉用户任务进展、请用户接管。SDK 小于 33 时跳过这一步。

```
adb shell pm grant com.wangzan101.mika android.permission.POST_NOTIFICATIONS
```

成功：没有输出。

## 第 5 步：打开 Mika 的无障碍服务

只在用户需要手机 App 操作且系统为安卓 12+ 时执行。安卓 10、11 或只用聊天、网页和文件时跳过，不把无障碍未开启当成安装失败。

Mika 靠无障碍服务读界面、做点按。通过 adb 直接写系统设置来打开它，原因是：不从应用商店安装的 App，用户在系统设置里手动打开无障碍时，常被系统以「受限制的设置」拦下；通过 adb 写入不会遇到这个问题。

Mika 的无障碍服务组件名（来自 `app/src/main/AndroidManifest.xml` 里的 `MikaAccessibilityService`）：

```
com.wangzan101.mika/.a11y.MikaAccessibilityService
```

它和完整写法 `com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService` 是同一个；判断列表里有没有 Mika 时，两种写法都要认。

**不要覆盖用户已经开着的其他无障碍服务。** 先读出现有列表，没有 Mika 才在末尾加上（用冒号分隔），再写回去：

```
ME=com.wangzan101.mika/.a11y.MikaAccessibilityService
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "现有：$CUR"
case ":$CUR:" in
  *":$ME:"*|*":com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService:"*)
    NEW="$CUR" ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$ME"; else NEW="$CUR:$ME"; fi ;;
esac
adb shell settings put secure enabled_accessibility_services "'$NEW'"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services
```

成功：最后一行输出里有 Mika 的组件，原来的那些服务也都还在。

说明：`settings get` 对从没写过的项输出 `null`，要当成空列表；写回时外面加单引号，是因为有的服务名里带 `$` 符号。

## 第 6 步：电池白名单

把 Mika 加进系统的省电白名单，免得后台任务、定时任务被系统中途掐掉：

```
adb shell cmd deviceidle whitelist +com.wangzan101.mika
```

成功：输出 `Added: com.wangzan101.mika`（已经在里面时也一样）。
如果这台手机不认 `cmd`，改用 `adb shell dumpsys deviceidle whitelist +com.wangzan101.mika`，效果相同。

检查：

```
adb shell cmd deviceidle whitelist | grep com.wangzan101.mika
```

## 第 7 步：启动 Mika 的手机助手

先打开一次 Mika（所有用途都需要，用于初始化 App 和连接脚本）：

```
adb shell am start -n com.wangzan101.mika/.ui.MainActivity
```

也可以用 `adb shell monkey -p com.wangzan101.mika 1`。第一次打开时 Mika 会引导用户添加模型；可以先让用户点「稍后再说」之类的按钮跳过，模型放到第 8 步。

安卓 10、11 或只用聊天、网页和文件时，打开 App 后直接到第 8 步；手机助手只用于操作手机 App，不需要把「手机操作」设为就绪。

需要手机 App 操作时，目标是让 Mika 的内置助手获得 adb 的 shell 身份（uid 2000）。无线调试配对和 USB 调试都能提供这个身份：系统有能用的无线调试页面时可以配对；页面被系统删掉时，直接用 USB 启动，不需要配对码。

### 先检查无线调试页面是否存在

开始配对前，先做只读检测：

```
adb shell cmd package resolve-activity --brief -a android.settings.WIRELESS_DEBUGGING_SETTINGS
adb shell dumpsys package com.android.settings | grep -iE 'wireless.?debug|AdbWireless|AdbPairing'
```

- 第一条返回 `No activity found`，第二条没有任何输出：系统里已经没有这个页面。告诉用户不用再在设置里找，也不要再反复打开无线调试开关等配对码，直接做下面的 B。
- 找到了 activity 或相关组件：页面可能存在，继续做 A。如果用户仍然进不去配对页，或者开关打开后没有出现配对码，改用 B，不要反复切换开关。
- 命令报错或输出无法判断：把错误原文给用户看；这不能证明页面被删掉。USB 已连接并授权时可以直接用 B，不必为了判断而钻系统的私有实现。

未选择启动方式时，向用户说明区别：

- **A. 配对无线调试（推荐，一次设置）**：在手机上配对；保留相关授权、无线调试和网络条件满足时，可以在重启后自动恢复，不需要电脑。
- **B. 用 USB 启动**：插线运行一次，不用配对；每次手机重启后需要再连电脑运行（除非 A 已能自动恢复）。

也可以先用 B 启动，再配置 A 方便恢复。这里是 Mika 为启动手机助手而配对，不会自动把电脑配到无线 ADB，也不会开启电脑访问；电脑接入见第 10 步。

### 可选：允许 Mika 修改系统设置

这份授权让 Mika 在已有可用无线调试能力时自己打开它，用于自动恢复；**不是 USB 启动助手的前提，也不会把被删掉的配对页面找回来**。说明它会改变手机的权限状态，征得用户同意后再执行；用户不同意就跳过，不影响 B：

```
adb shell pm grant com.wangzan101.mika android.permission.WRITE_SECURE_SETTINGS
adb shell dumpsys package com.wangzan101.mika | grep 'WRITE_SECURE_SETTINGS: granted'
```

成功：第二条输出中有 `granted=true`。

### A. 配对无线调试

1. 确认上面的检测没有找到页面被删掉的证据后，打开无线调试：

   ```
   adb shell settings put global adb_wifi_enabled 1
   adb shell settings get global adb_wifi_enabled
   ```

   成功：第二条输出 `1`。手机要连着 Wi-Fi（不用能上网）。如果手机弹出「要允许在此网络上使用无线调试吗？」，请用户点「允许」，并勾上「始终允许在此网络上使用」。如果读回来又变成 `0`，多半是没连 Wi-Fi，或者用户没在弹框里允许。开关打开却仍没有配对码弹窗时，不要反复写这个设置，改用 B。

2. 告诉用户：在 Mika 里打开 设置 › 手机操作，进入「设置手机操作」向导（第一次打开 Mika 时，设置模型之后也会进入它；向导没出现时，点设置页顶上状态卡片里的提示，或者点 设置 › 手机操作 › 重新配对）。向导会自己检查开发者选项、USB 调试和无线调试，已经打开的会自动打勾。

3. 配对要用户在手机上亲手完成：在系统的「无线调试」页里点「使用配对码配对设备」，把屏幕上的 6 位配对码填进 Mika 的通知里（或者让 Mika 自己读）。你没法替用户配对，等用户说配好了再继续。

4. 每一步怎么点、卡住了怎么办，请用户看[手机操作设置指南](setup-guide.md)。

### B. 用 USB 启动

前提：Mika 至少打开过一次，它才会把启动脚本写到手机上。先检查文件，并读一遍脚本，确认是 Mika 生成的启动脚本；不要修改它：

```
adb shell ls /sdcard/Android/data/com.wangzan101.mika/files/
adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh
```

文件列表里应有 `usb-start.sh`。插着线时运行：

```
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh
```

- 提示文件不存在：先用上面的命令打开一次 Mika，等几秒再试。还是没有的话，手机上的 Mika 版本可能还不带这个功能，改用 A。
- 提示 `Mika was updated or reinstalled`、`Mika helper did not start` 或 `is not running`：先打开一次 Mika，让它按当前 APK 重新生成脚本，再运行同一条命令。
- 拔线后以手机里的助手状态为准；如果手机操作变为未就绪，重连电脑运行本命令，或用已配对的 A 恢复。
- 重启手机后，若 A 没有成功恢复助手，就需要再连电脑运行一次这条命令；重启后先打开一次 Mika。
- 不要为了配对或启动手机助手运行 `connect-computer.sh`。它用于第 10 步的电脑访问，会开启本地服务并生成令牌，只有用户明确要求电脑访问时才运行。
- 不要把 `adb tcpip 5555` 和 `adb connect <手机IP>:5555` 当作本流程的默认替代：那是电脑到手机的网络 ADB，可能受 Wi-Fi AP 隔离影响。Mika 自己的配对和连接走手机本机的 `127.0.0.1`，不受 AP 隔离影响，不需要为此改路由器设置。

### 确认已经启动

可以先用进程做辅助检查：

```
adb shell ps -A -o USER,PID,NAME | grep -i mika
```

通常会看到 `shell ... com.wangzan101.mika:helper`，以及 `u0_aXXX ... com.wangzan101.mika`。进程存在不等于交接已经完成；最后请用户打开 Mika 的设置，看顶上的状态卡片：「手机操作」显示可用（模型也配好时显示「模型已配置 · 手机操作可用」）。如果显示「手机操作还没准备好」，按上面的步骤重做，或者看下面的「常见问题」。

## 第 8 步：添加模型

告诉用户：在 Mika 里打开 设置 › 模型，添加一个服务商并填入自己的 API Key，点「测试并保存」；或者在添加服务商那里点「使用 ChatGPT 继续」，用 ChatGPT 账号登录（需要 Plus 或 Pro）。

**这一步完全由用户在手机上操作。** 不要索要 Key，不要通过 adb 输入或发送 Key。

## 第 9 步：检查结果

```
adb shell dumpsys package com.wangzan101.mika | grep versionName
adb shell settings get secure enabled_accessibility_services
```

成功：第一条显示当前安装的版本号；执行过第 5 步时，第二条应包含 Mika 的组件。跳过手机操作设置时，不要求第二条出现该组件。

需要验收任务执行时，建议用户在 Mika 里亲自发一条简单任务。已配置手机操作的用户可以试：

```
打开设置，告诉我安卓版本是多少
```

只配置聊天、网页和文件的用户可先问一个普通问题。没有执行任务时，结果应写「安装与设置已检查，任务执行未验证」。

## 第 10 步（可选）：从电脑使用 Mika（CLI + Skill 或 MCP）

仅在用户要求电脑访问时执行。**CLI + Skill 与 MCP 通常二选一，无需两套都配置。** 沿用用户已指定的入口和 Skill 安装位置；尚未确定时，按[从电脑使用 Mika](computer.md)的选择表明确选择。

- **CLI + Skill**：按安装指南的 Skill 部分安装，无需先装 pipx 或全局 CLI；完成连接检查后跳过下面的 MCP 配置。只需终端／脚本时可单独安装 CLI。
- **MCP**：继续下面的 USB 步骤，无需安装 CLI 或 Skill。手机经 ADB 转发连接；其他连接方式见[电脑连接（MCP）](mcp.md)。

ADB 操作始终沿用第 1 步选定的手机：CLI / Skill 命令传同一个 `--device SERIAL`，下面的 adb 命令沿用 `adb -s <序列号> ...`。只有用户要求真实任务验收时才提交测试任务。

1. 打开。这条命令也会在需要时尝试启动第 7 步的手机助手：

   ```
   adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh
   ```

   成功：最后说「Computer access over USB is on」，并打印一段带 `url`、`headers`、`token` 的 JSON。提示文件不存在时，先打开一次 Mika（第 7 步里的命令），等几秒再试。

2. 端口转发：

   ```
   adb forward tcp:8765 tcp:8765
   ```

3. 需要时随时再读令牌（`token` 那一项）：

   ```
   adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
   ```

4. 试一下能不能连上（把令牌填进去）：

   ```
   curl -s http://127.0.0.1:8765/mcp \
     -H "Authorization: Bearer <令牌>" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
   ```

   成功：回一段 JSON，`result` 里有 `serverInfo`。这只证明服务可达、鉴权和 MCP 握手成功，不证明 AI 客户端已加载配置或模型任务能成功。

5. MCP 客户端的配置：一个 Streamable HTTP（常写作 `http`）类型的服务器，地址 `http://127.0.0.1:8765/mcp`，请求头 `Authorization: Bearer <令牌>`。下面是常见 JSON 示例，实际使用所选客户端要求的格式：

   ```json
   {
     "mcpServers": {
       "mika": {
         "type": "http",
         "url": "http://127.0.0.1:8765/mcp",
         "headers": { "Authorization": "Bearer <令牌>" }
       }
     }
   }
   ```

   按用户指定的客户端和配置范围添加 Mika，保留其他条目；只有用户要配置当前助手时，才写入你自己的 MCP 配置。需要重启或重载时告诉用户，随后确认客户端能发现 Mika 工具；无法完成这项检查时，明确写「客户端加载未验证」。用户没要求时，不要把令牌贴在对话里。

告诉用户：

- 拔插数据线之后（或者 adb 重启过），要再运行一次 `adb forward tcp:8765 tcp:8765`。令牌不变。
- 重启手机后 USB 方式会关掉，要再运行一次第 1 条命令；这时令牌会换新的，MCP 配置里的令牌也要换上。
- 关掉：`adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh off`，或者在 Mika 的 设置 › 连接电脑 › 高级设置 里关，或者点通知上的「关闭」。关掉后旧令牌立即作废。

## 常见问题

| 现象 | 怎么办 |
|---|---|
| `adb devices` 里没有手机 | 换一根能传数据的线；手机上确认开了「USB 调试」；把 USB 用途选成「文件传输」；拔插一次。 |
| 状态是 `unauthorized` | 请用户在手机上的「允许 USB 调试吗？」弹框里点「允许」。没看到弹框：拔插一次，或者在开发者选项里点「撤销 USB 调试授权」后再插上。 |
| 安装被拦下 | 请用户看手机上有没有确认框；在开发者选项里打开「通过 USB 安装」之类的开关（如果有）。签名不同的报错见第 3 步，卸载由用户决定。 |
| 无障碍过一会儿又关了 | 再做一次第 5 步。配对过无线调试的话，Mika 会自己把它重新打开（设置 › 恢复与修复 里默认开着）。 |
| 重启后手机助手没在运行 | 做过 A：连上 Wi-Fi 等一会儿，Mika 会自己恢复；不行就在 设置 › 手机操作 里点「重新配对」。只做过 B：再连电脑运行一次第 7 步 B 的命令，或者改做 A。 |
| 连不上 GitHub | 请用户在浏览器里打开 [Releases](https://github.com/wangzan101/Mika/releases/latest) 手动下载 APK，然后 `adb install -r <APK 文件路径>`。 |
| 无线调试打不开 | 手机要连着 Wi-Fi；在弹框里允许在此网络上使用无线调试。实在不行就用 B。 |
| MCP 客户端经 USB 连不上 Mika（第 10 步） | 再运行一次 `adb forward tcp:8765 tcp:8765`（每次拔插数据线后都要）。手机重启过的话，再运行一次第 10 步打开的那条命令，并换上新令牌。 |
| MCP 手工转发报 `cannot bind`，或返回的不是 Mika 的内容（第 10 步） | 电脑上 8765 端口被别的程序占了。换一个空闲端口转发，比如 `adb forward tcp:18765 tcp:8765`，配置里的地址改成 `http://127.0.0.1:18765/mcp`；手机那边不用改。 |

## 最后：给用户一份小结

全部做完后，用用户的语言告诉用户：

- **已经做好的**：当前 APK 版本；电脑接入选择了哪一种、实际装了哪些组件，CLI/Skill 的版本 tag 和安装位置，或 MCP 的目标客户端；分别说明安装、连接检查、Agent 的 Skill／MCP 工具加载、真实任务执行的验证结果。通知权限、无障碍、电池白名单和手机助手按实际所需范围报告，跳过或未验证的照实写明。
- **用户还要做的**：模型还没配置时在手机上添加；需要手机操作且选择 A 时完成配对，只用了 B 时提醒重启后重新启动助手。若要验证真实任务，由用户发送简单任务或按已授权范围验收。做了第 10 步的话：CLI/Skill 用户见[安装维护指南](computer-install.md)，ADB 方式在手机重启后重新运行 `connect`；MCP 用户说明客户端是否已加载、USB 拔插后需重新转发及重启后更新令牌。
