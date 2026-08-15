# 代码签名（消除 SmartScreen / Defender 拦截）

## 为什么需要签名

未签名的 `.exe` / 安装包，Windows 会弹「Windows 已保护你的电脑 / Unknown Publisher」。
换打包格式（PyInstaller onedir、NSIS、Inno）都救不了——这是 SmartScreen/Defender 对**未签名可执行文件**的底层拦截。
唯一根治：**Authenticode 代码签名**。

## 现实预期（别被「EV 免费」误导）

| 证书类型 | 发布者显示 | SmartScreen 首下行为 | 免费开源档能否拿到 |
|---|---|---|---|
| **EV**（扩展验证，云端 HSM） | 验证过的组织/身份 | **立即信任**，不弹 SmartScreen | 个人/开源通常**拿不到纯 EV**，但 SignPath 用 Certum EV 基础设施给 OSS 做托管签名，效果等同 |
| **OV**（组织验证，云端 HSM） | 你的名字/组织 | 首次下载可能仍提示，靠下载量**积累信誉**后消失 | Certum Open Source 档是 OV |

结论：
- 要「双击立即无忧」→ 需要 EV 级信誉。SignPath 免费 OSS 方案走 Certum EV 基础设施做**托管签名**，开源项目通常能立即消除 SmartScreen（多个项目验证）。
- Certum 的「Open Source」档是个人 OV，发布者显示 `Open Source Developer, 你的名字`，SmartScreen 需攒信誉。
- 自签名证书**无效**（SmartScreen 不认）。

## 路线 A：SignPath（推荐给开源，免费 + 云端 EV 托管）

### 前提（硬门槛）
1. **你自己的公开 GitHub 仓库**（SignPath 挂 GitHub App，从公开仓库 Release/CI 拉产物签名）。
   - 当前 `origin` 是港大官方 `samcaicn/safeopc`，且本地 `packaging/` 等改动未提交 → **不能直接用**。
   - 做法：在 GitHub 建你自己的公开仓库（fork `samcaicn/safeopc` 或自建），把含 `packaging/` 的代码推上去。
2. 项目确实是开源（公开仓库即满足）。

### 申请步骤
1. 打开 https://signpath.io/solutions/open-source-community → 点 **Apply for free signing**。
2. 填表，指向你的公开仓库 URL（如 `https://github.com/<你>/SafeOPC`）。
3. 等审核（开源通常 **~1 周**）。
4. 审核通过后，SignPath 控制台建立：
   - Project slug（如 `safeopc`）
   - Signing policy slug（如 `release-signing`）
   - Artifact configuration slug（定义签哪些文件，如 `.exe` / NSIS 安装包）
5. 装 **SignPath GitHub App**（授权它访问你的公开仓库）。
6. 在仓库 `Settings → Secrets and variables → Actions` 加：
   - `SIGNPATH_API_TOKEN`（SignPath 控制台生成）
   - `SIGNPATH_ORG_ID`（组织 UUID）
7. 推送一个 release tag（`v*`），CI 自动把 `SafeOPC-Setup.exe` 提交给 SignPath 签名并取回。

### 本仓库已备好的接入文件
- `packaging/ci-template/sign-windows.yml` — GitHub Actions 工作流（自动下载 Release 里的 NSIS 安装包、提交 SignPath 签名、取回）。
- `packaging/ci-template/safeopc.policy` — SignPath 策略声明（放进你仓库的 `.signpath/policies/safeopc.policy`，可审计）。

> 本机无需 `signtool`：SignPath 云端完成签名。

## 路线 B：Certum Open Source（个人、本地签、OV）

### 适用
- 个人申请（需身份证/护照 + 开源项目真实性验证），**不强制公开 GitHub 仓库**。
- 云端 HSM（SimplySign 客户端 + 手机动态码），可在本机/CI 签名。
- 发布者显示 `Open Source Developer, 你的名字`（OV，SmartScreen 需攒信誉）。

### 申请步骤
1. 打开 https://www.certum.eu/en/cert_offer_en_open_source_cs/ → 选 Open Source 档，申请。
2. 提交个人身份证明 + 开源项目说明，CA 电话/邮件核验（1–5 天）。
3. 审核通过后证书签发至 Certum 云端（CertManager / SimplySign）。
4. 本机装 SimplySign 客户端，绑定手机，用 `signtool` 或 Certum 工具对 `SafeOPC-Setup.exe` 签名（附时间戳）。

### 本机签名（需 Windows SDK 的 signtool）
```
# 本机当前未装 signtool，需先装 Windows SDK 或用 Certum SimplySign 自带工具
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /a "dist/SafeOPC-Setup.exe"
```

## 当前本机状态
- 未装 `signtool.exe`（无 Windows SDK）→ 本地 pfx/SimplySign 签名前需先装 SDK 或走 Certum 云工具。
- `dist/SafeOPC-Setup.exe`（127 MB，NSIS，未签名）已就绪，签名后即可分发。

## 决策建议
- 想「双击立即无忧」+ 愿意等 ~1 周审核 + 有/愿建公开 GitHub → **路线 A（SignPath）**。
- 不想建公开仓库、个人本地签即可、接受 SmartScreen 攒信誉 → **路线 B（Certum Open Source）**。
- 只是自己本地测 → 都不用，加 Defender 排除目录或首次 cmd 运行点「仍要运行」即可。
