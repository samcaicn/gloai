export interface Bot {
  id: string;
  name: string;
  display_name: string;
  provider: string;
  status: string;
  can_send: boolean;
  send_disabled_reason?: string;
  ai_enabled: boolean;
  ai_model: string;
  msg_count: number;
  reminder_hours: number;
  last_msg_at?: number;
  last_reminded_at?: number;
  created_at: number;
  extra?: Record<string, any>;
}

export function botDisplayName(bot: Pick<Bot, "display_name" | "name">): string {
  return bot.display_name || bot.name;
}

// ==================== 技能市场 ====================

/** Marketplace visibility of a skill as a whole. */
export type SkillListing = "draft" | "pending" | "listed" | "rejected" | "unlisted";

/** Review state of a single submitted version. */
export type SkillVersionStatus = "pending" | "approved" | "rejected" | "superseded" | "cancelled";

export interface Skill {
  id: string;
  slug: string;
  name: string;
  description: string;
  icon?: string;
  category?: string;
  tags?: string;
  homepage?: string;
  license?: string;
  author?: string;
  owner_id: string;
  owner_name?: string;
  source: string;
  source_url?: string;
  latest_version_id?: string;
  latest_version?: string;
  listing: SkillListing;
  reject_reason?: string;
  install_count: number;
  rating_count: number;
  rating_avg: number;
  created_at: number;
  updated_at: number;
  installed?: boolean;
}

export interface SkillFile {
  path: string;
  size: number;
}

export interface SkillVersion {
  id: string;
  skill_id: string;
  version: string;
  changelog?: string;
  manifest?: Record<string, any>;
  readme?: string;
  entry: string;
  bundle_size: number;
  bundle_sha256?: string;
  files?: SkillFile[];
  source_url?: string;
  commit_hash?: string;
  status: SkillVersionStatus;
  reject_reason?: string;
  submitted_by?: string;
  submitter_name?: string;
  reviewer_name?: string;
  reviewed_at?: number;
  download_count: number;
  created_at: number;
  skill_name?: string;
  skill_slug?: string;
  skill_icon?: string;
}

export interface SkillRating {
  id: string;
  skill_id: string;
  user_id: string;
  user_name?: string;
  rating: number;
  comment?: string;
  version?: string;
  created_at: number;
  updated_at: number;
}

export interface SkillReviewLog {
  id: string;
  skill_id: string;
  version_id?: string;
  action: string;
  actor_id: string;
  actor_name?: string;
  reason?: string;
  version?: string;
  created_at: number;
}

export interface SkillInstall {
  id: string;
  skill_id: string;
  version_id: string;
  agent_id?: string;
  skill_name?: string;
  skill_slug?: string;
  skill_icon?: string;
  version?: string;
  created_at: number;
}

export interface SkillDetail {
  skill: Skill;
  latest_version: SkillVersion | null;
  my_rating: SkillRating | null;
  ratings: SkillRating[];
  installed: boolean;
  can_manage: boolean;
  versions?: SkillVersion[];
  review_logs?: SkillReviewLog[];
}

export interface SkillListParams {
  q?: string;
  category?: string;
  sort?: "rating" | "installs" | "newest" | "updated";
  mine?: boolean;
  listing?: string;
}

export interface SkillSubmitFields {
  slug?: string;
  category?: string;
  tags?: string;
  changelog?: string;
  icon?: string;
  homepage?: string;
}

export interface SkillSubmitResult {
  skill_id: string;
  slug: string;
  version_id: string;
  version: string;
  status: SkillVersionStatus;
  files?: SkillFile[];
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...options?.headers },
    ...options,
  });
  if (res.status === 401) {
    const path = window.location.pathname;
    const isPublic = path === "/";
    if (!isPublic) {
      window.location.href = "/login";
    }
    throw new Error("unauthorized");
  }
  let data: any;
  try {
    data = await res.json();
  } catch {
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    throw new Error("invalid response");
  }
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data as T;
}

