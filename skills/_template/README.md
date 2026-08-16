# 标准技能模板（_template）

这是 tupautochrome 自动化技能的**标准起点模板**。新技能作者拷贝此目录即可起步。

## 拷贝即用

```bash
cp -r skills/_template skills/<your-skill-id>
```

## 目录结构

```
_template/
├── SKILL.md          # 技能元数据（frontmatter）+ 人读说明
├── index.js          # 运行时 handler（三段式导出：handler + lifecycle + debug）
├── flowchart.json    # 标准流程图配置（节点/边/判断）
├── USAGE.md          # 使用流程：搜索 → 加载 → 执行 → 停止 → 回放
├── DEBUG.md          # 调试流程：断点 / 单步 / 变量监视 / trace
├── UPGRADE.md        # 升级流程：SemVer / 灰度 / 回滚 / 市场元数据
├── README.md         # 本文件
└── assets/           # 静态资源（图标等）
    └── .gitkeep
```

## 新建技能的 5 步

1. **拷贝目录**：`cp -r skills/_template skills/<your-skill-id>`
2. **改 SKILL.md**：把 `id` 改成 `com.tupautochrome.skills.<your-skill-id>`，填 `name` / `software_names` / `runtime.caps` 等 frontmatter
3. **改 flowchart.json**：把 `id` / `skillId` / `nodes` / `connections` / `judgments` 替换成你的流程
4. **实现 index.js**：改 `FLOWCHART` 常量镜像 flowchart.json，实现 `execute` / `record` 的节点逻辑
5. **写三份文档**：把 USAGE.md / DEBUG.md / UPGRADE.md 的内容替换成你的实际场景，发布到服务器

## 参考实例

`skills/trace-auto/` 是基于此模板的参考实例，演示了 Trae IDE 自动化的完整实现（CDP>UIA>OCR>VLM 识别链、迷你悬浮窗控制、流程图回放）。

## 能力依赖

本模板依赖的能力层见 `src-tauri/src/skills/capabilities.js`，速查表见上级 `skills/README.md`。
