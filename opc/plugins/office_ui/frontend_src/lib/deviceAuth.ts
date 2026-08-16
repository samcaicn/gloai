// Device authorization client (参考 safeopcAPP device.ts).
//
// Contract对齐后端 opc/plugins/office_ui/auth_device.py：
//   registerDevice(joinCode)  → 指纹注册 / 获取 token / 提交验证码
//   checkBindStatus           → 轮询审批状态
//   verifyToken               → 只读校验 token 有效性（tenant.get 等价物）
//   ensureDeviceToken         → 启动静默注册 + 校验（绝不抛错）
//
// 客户端是被审核方：设备指纹注册后处于 pending_approval，需操作员审核通过
// （输入 join_code 或 dev-approve）变为 active，后端才会放行 LLM/MCP 执行。

import {
  deviceStatusStore,
  readDeviceToken,
  writeDeviceToken,
  clearDeviceToken,
  type DeviceApprovalStatus,
} from '../stores/deviceStatusStore';

// 同源：生产环境 SPA 由 office-ui 服务器托管，相对路径即同域。
// 开发环境可用 VITE_API_BASE 指向 office-ui 端口（如 http://localhost:8765）。
const ENV = (import.meta as any).env as Record<string, string | undefined> | undefined;
const API_BASE: string = ENV?.VITE_API_BASE || window.location.origin || '';

export type { DeviceApprovalStatus };

export interface RegisterResult {
  token: string;
  deviceId: string;
  tenantId: string | null;
  isNewDevice: boolean;
  approvalStatus: string;
  nextStep: string | null;
  requestId: string | null;
}

export interface BindStatusResult {
  status: string;
  valid: boolean;
}

export interface VerifyResult {
  valid: boolean;
  approvalStatus: string;
  tenantId: string | null;
}

/** 规范化后端审批状态词汇（服务器无 rejected，一切非 active 收敛为 pending_approval）。 */
export function normalizeApprovalStatus(raw: string | null | undefined): DeviceApprovalStatus {
  const lower = (raw || '').toLowerCase();
  if (lower === 'active' || lower === 'approved') return 'active';
  if (
    [
      'pending_approval',
      'pending',
      'unbound',
      'device_not_bound',
      'not_bound',
      'rejected',
      'reject',
      'declined',
      'denied',
      'disabled',
      'revoked',
    ].includes(lower)
  ) {
    return 'pending_approval';
  }
  return 'unknown';
}

async function postJson(path: string, body: Record<string, unknown>): Promise<any> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`HTTP ${res.status} ${text}`);
  }
  return res.json();
}

/** 指纹注册（joinCode 为空）或提交验证码（joinCode 非空）。 */
export async function registerDevice(joinCode: string): Promise<RegisterResult> {
  const result = (await postJson('/api/device/register', { joinCode })) as RegisterResult;
  const status = normalizeApprovalStatus(result.approvalStatus);
  if (result.token) writeDeviceToken(result.token);
  deviceStatusStore.setStatus({ approvalStatus: status, token: result.token ?? null });
  if (result.requestId) deviceStatusStore.setRequestId(result.requestId);
  return result;
}

/** 轮询绑定/审批状态。 */
export async function checkBindStatus(
  requestId: string,
  token?: string,
): Promise<BindStatusResult> {
  const t = token ?? readDeviceToken() ?? '';
  return postJson('/api/device/bind-status', { token: t, requestId }) as Promise<BindStatusResult>;
}

/** 只读校验 token 是否被服务端 auth 层放行（等价于 MCP tenant.get）。 */
export async function verifyToken(token?: string): Promise<VerifyResult> {
  const t = token ?? readDeviceToken() ?? '';
  return postJson('/api/device/verify', { token: t }) as Promise<VerifyResult>;
}

export function getDeviceApprovalStatus(): DeviceApprovalStatus {
  return deviceStatusStore.getSnapshot().approvalStatus;
}

/**
 * 启动时静默确保 token：无 token→指纹注册；有 token→校验。
 * 设计上绝不抛错——启动期任何网络问题都不应阻塞 UI。
 */
export async function ensureDeviceToken(): Promise<{
  token: string | null;
  valid: boolean;
  changed: boolean;
}> {
  const existing = readDeviceToken();
  try {
    if (!existing) {
      deviceStatusStore.setStatus({ approvalStatus: 'unknown', token: null });
      const r = await registerDevice('');
      if (r.token) {
        const v = await verifyToken(r.token);
        const status = normalizeApprovalStatus(v.approvalStatus);
        deviceStatusStore.setStatus({ approvalStatus: status, token: r.token });
        return { token: r.token, valid: status === 'active', changed: true };
      }
      deviceStatusStore.setStatus({ approvalStatus: 'pending_approval', token: null });
      return { token: null, valid: false, changed: false };
    }

    const v = await verifyToken(existing);
    if (!v.valid) {
      clearDeviceToken();
      deviceStatusStore.setStatus({ approvalStatus: 'unknown', token: null });
      const r = await registerDevice('');
      const status = r.token
        ? normalizeApprovalStatus((await verifyToken(r.token)).approvalStatus)
        : 'pending_approval';
      deviceStatusStore.setStatus({ approvalStatus: status, token: r.token ?? null });
      return { token: r.token ?? null, valid: status === 'active', changed: true };
    }
    const status = normalizeApprovalStatus(v.approvalStatus);
    deviceStatusStore.setStatus({ approvalStatus: status, token: existing });
    return { token: existing, valid: status === 'active', changed: false };
  } catch (e) {
    // 保守：保留旧 token，标记 unknown，绝不阻塞 UI。
    deviceStatusStore.setStatus({ approvalStatus: 'unknown', token: existing });
    return { token: existing, valid: false, changed: false };
  }
}

/** 提交验证码后轮询，直到 active 或超时。 */
export async function pollUntilApproved(
  requestId: string,
  token: string,
  timeoutMs = 5 * 60_000,
  intervalMs = 2000,
): Promise<DeviceApprovalStatus> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const r = await checkBindStatus(requestId, token);
    const status = normalizeApprovalStatus(r.status);
    if (status === 'active') return 'active';
    await new Promise((res) => setTimeout(res, intervalMs));
  }
  return 'pending_approval';
}
