# 取证：产出引用百分号转义不解码 + 主回答不渲染卡片

## 2026-11-20 上午（本地 + 真机 Mate X2，HarmonyOS 4.2 / API 31）

### A. 库行为实证（缺陷 2 的根因）

`commonmark-0.30.1-destinations.txt`：项目同版本 commonmark-java 0.30.1 直跑，
`[x](/workspace/my%20file.csv)` 的 destination 原样返回 `my%20file.csv`，中文
`%E6%8A%A5%E5%91%8A.png` 同样原样——解析器不解码，应用层不解码就与真实文件名对不上。

### B. 真机走查（发现缺陷 1）

装机 main（c4ef434c + 解码修复）→ connect-computer 提交任务「生成 /workspace/my report.csv
（带空格）与 /workspace/报告.png（中文名），回复用 Markdown 引用交付、链接目标按 URL 规则
百分号编码」→ 任务完成，回答含 `![…](/workspace/%E6%8A%A5%E5%91%8A.png)` 与
`[my report.csv](/workspace/my%20report.csv)`。

- **01-before-main-answer-no-cards.png**：回答下方只有内联文字，**没有任何卡片/缩略图**——
  `OutputRefs` 只接在插话回复（FinishedReply），任务主回答（ExchangeStatus.Done）没接。
  （此图截于只含解码修复的构建，用来定性接线缺失。）

### C. 真机复测（接线 + 解码全修复版）

同一任务重跑（修复分支含 StatusCard 接线）：

- **02-fixed-cards-rendered.png**：回答下方出现青绿色缩略图与文件卡。
- 像素扫描：青绿 RGB(0,150,136) 包围盒 644×400px，纵横比 1.61 = 64×40 ✓
  （解码后路径经文件闸门读到真图，非占位）。
- **03-fixed-filecard-decoded-name.png**：文件卡名显示 `my report.csv`
  （CSV 类型徽章 + 文件名，非 `my%20report.csv`）。
- **04-fixed-open-csv-viewer.png**：点文件卡 → App 查看页打开，标题 `my report.csv`，
  内容 `name,score / Alice,90` 正确（openSent → relative → 文件闸门全链路通）。
- **05-fixed-open-photo-viewer.png**：点图片卡 → 大图查看页打开（青绿图整幅显示）。

### D. 单测

- `MarkdownModelTest` +3、`WorkspaceRefTest` +2（解码形状/裸形式/字面 + 与非法转义/编码越界
  丢弃/照片分类），定向与全量 `:app:testDebugUnitTest`、`:core:test` 均通过。