export const api = {
  // Auth
  register: (username: string, password: string) =>
    request("/api/auth/register", { method: "POST", body: JSON.stringify({ username, password }) }),
  login: (username: string, password: string) =>
    request("/api/auth/login", { method: "POST", body: JSON.stringify({ username, password }) }),
  logout: () => request("/api/auth/logout", { method: "POST" }),
  oauthProviders: () =>
    request<{ providers: any[] }>("/api/auth/oauth/providers").then((data) => ({
      providers: (data.providers || []).map((p: any) =>
        typeof p === "string" ? { name: p, display_name: p, type: "oauth" } : p,
      ) as Array<{ name: string; display_name: string; type: string; key?: string }>,
    })),
  me: () =>
    request<{
      id: string;
      username: string;
      display_name: string;
      role: string;
      email?: string;
      has_password: boolean;
      has_passkey: boolean;
      has_oauth: boolean;
    }>("/api/me"),
  info: () => request<{ ai: boolean; registration_enabled: boolean; version: string }>("/api/info"),

  // Passkeys
  listPasskeys: () => request<any[]>("/api/me/passkeys"),
  passkeyBindBegin: () => request<any>("/api/me/passkeys/register/begin", { method: "POST" }),
  passkeyBindFinishRaw: (body: string, name?: string) =>
    fetch(`/api/me/passkeys/register/finish${name ? `?name=${encodeURIComponent(name)}` : ""}`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body,
    }).then(async (r) => {
      if (!r.ok) throw new Error((await r.json()).error);
    }),
  deletePasskey: (id: string) => request(`/api/me/passkeys/${id}`, { method: "DELETE" }),
  renamePasskey: (id: string, name: string) =>
    request(`/api/me/passkeys/${id}`, { method: "PATCH", body: JSON.stringify({ name }) }),

  // Profile
  updateProfile: (data: { display_name?: string; email?: string }) =>
    request("/api/me/profile", { method: "PUT", body: JSON.stringify(data) }),
  updateUsername: (username: string) =>
    request("/api/me/username", { method: "PUT", body: JSON.stringify({ username }) }),
  changePassword: (data: { old_password: string; new_password: string }) =>
    request("/api/me/password", { method: "PUT", body: JSON.stringify(data) }),

  // Bots
  listBots: () => request<Bot[]>("/api/bots"),
  bindStart: () =>
    request<{ session_id: string; qr_url: string }>("/api/bots/bind/start", { method: "POST" }),
  reconnectBot: (id: string) => request(`/api/bots/${id}/reconnect`, { method: "POST" }),
  deleteBot: (id: string) => request(`/api/bots/${id}`, { method: "DELETE" }),
  listBotApps: (botId: string) => request<any[]>(`/api/bots/${botId}/apps`),
  listTraces: (botId: string, limit = 50) =>
    request<import("./trace-utils").TraceSpan[]>(`/api/bots/${botId}/traces?limit=${limit}`),
  getTrace: (botId: string, traceId: string) =>
    request<import("./trace-utils").TraceSpan[]>(`/api/bots/${botId}/traces/${traceId}`),
  updateBot: (
    id: string,
    data: { name?: string; display_name?: string; reminder_hours?: number },
  ) => request(`/api/bots/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  setBotAI: (botId: string, enabled: boolean) =>
    request(`/api/bots/${botId}/ai`, {
      method: "PUT",
      body: JSON.stringify({ enabled }),
    }),
  setBotAIModel: (botId: string, model: string) =>
    request(`/api/bots/${botId}/ai_model`, {
      method: "PUT",
      body: JSON.stringify({ model }),
    }),
  botContacts: (id: string) => request<any[]>(`/api/bots/${id}/contacts`),

  // Channels (under bots)
  listChannels: (botId: string) => request<any[]>(`/api/bots/${botId}/channels`),
  createChannel: (botId: string, name: string, handle?: string) =>
    request(`/api/bots/${botId}/channels`, {
      method: "POST",
      body: JSON.stringify({ name, handle: handle || "" }),
    }),
  updateChannel: (botId: string, id: string, data: any) =>
    request(`/api/bots/${botId}/channels/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteChannel: (botId: string, id: string) =>
    request(`/api/bots/${botId}/channels/${id}`, { method: "DELETE" }),
  rotateKey: (botId: string, id: string) =>
    request<{ api_key: string }>(`/api/bots/${botId}/channels/${id}/rotate_key`, {
      method: "POST",
    }),

  // OAuth accounts
  oauthAccounts: () => request<any[]>("/api/me/linked-accounts"),
  unlinkOAuth: (provider: string) =>
    request(`/api/me/linked-accounts/${provider}`, { method: "DELETE" }),

  // Stats
  stats: () => request<any>("/api/bots/stats"),

  // Messages (under bots)
  messages: (botId: string, limit = 30, cursor?: string) =>
    request<{
      messages: any[];
      next_cursor: string;
      has_more: boolean;
      can_send?: boolean;
      send_disabled_reason?: string;
    }>(`/api/bots/${botId}/messages?limit=${limit}${cursor ? "&cursor=" + cursor : ""}`),
  sendMessage: (botId: string, data: any) =>
    request(`/api/bots/${botId}/send`, { method: "POST", body: JSON.stringify(data) }),

  // Admin: system config
  getOAuthConfig: () => request<Record<string, any>>("/api/admin/config/oauth"),
  setOAuthConfig: (provider: string, data: { client_id: string; client_secret: string }) =>
    request(`/api/admin/config/oauth/${provider}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteOAuthConfig: (provider: string) =>
    request(`/api/admin/config/oauth/${provider}`, { method: "DELETE" }),

  // Admin: OIDC config
  getOIDCConfig: () => request<any[]>("/api/admin/config/oidc"),
  setOIDCConfig: (
    slug: string,
    data: {
      display_name: string;
      issuer_url: string;
      client_id: string;
      client_secret: string;
      scopes?: string;
    },
  ) => request(`/api/admin/config/oidc/${slug}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteOIDCConfig: (slug: string) =>
    request(`/api/admin/config/oidc/${slug}`, { method: "DELETE" }),

  // Public: available models list (all authenticated users)
  getAvailableModels: () => request<string[]>("/api/config/ai/available_models"),

  // Admin: AI config
  getAIConfig: () => request<any>("/api/admin/config/ai"),
  setAIConfig: (data: {
    base_url?: string;
    api_key?: string;
    model?: string;
    system_prompt?: string;
    max_history?: string;
    hide_thinking?: string;
    strip_markdown?: string;
    available_models?: string;
  }) => request("/api/admin/config/ai", { method: "PUT", body: JSON.stringify(data) }),
  deleteAIConfig: () => request("/api/admin/config/ai", { method: "DELETE" }),
  // Admin: fetch model list from the provider's OpenAI-compatible /models endpoint
  fetchAIModels: (data: {
    base_url?: string;
    api_key?: string;
    custom_headers?: Record<string, string>;
  }) =>
    request<{ models: { id: string; object?: string; owned_by?: string }[] }>(
      "/api/admin/config/ai/fetch-models",
      { method: "POST", body: JSON.stringify(data) },
    ),
  // Admin: aggregated LLM token usage for per-tenant billing
  getLLMUsage: (params?: {
    tenant?: string;
    model?: string;
    model_type?: string;
    from?: number;
    to?: number;
    limit?: number;
  }) => {
    const qs = new URLSearchParams();
    if (params?.tenant) qs.set("tenant", params.tenant);
    if (params?.model) qs.set("model", params.model);
    if (params?.model_type) qs.set("model_type", params.model_type);
    if (params?.from) qs.set("from", String(params.from));
    if (params?.to) qs.set("to", String(params.to));
    if (params?.limit) qs.set("limit", String(params.limit));
    const q = qs.toString();
    return request<{
      rows: {
        tenant_id: string;
        tenant_name: string;
        model: string;
        model_type: string;
        prompt_tokens: number;
        completion_tokens: number;
        total_tokens: number;
        cached_tokens: number;
        reasoning_tokens: number;
        call_count: number;
        last_at: number;
      }[];
      totals: {
        prompt_tokens: number;
        completion_tokens: number;
        total_tokens: number;
        cached_tokens: number;
        reasoning_tokens: number;
        call_count: number;
      };
    }>(`/api/admin/llm-usage${q ? `?${q}` : ""}`);
  },
  // Admin: aggregated media-generation usage (image / video / audio) for billing
  getMediaUsage: (params?: {
    tenant?: string;
    model?: string;
    media_type?: string;
    from?: number;
    to?: number;
    limit?: number;
  }) => {
    const qs = new URLSearchParams();
    if (params?.tenant) qs.set("tenant", params.tenant);
    if (params?.model) qs.set("model", params.model);
    if (params?.media_type) qs.set("media_type", params.media_type);
    if (params?.from) qs.set("from", String(params.from));
    if (params?.to) qs.set("to", String(params.to));
    if (params?.limit) qs.set("limit", String(params.limit));
    const q = qs.toString();
    return request<{
      rows: {
        tenant_id: string;
        tenant_name: string;
        model: string;
        media_type: string;
        count: number;
        duration_seconds: number;
        call_count: number;
        last_at: number;
      }[];
      totals: {
        count: number;
        duration_seconds: number;
        call_count: number;
      };
    }>(`/api/admin/media-usage${q ? `?${q}` : ""}`);
  },

  // Apps
  importMCP: (data: { url: string; headers?: Record<string, string> }) =>
    request<{
      server_name?: string;
      server_version?: string;
      tools: Array<{ name: string; description: string; command?: string; parameters?: any }>;
      truncated?: boolean;
    }>("/api/apps/import-mcp", { method: "POST", body: JSON.stringify(data) }),
  createApp: (data: any) =>
    request<any>("/api/apps", { method: "POST", body: JSON.stringify(data) }),
  listApps: (opts?: { listing?: string }) =>
    request<any[]>(`/api/apps${opts?.listing ? `?listing=${opts.listing}` : ""}`),
  getApp: (id: string) => request<any>(`/api/apps/${id}`),
  updateApp: (id: string, data: any) =>
    request<any>(`/api/apps/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  verifyAppUrl: (appId: string) =>
    request<any>(`/api/apps/${appId}/verify-url`, { method: "POST" }),
  deleteApp: (id: string) => request(`/api/apps/${id}`, { method: "DELETE" }),

  // Admin: Apps
  adminListApps: () => request<any[]>("/api/admin/apps"),
  setAppListing: (id: string, listing: string) =>
    request(`/api/admin/apps/${id}/listing`, { method: "PUT", body: JSON.stringify({ listing }) }),

  // App Installations
  installApp: (appId: string, data: any) =>
    request<any>(`/api/apps/${appId}/install`, { method: "POST", body: JSON.stringify(data) }),
  listInstallations: (appId: string) => request<any[]>(`/api/apps/${appId}/installations`),
  getInstallation: (appId: string, iid: string) =>
    request<any>(`/api/apps/${appId}/installations/${iid}`),
  updateInstallation: (appId: string, iid: string, data: any) =>
    request<any>(`/api/apps/${appId}/installations/${iid}`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),
  deleteInstallation: (appId: string, iid: string) =>
    request(`/api/apps/${appId}/installations/${iid}`, { method: "DELETE" }),
  regenerateToken: (appId: string, iid: string) =>
    request<any>(`/api/apps/${appId}/installations/${iid}/regenerate-token`, { method: "POST" }),
  listEventLogs: (appId: string, iid: string, limit = 50) =>
    request<any[]>(`/api/apps/${appId}/installations/${iid}/event-logs?limit=${limit}`),
  listApiLogs: (appId: string, iid: string, limit = 50) =>
    request<any[]>(`/api/apps/${appId}/installations/${iid}/api-logs?limit=${limit}`),

  // Listing
  requestListing: (appId: string) =>
    request(`/api/apps/${appId}/request-listing`, { method: "POST" }),
  reviewListing: (appId: string, approve: boolean, reason?: string) =>
    request(`/api/admin/apps/${appId}/review-listing`, {
      method: "PUT",
      body: JSON.stringify({ approve, reason: reason || "" }),
    }),
  listAppReviews: (appId: string) => request<any[]>(`/api/apps/${appId}/reviews`),

  // Webhook logs
  webhookLogs: (botId: string, channelId?: string, limit = 50) =>
    request<any[]>(
      `/api/bots/${botId}/webhook-logs?limit=${limit}${channelId ? "&channel_id=" + channelId : ""}`,
    ),

  // Marketplace
  getMarketplaceApps: () => request<any[]>("/api/marketplace"),
  getBuiltinApps: () => request<any[]>("/api/marketplace/builtin"),
  syncMarketplaceApp: (slug: string) =>
    request<any>(`/api/marketplace/sync/${slug}`, { method: "POST" }),

  // Registry admin
  getRegistries: () => request<any[]>("/api/admin/registries"),
  createRegistry: (data: { name: string; url: string }) =>
    request<any>("/api/admin/registries", { method: "POST", body: JSON.stringify(data) }),
  updateRegistry: (id: string, data: { enabled: boolean }) =>
    request<any>(`/api/admin/registries/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteRegistry: (id: string) => request<any>(`/api/admin/registries/${id}`, { method: "DELETE" }),

  // Registry config
  getRegistryConfig: () => request<any>("/api/admin/config/registry"),
  setRegistryConfig: (data: { enabled: string }) =>
    request<any>("/api/admin/config/registry", { method: "PUT", body: JSON.stringify(data) }),

  // Registration config
  getRegistrationConfig: () => request<{ enabled: string }>("/api/admin/config/registration"),
  setRegistrationConfig: (data: { enabled: string }) =>
    request<any>("/api/admin/config/registration", { method: "PUT", body: JSON.stringify(data) }),

  // Scan login role config
  getScanLoginRoleConfig: () => request<{ role: string }>("/api/admin/config/scan_login_role"),
  setScanLoginRoleConfig: (data: { role: string }) =>
    request<any>("/api/admin/config/scan_login_role", {
      method: "PUT",
      body: JSON.stringify(data),
    }),

  // Admin: Dashboard
  adminStats: () => request<any>("/api/admin/stats"),

  // Admin: Users
  listUsers: () => request<any[]>("/api/admin/users"),
  createUser: (data: { username: string; password: string; role?: string }) =>
    request("/api/admin/users", { method: "POST", body: JSON.stringify(data) }),
  updateUserRole: (id: string, role: string) =>
    request(`/api/admin/users/${id}/role`, { method: "PUT", body: JSON.stringify({ role }) }),
  updateUserStatus: (id: string, status: string) =>
    request(`/api/admin/users/${id}/status`, { method: "PUT", body: JSON.stringify({ status }) }),
  resetUserPassword: (id: string) =>
    request<{ password: string }>(`/api/admin/users/${id}/password`, { method: "PUT" }),
  deleteUser: (id: string) => request(`/api/admin/users/${id}`, { method: "DELETE" }),

  // 甲乙方 AI 对聊 (tenant-chat builtin app): 甲/乙 = 两个真实扫码 iLink 用户
  tenantChatMine: () => request<any>("/api/tenant-chat/conversations/mine"),
  tenantChatGet: (id: string) => request<any>(`/api/tenant-chat/conversations/${id}`),
  // 被动会话（别人找你聊）参数设置
  tenantChatPassiveGet: () => request<any>("/api/tenant-chat/passive"),
  tenantChatPassiveSet: (data: {
    enabled: boolean;
    handle?: string;
    name?: string;
    system_prompt?: string;
    topic?: string;
    max_rounds?: number;
    delay_ms?: number;
  }) => request("/api/tenant-chat/passive", { method: "PUT", body: JSON.stringify(data) }),
  // 列出已开启被动会话的用户（供「找人聊」发现页使用）
  tenantChatPassiveUsers: () => request<any>("/api/tenant-chat/passive/users"),
  // 向某个已开启被动会话的用户发起对聊（target_user_id 由发起页提供）
  tenantChatStartPassive: (targetUserId: string) =>
    request<any>("/api/tenant-chat/conversations/start-passive", {
      method: "POST",
      body: JSON.stringify({ target_user_id: targetUserId }),
    }),
  tenantChatCreate: () => request<any>("/api/tenant-chat/conversations", { method: "POST" }),
  tenantChatJoin: (id: string, code: string) =>
    request<any>("/api/tenant-chat/conversations/join", {
      method: "POST",
      body: JSON.stringify({ id, code }),
    }),
  tenantChatControl: (id: string, action: "start" | "pause" | "step" | "reset") =>
    request(`/api/tenant-chat/conversations/${id}/control`, {
      method: "POST",
      body: JSON.stringify({ action }),
    }),
  tenantChatSetPersona: (id: string, name: string, system_prompt: string) =>
    request(`/api/tenant-chat/conversations/${id}/persona`, {
      method: "PUT",
      body: JSON.stringify({ name, system_prompt }),
    }),
  tenantChatSetConfig: (
    id: string,
    data: { topic?: string; max_rounds?: number; delay_ms?: number },
  ) =>
    request(`/api/tenant-chat/conversations/${id}/config`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),

  // 供采市场 (supply-market builtin app)
  supplyCategories: () => request<string[]>("/api/supply-market/categories"),
  supplyMyItems: (params?: { state?: string; item_type?: string }) =>
    request<any[]>(
      `/api/supply-market/items${
        params ? `?${new URLSearchParams(params as Record<string, string>)}` : ""
      }`,
    ),
  supplyPublish: (data: {
    item_type: string;
    title: string;
    description: string;
    category: string;
    price: number;
    currency: string;
    location: string;
    contact: string;
  }) =>
    request<any>("/api/supply-market/items", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  supplyGet: (id: string) => request<any>(`/api/supply-market/items/${id}`),
  supplyClarify: (id: string, answers: { qid: string; text: string }[]) =>
    request<any>(`/api/supply-market/items/${id}/clarify`, {
      method: "POST",
      body: JSON.stringify({ answers }),
    }),
  supplyClose: (id: string) =>
    request(`/api/supply-market/items/${id}/close`, { method: "POST" }),
  supplyDelete: (id: string) =>
    request(`/api/supply-market/items/${id}`, { method: "DELETE" }),
  supplyMarketplace: (params?: {
    item_type?: string;
    category?: string;
    location?: string;
    price_min?: number;
    price_max?: number;
    limit?: number;
  }) =>
    request<any[]>(
      `/api/supply-market/marketplace${
        params ? `?${new URLSearchParams(params as Record<string, string>)}` : ""
      }`,
    ),
  supplyMatch: (itemId: string, limit?: number) =>
    request<any[]>(
      `/api/supply-market/match?item_id=${encodeURIComponent(itemId)}${limit ? `&limit=${limit}` : ""}`,
    ),
  supplyChatsMine: () => request<any[]>("/api/supply-market/chats/mine"),
  supplyChatStart: (itemId: string) =>
    request<any>("/api/supply-market/chats", {
      method: "POST",
      body: JSON.stringify({ item_id: itemId }),
    }),
  supplyChatGet: (id: string) => request<any>(`/api/supply-market/chats/${id}`),
  supplyChatSend: (id: string, text: string) =>
    request<any>(`/api/supply-market/chats/${id}/messages`, {
      method: "POST",
      body: JSON.stringify({ text }),
    }),

  // ==================== 技能市场 ====================
  listSkills: (params?: SkillListParams) => {
    const qs = new URLSearchParams();
    if (params?.q) qs.set("q", params.q);
    if (params?.category) qs.set("category", params.category);
    if (params?.sort) qs.set("sort", params.sort);
    if (params?.mine) qs.set("mine", "1");
    if (params?.listing) qs.set("listing", params.listing);
    const suffix = qs.toString();
    return request<Skill[]>(`/api/skills${suffix ? `?${suffix}` : ""}`);
  },
  getSkill: (id: string) => request<SkillDetail>(`/api/skills/${id}`),
  listSkillVersions: (id: string) => request<SkillVersion[]>(`/api/skills/${id}/versions`),
  listSkillRatings: (id: string) => request<SkillRating[]>(`/api/skills/${id}/ratings`),
  deleteSkill: (id: string) => request(`/api/skills/${id}`, { method: "DELETE" }),

  /** Submit a zip bundle for review (multipart upload). */
  submitSkillBundle: async (file: File, fields: SkillSubmitFields = {}) => {
    const form = new FormData();
    form.append("bundle", file);
    for (const [k, v] of Object.entries(fields)) {
      if (v) form.append(k, v);
    }
    const res = await fetch("/api/skills/submit", {
      method: "POST",
      credentials: "same-origin",
      body: form,
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data as SkillSubmitResult;
  },
  /** Import a skill from GitHub / any HTTPS URL and submit it for review. */
  importSkill: (source_url: string, fields: SkillSubmitFields = {}) =>
    request<SkillSubmitResult>("/api/skills/submit", {
      method: "POST",
      body: JSON.stringify({ source_url, ...fields }),
    }),
  cancelSkillVersion: (skillId: string, versionId: string) =>
    request(`/api/skills/${skillId}/versions/${versionId}/cancel`, { method: "POST" }),
  rateSkill: (id: string, rating: number, comment?: string) =>
    request<{ rating_avg: number; rating_count: number }>(`/api/skills/${id}/rating`, {
      method: "PUT",
      body: JSON.stringify({ rating, comment: comment || "" }),
    }),
  deleteSkillRating: (id: string) => request(`/api/skills/${id}/rating`, { method: "DELETE" }),
  installSkill: (id: string, agent_id?: string) =>
    request<{ version: string; download_url: string }>(`/api/skills/${id}/install`, {
      method: "POST",
      body: JSON.stringify({ agent_id: agent_id || "" }),
    }),
  uninstallSkill: (id: string, agent_id?: string) =>
    request(`/api/skills/${id}/install?agent_id=${encodeURIComponent(agent_id || "")}`, {
      method: "DELETE",
    }),
  mySkillInstalls: () => request<SkillInstall[]>("/api/me/skill-installs"),
  skillDownloadURL: (skillId: string, versionId: string) =>
    `/api/skills/${skillId}/versions/${versionId}/download`,

  // Admin: skill marketplace
  adminListSkills: (listing?: string) =>
    request<Skill[]>(`/api/admin/skills${listing ? `?listing=${listing}` : ""}`),
  adminPendingSkillVersions: () => request<SkillVersion[]>("/api/admin/skills/pending"),
  reviewSkillVersion: (versionId: string, status: "approved" | "rejected", reason?: string) =>
    request(`/api/admin/skills/versions/${versionId}/review`, {
      method: "PUT",
      body: JSON.stringify({ status, reason: reason || "" }),
    }),
  setSkillListing: (id: string, listing: "listed" | "unlisted", reason?: string) =>
    request(`/api/admin/skills/${id}/listing`, {
      method: "PUT",
      body: JSON.stringify({ listing, reason: reason || "" }),
    }),
  adminDeleteSkill: (id: string) => request(`/api/admin/skills/${id}`, { method: "DELETE" }),
};
