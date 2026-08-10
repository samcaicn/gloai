# edict 集成清单（由 scripts/sync-edict.sh 自动生成）

- 同步时间: 2026-08-08T09:55:45Z
- 上游(只读): /up  (github.com/cft0808/edict)
- 上游基线: origin/main @ 14a207557719c046af0f993a7bff1cc5a5015b33
- 集成方式: git archive 导出 main + 应用 scripts/edict-integration.patch
- 已叠加的“提出的需求分支”（仅取其相对 main 的真实功能增量）:
    - copilot/add-ai-daily-digest            (HEAD a032eff)  # 技术博客 RSS 源 + 看板分类
    - copilot/fix-code-bugs-and-optimize-performance (HEAD 037bbbd)  # 回归测试
      （该分支对 kanban_update.py 的 save()->trigger_refresh() 重构，
       上游 origin/main 已包含，故此处仅补其新增测试）

## 说明
本目录是根目录下的独立组件 / 伴生服务，不直接并入 Go Hub 工程。
重跑 ./scripts/sync-edict.sh 可从只读上游重新生成本目录（含上述增量）。
