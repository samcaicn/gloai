# 性能基线测量计划

> 任务ID：JJC-20260303-001 · 门下省修订要求  
> 执行：户部

## 测量指标

| 指标 | 工具 | 方法 |
|------|------|------|
| 首屏加载 (LCP) | Lighthouse CLI | `npx lighthouse http://localhost:3926 --output=json` |
| 首次可交互 (TTI) | Lighthouse CLI | 同上 |
| Bundle 大小 | `npm run build` | 记录 `dist/` 总体积及各 chunk |
| API P95 响应时间 | autocannon / k6 | `GET /api/tasks` 1000次请求 |
| 内存占用 | Chrome DevTools / `process.memoryUsage()` | 稳态 + 50任务负载 |

## 执行步骤

1. **升级前**（当前版本）执行全量测量，输出 `docs/baseline-before.json`
2. 第一批功能全部合并后，同条件再测，输出 `docs/baseline-after.json`
3. 生成对比报告 `docs/performance-comparison.md`

## 通过标准

- LCP 不超过基线 +20%（目标 < 2s）
- API P95 不超过基线 +15%（目标 < 200ms）
- Bundle 大小不超过基线 +15%
