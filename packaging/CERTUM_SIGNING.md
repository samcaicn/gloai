# Certum Open Source 代码签名实操（路线 B）

> 选择：个人申请、免费、不强制公开 GitHub 仓库、云端 HSM 本机签名。
> 性质：**OV（组织验证）**，发布者显示 `Open Source Developer, 你的名字`。
> 现实：**SmartScreen 首次下载可能仍提示**，靠下载量攒信誉后消失；能去掉 `Unknown Publisher`，满足 Defender 对"已签名"的基本要求。要"双击立即零警告"只有 EV（个人/开源档拿不到纯 EV）。

## 1. 申请（需你本人操作，我不能代审）

1. 打开 https://www.certum.eu/en/cert_offer_en_open_source_cs/ → 选 **Open Source** 档 → 申请。
2. 提交：
   - 个人身份证明（身份证 / 护照扫描件）。
   - 开源项目说明（公开仓库 URL、许可证、非商业声明）。
   - 联系邮箱 / 电话。
3. CA 电话 / 邮件核验身份（**1–5 天**）。
4. 审核通过 → 证书签发至 Certum 云端（CertManager / SimplySign）。

## 2. 拿到证书后：两种本地签名方式

### 方式 A — signtool（推荐，可脚本化）
前提：证书已**导入 Windows 证书存储**（Certum 流程允许时），或你拿到了可导入的 PFX。

本机 signtool 已存在（Windows SDK 自带），直接调绝对路径即可，**无需装任何东西**：

```
"C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64\signtool.exe" sign ^
  /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 ^
  /s My /n "Open Source Developer" ^
  "C:\code\openopc\dist\SafeOPC-Setup.exe"
```

- `/s My`：从当前用户证书存储选证书（按 `/n` 名称匹配，或用 `/sha1 <指纹>` 精确指定）。
- `/tr` + `/td`：免费时间戳服务，证书过期后签名仍有效。
- 若有 PFX：`/f cert.pfx /p 密码` 替代 `/s My /n ...`。

### 方式 B — Certum 官方工具（云端 HSM，私钥不出 Certum）
若 Certum 仅给云端 HSM（无本地可导出证书），用官方签名途径：
- **SimplySign** 桌面客户端 → 选本地文件 → 手机动态码授权签名；或
- Certum **Code Signing REST API**（CI 用）。
具体命令以 Certum 控制台 / 文档为准（每个账户的工具路径不同，无法硬编码）。

## 3. 自动脚本

`packaging/sign-certum.ps1` 已封装方式 A（自动定位 signtool + 签名 + 验证）：

```
# 证书在 Windows 存储（按名称匹配）：
powershell -ExecutionPolicy Bypass -File packaging/sign-certum.ps1

# 或指定 PFX：
powershell -ExecutionPolicy Bypass -File packaging/sign-certum.ps1 -PfxPath cert.pfx -PfxPassword "***"
```

## 4. 验证签名

```
signtool verify /pa "C:\code\openopc\dist\SafeOPC-Setup.exe"
```
应显示 `Successfully verified`。

## 5. 当前状态
- 本机有 signtool（`10.0.26100.0/x64`），无需安装。
- `dist/SafeOPC-Setup.exe`（127 MB，NSIS，未签名）已就绪，签名后即可分发。
- 证书需你本人向 Certum 申请（个人身份验证），我无法代审。

## 6. 决策回顾
- 想要"双击立即无忧" → 需 EV；个人/开源免费档是 OV，做不到立即零警告。
- 接受 SmartScreen 攒信誉 + 想本机/简单分发 → Certum Open Source 合适。
- 只是自己本地测 → 不用签名，加 Defender 排除目录或首次 cmd 运行点「仍要运行」即可。
