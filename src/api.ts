// 对 Tauri command 的薄封装。所有 bbt-zb / 本地配置请求都经由 Rust 后端，
// 避免 webview 直接跨域 fetch（绕开 CORS / CSP 限制）。
import { invoke } from "@tauri-apps/api/core";

export const api = {
  loadConfig: () => invoke<any>("load_config"),
  saveConfig: (value: any) => invoke("save_config", { value }),
  patchConfig: (value: any) => invoke<any>("patch_config", { value }),
  localCacheStatus: () => invoke<any>("local_cache_status"),
  resetConfig: () => invoke("reset_config"),

  setConnection: (base_url: string, token?: string, telephone?: string) =>
    invoke("set_connection", {
      base_url,
      token: token || undefined,
      telephone: telephone || undefined,
    }),

  login: (base_url: string, telephone: string, password: string) =>
    invoke<any>("login", { base_url, telephone, password }),

  call: (action: string, condition?: any) =>
    invoke<any>("bbt_call", { action, condition: condition || {} }).catch((e: any) => {
      const msg = String(e?.message || e || "");
      if (/会话已失效|未登录|token|登录过期|401|unauthorized/i.test(msg)) {
        window.dispatchEvent(new CustomEvent("session-expired", { detail: { msg, action } }));
      }
      throw e;
    }),

  testConnection: () => invoke<any>("test_connection"),
  fetchRemoteConfig: () => invoke<any>("fetch_remote_config"),
  pushRemoteConfig: (value: any) => invoke("push_remote_config", { value }),

  updateCheck: (base_url: string) => invoke<any>("update_check", { base_url }),
  updatePrepare: (base_url: string) => invoke<any>("update_prepare", { base_url }),

  // 团员 iLink 二维码：成员 ↔ 机器人账号映射（hub 持久化）
  memberqrGetMap: () => invoke<Record<string, string>>("memberqr_get_map"),
  memberqrSetMap: (map: Record<string, string>) => invoke("memberqr_set_map", { map }),

  // 团员 ↔ 微信私聊/群聊 配对（每个微信会话 = 1 个团员）
  wechatPairingGet: () => invoke<Record<string, any>>("wechat_pairing_get"),
  wechatPairingSet: (map: Record<string, any>) => invoke("wechat_pairing_set", { map }),

  // 团员名册：本地缓存 + 并发回源（groups / by_group / members / cache）
  memberRoster: (force = false) => invoke<any>("member_roster", { force }),
  rosterCacheStatus: () => invoke<any>("roster_cache_status"),
  rosterCacheClear: () => invoke<any>("roster_cache_clear"),
};
