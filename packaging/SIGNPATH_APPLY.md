# SignPath 开源免费代码签名 — 申请填写指南

> 我无法替你点 SignPath 的网页（登录会话隔离 + 需填你的身份/组织信息）。
> 这份指南把表单每一项要填的内容都拟好，你**复制粘贴**即可，几分钟搞定。

## 申请入口（已为你打开）
https://signpath.io/solutions/open-source-community

## 表单逐项填写

| 字段 | 填什么（直接复制） |
|---|---|
| Project / Application name | `SafeOPC` |
| Repository URL | `https://github.com/samcaicn/safeopc` |
| Open source license | 填实际许可（SafeOPC 仓库未带标准 LICENSE 文件，请确认；如 `MIT` / `Apache-2.0`，或 gloai 仓库自身许可） |
| Your name / Organization | 你自己填（申请人或组织名） |
| Contact email | 你自己的邮箱 |

### Project description（复制下面任一种）

英文（SignPath 审核用英文更稳）：
```
SafeOPC is an open-source AI-native company framework (originally samcaicn/safeopc).
This public repo (samcaicn/safeopc) hosts the Windows desktop installer (NSIS .exe)
for end-user distribution. We request free OSS Authenticode signing so Windows users
no longer see SmartScreen / "Unknown Publisher" warnings when downloading and running
the installer. The repository is public and open-source.
```

中文（SignPath 也接受）：
```
SafeOPC 是一个开源的 AI 原生公司框架（源自 samcaicn/safeopc）。本公开仓库
samcaicn/safeopc 托管 Windows 桌面安装包（NSIS .exe）供终端用户分发。我们申请
开源免费 Authenticode 签名，使 Windows 用户在下载运行安装包时不再看到
SmartScreen / “未知发布者” 警告。仓库为公开开源项目。
```

## 仓库所有权验证（这步必须由你做）
SignPath 申请中会要求验证你拥有 `samcaicn/safeopc`。通常做法：
- SignPath 提供一个 **GitHub App**，你在 GitHub 的 gloai 仓库设置里点 **Install**（你是 admin，我代不了，需你点）。
- 我已备好验证材料，无需你额外准备：
  - 分支 `safeopc-signpath`（含 `.signpath/policies/safeopc.policy`）
  - Release `safeopc-stub-v0.0.0`（含 `SafeOPC-Setup.exe` 127MB）

## 提交后
- 等人工审核 **~1 周**。
- 审批通过后，在 SignPath 仪表盘：
  - 建 `project=safeopc` / `signing-policy=release-signing` / `artifact-configuration=safeopc-installer`
  - 把 **SignPath GitHub App** 安装到 gloai
  - gloai `Settings → Secrets` 加 `SIGNPATH_API_TOKEN` + `SIGNPATH_ORG_ID`
  - 发一次 release（或重发）触发自动签名

## 申请成功后通知我，我会执行收尾
- 删分支 `safeopc-signpath` + 删 Release `safeopc-stub-v0.0.0`
- 把 SignPath 的 token / org id 存到本地 `C:\code\openopc\.workbuddy\signpath-secrets.md`（标记为**勿公开、勿提交**）
