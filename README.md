# AI智播

AI 数字人直播中控台核心原型（单文件静态站点）。

## 分支

- `aizhibo` — 本项目原型与 CI 热更新产物分支

## 本地预览

直接用浏览器打开 `index.html` 即可，无需构建。

## 热更新流程

参考 weauto 的 Artifact 分发模式：

1. 修改 `index.html` / `version.json`（改版本时同步递增 `version` 字段）
2. `git push origin aizhibo`
3. GitHub Actions 自动执行 `ci.yml`：结构体检 → 生成 `manifest.json` → 上传 `ai-zhibo-web` Artifact（保留 90 天）
4. 分发端按 Artifact 拉取最新 `index.html`，用 `version.json` / `manifest.json` 的 `version`、`sha256` 比对决定是否热替换

CI 也支持在 Actions 页面手动 `Run workflow` 触发（`workflow_dispatch`）。
