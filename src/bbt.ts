// bbt-zb 接入封装（对齐 weauto/llm.js 的「上游」思路）：
// 登录、探活、拉取/推送聊天参数、通用 action 调用，全部委托给 Rust 后端转发。
import { api } from "./api";

export const bbt = {
  login: (base_url: string, telephone: string, password: string) =>
    api.login(base_url, telephone, password),
  call: (action: string, condition?: any) => api.call(action, condition),
  test: () => api.testConnection(),
  fetch: () => api.fetchRemoteConfig(),
  push: (value: any) => api.pushRemoteConfig(value),
  setConnection: (base_url: string, token?: string, telephone?: string) =>
    api.setConnection(base_url, token, telephone),
  updateCheck: (base_url: string) => api.updateCheck(base_url),
  updatePrepare: (base_url: string) => api.updatePrepare(base_url),
};
