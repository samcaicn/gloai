// Pages Functions catch-all：把 Pages 请求转交给原 Worker 处理器（cf_worker.js 的 export default）。
// 这样同一份业务代码（授权 / AI 代理 / Jev / 计费 / 免费配额）在 Pages 平台上原样运行，
// 访问域名变为 *.pages.dev。bindings（KV / AI / vars / secrets）在 wrangler.toml 与项目设置中配置。
import worker from "../cf_worker.js";

export async function onRequest(context) {
  return worker.fetch(context.request, context.env, context.ctx);
}
