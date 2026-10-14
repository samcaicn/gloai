# jev-chat (jev-chat-jarvis) 待办

> 背景：我们的 `jev-chat-jarvis` 是上游 `jev-chat/jev-chat-jarvis`（MIT 开源）的**硬 fork**，不是上游的跟踪镜像。
> 上游官方仓已独立演进，并拆成多平台独立仓；我们这边的私有化改造（离线 OCR / 反马甲 / 话术包加密）上游不会合入，应继续自维护。

## 现状基线

- 我们的分支：`samcaicn/gloai:jev-chat-jarvis`（tip `6d0996aa`，最后提交 2026-10-13）。
- 代码基线：上游快照 `@8722e03`（2026-09-27，约对应上游 **v1.4** 时代）。
- 我们已本地私有化（上游无）：
  - **OCR 整套换成 PaddleOCR (PP-OCRv6 ONNX)**：纯离线、无 GMS 依赖（2026-10-02）。
  - **反马甲包 L1+L2+L3 体系**：R8 全混淆 + NDK `libintegrity.so` / 原生完整性校验（frida·xposed·magisk·TracerPid）/ Worker `verifyClientIntegrity` HMAC+时间戳 403 拒马甲，挂 `jukuai.net`（2026-10-13）。
  - **AES-GCM 话术包 (.wpk) 加密系统**：计数器 off-by-one 修正 + 索引落盘不依赖 keystore + `gcm_crosscheck` 与 Node 双向比对（2026-10-13）。
  - 自动收发 / 发送前反悔 / 自检诊断 / 事件日志 / Vercel·OpenCode Zen 预设（CHANGELOG「未发布」段，已落地）。

## 上游现状（2026-10，需要了解的差异）

- 国内版 **v1.7**（2026-10-05/06）：新增 WhatsApp 一对一、常驻可拖拽卡片、候选「换一组」、候选条数 1/2/3、语言跟随对方、30s 超时重试、无障碍服务改名 Jev、截屏移出主线程。
- 海外版 **global-v0.1.0**（2026-10-06）：WhatsApp 英文一对一，独立包 `com.jev.overseas`，任意 CPU。
- 已拆多平台独立仓：Windows `jev-chat-windows`、macOS `jev-chat-jarvis-mac`、iOS `jev-chat-jarvis-ios`（仅源码）。

## 应该做什么（行动项）

1. **保持硬 fork，不要 upstream rebase 覆盖本地私有化**：PaddleOCR / 反马甲 / 话术包加密继续自维护，不要被上游合并且冲掉。
2. **选择性 cherry-pick 上游 v1.7 的纯 UX 改进**（与本地改造不冲突）：WhatsApp 一对一适配、常驻卡片、候选「换一组」、候选条数、语言跟随、30s 超时重试。
3. **合规（若对外发布 / 商业化）**：保留 `LICENSE`+`NOTICE`，注明 `Based on Jev Chat Assistant (https://github.com/jev-chat/jev-chat-jarvis)`；禁用 "Jev 聊天助手 / jev-chat / chatjevs.com" 暗示官方背书。
4. **决策：是否跟进海外版 / Windows / macOS / iOS 独立仓**（按需，非必需）。
5. **CI 规范化**：双仓同步 + `_wipe.yml`「只留 Actions」的规范见 `/c/d/STANDARD.md`，由各项目自行遵守，不再依赖已删除的聚合仓（gb-monorepo）。

## 备注

- jev-chat 是**独立项目**，不属于原 gb-monorepo 的规范项目集合；其 CI 分支 `jev-chat-jarvis` 保留在 `samcaicn/gloai`，不调用标准 `_wipe.yml`（属独立项目拓扑，按需自行处理）。
- 原 gb-monorepo 聚合仓已于 2026-10-14 彻底清理删除；其价值（CI 规范）已抽离到 `/c/d/STANDARD.md`。

## 已落地到 WeAuto（2026-10-14，本仓 jev_guard.py）

参考本 TODO 的上游 v1.7 差异，已在 WeAuto 侧完成移植（硬 fork 继续自维护，未做 upstream rebase）：

- **题目集判据精化**：对照上游 main `cn/tools/jev/questions.py` 逐条合入 true_intent / should_reply_now / best_action / she_needs / tension_resolved 的 criteria 与 instructions 修订（v1.4 快照之后的上游演进）。
- **语言跟随对方**：上游 v1.7 是「候选回复的语言跟随对方最近一条消息」；WeAuto 回复全自动、无候选 UI，等价落地为新增 `language` 判断题 + guidance 注入（主模型用同一语言回复）。
- **30s 超时重试**：上游 v1.7「判断最多等 30 秒，超时停下给重试」。WeAuto 落地为首次 8s + 瞬时故障（超时/连接失败/5xx）重试一次（30s 量级）；4xx 不重试；每次尝试重签 HMAC（ts ±60s 窗口）。
- **候选条数 1/2/3**：`build_rank_question` / `rank` 支持 1~3 条；1 条时不发排序请求（上游：出得更快）。
- **测试与 CI**：`_jev_exe_test.py` 重写为适配零配置架构的离线单测（67 项，mock 本地 decisions 服务），接入 CI ux-e2e job。

未移植（Android 专属，WeAuto 无对应形态）：WhatsApp 一对一、常驻可拖拽卡片、候选「换一组」（WeAuto 无候选 UI）、无障碍服务改名、截屏移出主线程。

## ⚠️ 线上事故（2026-10-14 发现，待修）

weauto-app.pages.dev 的 Worker 自 2026-10-13 04:14 部署起对 `/ai/*` 启用了 Android L3 完整性门槛（`X-Sig-Fp`/`X-Hmac`，403 `integrity_signature`），**Windows EXE 的 Jev 判断被全量挡下**（judge() 已降级为照常回复，不会让 bot 变哑巴，但功能实际不可用；连续失败后 WebUI 显示「已熔断」）。

- 该 Worker 源码不在本仓（部署目录 `worker_fix/` 在另一台机器，本地 cf_worker.js 无完整性校验代码）。
- 修复方向：`/ai/jev/decisions` 改**双凭证**——Android 完整性头 **或** EXE 的 `X-WeAuto-Ts/Nonce/Sig`（密钥 `WEAUATO_CLIENT_SECRET`，本地 `cf_worker.js` 的 `jevDecisions` 已有完整实现，生产 `jev_require_auth: true`）。注意 Android 侧不能被放空：马甲包可扒 EXE 里的 secret，完整性墙对 Android 仍必须保留。
