// 租户信息相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册（commands::tenant.rs）：
//   tenantGet       → tenant_get        (无参数，返回 TenantInfo，不含 tags/website)
//   tenantRegister  → tenant_register   (input: TenantRegisterInput { name, token? })
//   tenantInfo      → tenant_info       (token?: String, 返回 TenantInfo 含 tags + website)
//
// 后端实现：commands/tenant.rs，租户信息持久化到 app_data_dir/tenant.json。
// tenant_register 为本地注册（生成 tenant_id + 落盘），token 当前未使用（无云端 API）。
// tenant_info 在 tenant_get 基础上额外调 MCP v2 `tenant.get` 拉取 tags / website：
//   - tags[0] 用于在 UI 左上角展示租户身份
//   - website 渲染为该 tag 文字的跳转链接（新窗口打开，无下划线，带飘动动画）
import { invoke } from './invoke';

export interface TenantInfo {
  id: string;
  name: string;
  plan?: string;
  /** 租户在 MCP server 端的 tags；MCP 拉取失败/未配置时不存在。 */
  tags?: string[];
  /** 租户在 MCP server 端的官网 / 落地页地址（新格式字段 website_url）。
   *  前端左上角 tag 文本会渲染为跳转到该地址的链接。
   *  MCP 拉取失败/未配置/非 http(s) 时不存在。
   *  兼容旧格式 website 字段。 */
  websiteUrl?: string;
  /** @deprecated 兼容旧格式，请使用 websiteUrl */
  website?: string;
  /** 租户在 MCP server 端配置的 logo 文字（品牌名/简称）。
   *  前端左上角优先展示此字段；未配置时回退到 tags[0] 再回退到本地租户名。
   *  MCP 拉取失败/未配置时不存在。 */
  logoText?: string;
  /** 服务器端配置更新时间（Unix 时间戳，秒级浮点数） */
  updatedAt?: number;
  /** 服务器端配置更新者（tenant_id） */
  updatedBy?: string;
}

export interface TenantRegisterInput {
  name: string;
  token?: string;
}

// 后端 tenant_get 返回当前租户信息（未注册时返回空 TenantInfo）。
export async function tenantGet(): Promise<TenantInfo> {
  return invoke<TenantInfo>('tenant_get');
}

// 后端 tenant_register 期望 (input: TenantRegisterInput)，本地生成 tenant_id 并落盘。
export async function tenantRegister(input: TenantRegisterInput): Promise<TenantInfo> {
  return invoke<TenantInfo>('tenant_register', { input });
}

// 后端 tenant_info 期望 (token?: String)；
// token 不传时为匿名调用，服务器按 IP 识别租户（可能返回空 tags / website）。
// 已注册场景：返回本地信息 + MCP tags + website。
// 未注册场景：返回空 TenantInfo，不发起 MCP 请求。
export async function tenantInfo(token?: string): Promise<TenantInfo> {
  return invoke<TenantInfo>('tenant_info', { token: token ?? null });
}
