// @vitest-environment jsdom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import {
  normalizeApprovalStatus,
  registerDevice,
  checkBindStatus,
  ensureDeviceToken,
  renewDeviceToken,
  getDeviceApprovalStatus,
  isAuthTokenInvalid,
  refreshDeviceToken,
  mcpCallWithRefresh,
} from './device';
import { useDeviceStatusStore } from '@/shared/stores/deviceStatusStore';

const invokeMock = vi.hoisted(() => vi.fn());

vi.mock('./invoke', () => ({
  invoke: (...args: unknown[]) => invokeMock(...args),
}));

const DEVICE_TOKEN_KEY = 'trae_device_token';
const DEVICE_APPROVAL_KEY = 'trae_device_approval_status';
const DEVICE_BIND_REQUEST_KEY = 'trae_device_bind_request_id';

beforeEach(() => {
  invokeMock.mockReset();
  localStorage.clear();
  // 重置设备状态 store，防止门控用例（pending_approval/rejected）跨用例泄漏
  useDeviceStatusStore.getState().reset();
});

afterEach(() => {
  localStorage.clear();
});

// ── normalizeApprovalStatus ──────────────────────────────────

describe('normalizeApprovalStatus', () => {
  it('returns "active" for "active"', () => {
    expect(normalizeApprovalStatus('active')).toBe('active');
  });
  it('returns "active" for "approved" (legacy compat)', () => {
    expect(normalizeApprovalStatus('approved')).toBe('active');
  });
  it('returns "pending_approval" for "pending_approval"', () => {
    expect(normalizeApprovalStatus('pending_approval')).toBe('pending_approval');
  });
  it('returns "pending_approval" for "pending" (legacy compat)', () => {
    expect(normalizeApprovalStatus('pending')).toBe('pending_approval');
  });
  it('returns "pending_approval" for "unbound" (Flow 1 unbound state)', () => {
    expect(normalizeApprovalStatus('unbound')).toBe('pending_approval');
  });
  it('returns "pending_approval" for "rejected" (server never returns rejected; converge to unbound)', () => {
    expect(normalizeApprovalStatus('rejected')).toBe('pending_approval');
  });
  it('returns "unknown" for empty string', () => {
    expect(normalizeApprovalStatus('')).toBe('unknown');
  });
  it('returns "unknown" for unrecognized value', () => {
    expect(normalizeApprovalStatus('something-else')).toBe('unknown');
  });
  it('is case-insensitive', () => {
    expect(normalizeApprovalStatus('PENDING')).toBe('pending_approval');
    expect(normalizeApprovalStatus('Approved')).toBe('active');
    expect(normalizeApprovalStatus('REJECTED')).toBe('pending_approval');
    expect(normalizeApprovalStatus('ACTIVE')).toBe('active');
    expect(normalizeApprovalStatus('PENDING_APPROVAL')).toBe('pending_approval');
    expect(normalizeApprovalStatus('UNBOUND')).toBe('pending_approval');
  });
});

// ── getDeviceApprovalStatus ───────────────────────────────

describe('getDeviceApprovalStatus', () => {
  it('returns "unknown" when store has no explicit status (regardless of localStorage token)', () => {
    // store 还没被 ensureDeviceToken 更新时返回 unknown（红灯），
    // 不读 localStorage，避免在 MCP 验证成功前误显示"审核中"。
    localStorage.setItem(DEVICE_TOKEN_KEY, 'some-token');
    expect(getDeviceApprovalStatus()).toBe('unknown');
  });
  
  it('returns "unknown" when no token exists', () => {
    localStorage.clear();
    expect(getDeviceApprovalStatus()).toBe('unknown');
  });
  
  it('returns "unknown" when token is empty', () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, '');
    expect(getDeviceApprovalStatus()).toBe('unknown');
  });
});

// ── registerDevice ───────────────────────────────────────────

describe('registerDevice', () => {
  it('calls invoke with joinCode', async () => {
    invokeMock.mockResolvedValueOnce({
      token: 'tok-abc',
      deviceId: 'dev-1',
      tenantId: 't-1',
      isNewDevice: true,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });

    const result = await registerDevice('12345678');
    expect(invokeMock).toHaveBeenCalledWith('register_device', { joinCode: '12345678' });
    expect(result.token).toBe('tok-abc');
    expect(result.approvalStatus).toBe('active');
  });

  it('propagates errors from backend', async () => {
    invokeMock.mockRejectedValueOnce(new Error('MCP bind failed'));
    await expect(registerDevice('bad')).rejects.toThrow('MCP bind failed');
  });

  it('returns pending status with requestId and empty token', async () => {
    invokeMock.mockResolvedValueOnce({
      token: '',
      deviceId: 'dev-2',
      tenantId: 't-2',
      isNewDevice: true,
      approvalStatus: 'pending_approval',
      nextStep: 'awaiting_approval',
      requestId: 'req-pending-001',
    });

    const result = await registerDevice('87654321');
    expect(result.token).toBe('');
    expect(result.approvalStatus).toBe('pending_approval');
    expect(result.requestId).toBe('req-pending-001');
  });
});

// ── checkBindStatus ──────────────────────────────────────────

describe('checkBindStatus', () => {
  it('passes requestId and deviceToken to invoke', async () => {
    invokeMock.mockResolvedValueOnce({ status: 'active', raw: {} });
    await checkBindStatus('req-1', 'tok-1');
    expect(invokeMock).toHaveBeenCalledWith('check_bind_status', {
      requestId: 'req-1',
      deviceToken: 'tok-1',
    });
  });

  it('allows empty deviceToken (pending bind without token)', async () => {
    invokeMock.mockResolvedValueOnce({ status: 'pending_approval', raw: {} });
    await checkBindStatus('req-1');
    expect(invokeMock).toHaveBeenCalledWith('check_bind_status', {
      requestId: 'req-1',
      deviceToken: '',
    });
  });

  it('falls back to localStorage token when deviceToken not provided', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'stored-tok');
    invokeMock.mockResolvedValueOnce({ status: 'active', raw: {} });
    await checkBindStatus('req-1');
    expect(invokeMock).toHaveBeenCalledWith('check_bind_status', {
      requestId: 'req-1',
      deviceToken: 'stored-tok',
    });
  });
});

// ── renewDeviceToken ─────────────────────────────────────────

describe('renewDeviceToken', () => {
  it('returns valid=true with new token when server rotates', async () => {
    invokeMock.mockResolvedValueOnce({ token: 'new-tok', valid: true });
    const result = await renewDeviceToken('old-tok');
    expect(result.valid).toBe(true);
    expect(result.token).toBe('new-tok');
  });

  it('returns valid=true with null token when server keeps same token', async () => {
    invokeMock.mockResolvedValueOnce({ token: null, valid: true });
    const result = await renewDeviceToken('same-tok');
    expect(result.valid).toBe(true);
    expect(result.token).toBeNull();
  });

  it('returns valid=false when server says token expired', async () => {
    invokeMock.mockResolvedValueOnce({ valid: false, reason: 'expired' });
    const result = await renewDeviceToken('old-tok');
    expect(result.valid).toBe(false);
    expect(result.token).toBeNull();
  });

  it('reads token from localStorage when not passed explicitly', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'stored-tok');
    invokeMock.mockResolvedValueOnce({ valid: true });
    const result = await renewDeviceToken();
    expect(invokeMock).toHaveBeenCalledWith('renew_device_token', {
      existingToken: 'stored-tok',
    });
    expect(result.valid).toBe(true);
  });

  it('returns valid=false when no token exists', async () => {
    const result = await renewDeviceToken();
    expect(result.valid).toBe(false);
    expect(result.token).toBeNull();
  });
});

// ── ensureDeviceToken ────────────────────────────────────────
//
// 2026-07-22 重构：验证从 client.renew 改为 tenant.get（不轮换 token）。
// 第二个 invoke 由 renew_device_token 变为 mcp_call_v2(action='tenant.get')。
// tenant.get 响应判定：
//   { ok: true }                                          → valid（已绑设备）
//   { ok: false, error: { code: 'device_not_bound' } }    → valid（auth 通过，未绑）
//   { ok: false, error: { code: 'auth.token_invalid' } }  → invalid
//   invoke reject + message 含 'HTTP 401/403'              → invalid
//   invoke reject + 非 401/403（网络/5xx/超时）             → 保守 valid（保留 token）

describe('ensureDeviceToken', () => {
  // ── 无 token 分支：fingerprint + tenant.get 验证 ──

  it('auto-registers via fingerprint + tenant.get verify when localStorage has no token', async () => {
    // 重装/升级后场景：localStorage 无 token，但服务器识别已审批设备
    // Step 1: registerDevice('') → fingerprint 返回 active + token
    invokeMock.mockResolvedValueOnce({
      token: 'new-tok-from-fp',
      deviceId: 'dev-1',
      tenantId: 't-1',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    // Step 2: mcp_call_v2 tenant.get → ok=true（设备已绑）
    invokeMock.mockResolvedValueOnce({ ok: true, data: { tags: ['acme'] } });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(true);
    expect(result.changed).toBe(true);
    expect(result.token).toBe('new-tok-from-fp');
    expect(result.skipped).toBe(false);
    // fingerprint 原始 token 原样写入（不轮换）
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('new-tok-from-fp');
    // 验证调用了两个 invoke：register_device + mcp_call_v2
    expect(invokeMock).toHaveBeenCalledTimes(2);
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'register_device', { joinCode: '' });
    expect(invokeMock).toHaveBeenNthCalledWith(2, 'mcp_call_v2', {
      action: 'tenant.get',
      params: {},
      token: 'new-tok-from-fp',
    });
  });

  it('does not write token when fingerprint active but tenant.get auth-rejects', async () => {
    // fingerprint 拿到 token，但 tenant.get 返回 auth.token_invalid → 不写 localStorage
    invokeMock.mockResolvedValueOnce({
      token: 'fp-tok-but-mcp-rejected',
      deviceId: 'dev-2',
      tenantId: 't-2',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    invokeMock.mockResolvedValueOnce({
      ok: false,
      data: null,
      error: { code: 'auth.token_invalid', message: 'invalid token' },
    });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(false);
    expect(result.changed).toBe(false);
    expect(result.token).toBe('fp-tok-but-mcp-rejected');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBeNull();
  });

  it('does not write token when fingerprint returns no token', async () => {
    // fingerprint 未签发 token（空字符串）→ 无法进行 MCP 验证
    invokeMock.mockResolvedValueOnce({
      token: '',
      deviceId: 'dev-3',
      tenantId: 't-3',
      isNewDevice: true,
      approvalStatus: 'pending_approval',
      nextStep: 'awaiting_approval',
      requestId: 'req-1',
    });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(false);
    expect(result.changed).toBe(false);
    expect(result.token).toBeNull();
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBeNull();
    // 无 token 时不调 tenant.get（直接返回）
    expect(invokeMock).toHaveBeenCalledTimes(1);
  });

  it('auto-registers when fingerprint returns pending but tenant.get auth-passes (device_not_bound)', async () => {
    // fingerprint 返回 pending + token，tenant.get 返回 device_not_bound
    // → auth 已通过（token 有效），置绿写入 localStorage（无需 join_code）
    invokeMock.mockResolvedValueOnce({
      token: 'pending-but-mcp-ok-tok',
      deviceId: 'dev-pending',
      tenantId: 't-pending',
      isNewDevice: true,
      approvalStatus: 'pending_approval',
      nextStep: 'mcp_verify',
      requestId: null,
    });
    // tenant.get: auth 通过但设备未绑 → valid
    invokeMock.mockResolvedValueOnce({
      ok: false,
      data: null,
      error: { code: 'device_not_bound', message: 'device not bound' },
    });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(true);
    expect(result.changed).toBe(true);
    expect(result.token).toBe('pending-but-mcp-ok-tok');
    expect(result.skipped).toBe(false);
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('pending-but-mcp-ok-tok');
    expect(invokeMock).toHaveBeenCalledTimes(2);
    expect(invokeMock).toHaveBeenNthCalledWith(2, 'mcp_call_v2', {
      action: 'tenant.get',
      params: {},
      token: 'pending-but-mcp-ok-tok',
    });
  });

  it('does not write token when fingerprint returns pending + token but tenant.get auth-rejects', async () => {
    invokeMock.mockResolvedValueOnce({
      token: 'pending-mcp-rejected-tok',
      deviceId: 'dev-pending-rej',
      tenantId: 't-pending-rej',
      isNewDevice: true,
      approvalStatus: 'pending_approval',
      nextStep: 'mcp_verify',
      requestId: null,
    });
    invokeMock.mockResolvedValueOnce({
      ok: false,
      data: null,
      error: { code: 'auth.token_invalid', message: 'invalid token' },
    });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(false);
    expect(result.changed).toBe(false);
    expect(result.token).toBe('pending-mcp-rejected-tok');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBeNull();
  });

  it('does not write token when fingerprint network fails', async () => {
    invokeMock.mockRejectedValueOnce(new Error('network timeout'));

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(false);
    expect(result.changed).toBe(false);
    expect(result.token).toBeNull();
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBeNull();
  });

  it('keeps fingerprint original token (no rotation) when tenant.get verifies', async () => {
    // 关键回归：tenant.get 不轮换 token，fingerprint 原始 token 原样写入
    invokeMock.mockResolvedValueOnce({
      token: 'fp-tok-original',
      deviceId: 'dev-4',
      tenantId: 't-4',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(true);
    expect(result.changed).toBe(true);
    expect(result.token).toBe('fp-tok-original');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('fp-tok-original');
  });

  // ── 有 token 分支：tenant.get 验证 existing ──

  it('keeps existing token unchanged when tenant.get verifies (no rotation)', async () => {
    // 退出重启后 token 仍有效 → 保留原 token，不写 localStorage，不轮换
    localStorage.setItem(DEVICE_TOKEN_KEY, 'same-tok');
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(true);
    expect(result.changed).toBe(false);
    expect(result.token).toBe('same-tok');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('same-tok');
    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'mcp_call_v2', {
      action: 'tenant.get',
      params: {},
      token: 'same-tok',
    });
  });

  it('keeps existing token when tenant.get returns device_not_bound (auth passed)', async () => {
    // auth 通过、设备未绑 → token 有效，保留原 token
    localStorage.setItem(DEVICE_TOKEN_KEY, 'unbound-tok');
    invokeMock.mockResolvedValueOnce({
      ok: false,
      data: null,
      error: { code: 'device_not_bound', message: 'device not bound' },
    });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(true);
    expect(result.changed).toBe(false);
    expect(result.token).toBe('unbound-tok');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('unbound-tok');
  });

  it('clears token when tenant.get returns auth.token_invalid', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'expired-tok');
    // tenant.get 判 token 无效
    invokeMock.mockResolvedValueOnce({
      ok: false,
      data: null,
      error: { code: 'auth.token_invalid', message: 'invalid token' },
    });
    // 旧 token 失效后触发自动 fingerprint + tenant.get 验证（这里模拟 fingerprint 失败）
    invokeMock.mockRejectedValueOnce(new Error('fingerprint network error'));

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(false);
    expect(result.changed).toBe(true);
    expect(result.token).toBeNull();
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBeNull();
  });

  it('clears token when tenant.get returns HTTP 401 (invoke rejects)', async () => {
    // 后端 mcp_call_v2 在 HTTP 401 时 reject，message 含 "HTTP 401"
    localStorage.setItem(DEVICE_TOKEN_KEY, 'tok-401');
    invokeMock.mockRejectedValueOnce(new Error('MCP tenant.get returned HTTP 401'));
    // 清空后 fingerprint 重注册（模拟失败）
    invokeMock.mockRejectedValueOnce(new Error('fingerprint network error'));

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(false);
    expect(result.changed).toBe(true);
    expect(result.token).toBeNull();
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBeNull();
  });

  it('re-registers via fingerprint + tenant.get when old token is rejected', async () => {
    // 退出重启后旧 token 被服务器拒绝 → 自动 fingerprint + tenant.get 验证重注册
    localStorage.setItem(DEVICE_TOKEN_KEY, 'rejected-tok');
    // tenant.get 判 token 无效
    invokeMock.mockResolvedValueOnce({
      ok: false,
      data: null,
      error: { code: 'auth.token_invalid', message: 'invalid token' },
    });
    // fingerprint 重注册成功
    invokeMock.mockResolvedValueOnce({
      token: 'new-tok-after-reject',
      deviceId: 'dev-5',
      tenantId: 't-5',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    // tenant.get 验证新 token 成功
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(true);
    expect(result.changed).toBe(true);
    expect(result.token).toBe('new-tok-after-reject');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('new-tok-after-reject');
    expect(invokeMock).toHaveBeenCalledTimes(3);
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'mcp_call_v2', {
      action: 'tenant.get',
      params: {},
      token: 'rejected-tok',
    });
    expect(invokeMock).toHaveBeenNthCalledWith(2, 'register_device', { joinCode: '' });
    expect(invokeMock).toHaveBeenNthCalledWith(3, 'mcp_call_v2', {
      action: 'tenant.get',
      params: {},
      token: 'new-tok-after-reject',
    });
  });

  it('keeps existing token when tenant.get has network error (conservative valid)', async () => {
    // 网络/5xx/超时（非 401/403）→ 保守 valid=true，保留旧 token，不登出用户
    localStorage.setItem(DEVICE_TOKEN_KEY, 'tok-net-err');
    invokeMock.mockRejectedValueOnce(new Error('network timeout'));

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(true);
    expect(result.changed).toBe(false);
    expect(result.token).toBe('tok-net-err');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('tok-net-err');
  });

  it('never throws — catches IPC errors gracefully', async () => {
    // invoke reject 非 401/403（如 IPC panic）→ verifyTokenViaTenantGet 保守 valid=true
    localStorage.setItem(DEVICE_TOKEN_KEY, 'tok-ipc-err');
    invokeMock.mockRejectedValueOnce(new Error('IPC panic'));

    const result = await ensureDeviceToken();
    expect(result.valid).toBe(true);
    expect(result.changed).toBe(false);
    expect(result.token).toBe('tok-ipc-err');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('tok-ipc-err');
  });
});

// ── 审批状态完整流程 ────────────────────────────────────────

describe('device registration full flow', () => {
  it('register pending_approval → poll → active → token available', async () => {
    // Step 1: register returns pending with requestId, no token
    invokeMock.mockResolvedValueOnce({
      token: '',
      deviceId: 'dev-x',
      tenantId: 't-x',
      isNewDevice: true,
      approvalStatus: 'pending_approval',
      nextStep: 'awaiting_approval',
      requestId: 'req-42',
    });

    const reg = await registerDevice('join-code-1');
    expect(reg.token).toBe('');
    expect(reg.requestId).toBe('req-42');
    expect(reg.approvalStatus).toBe('pending_approval');

    // No persistence - component keeps state in memory only

    // Step 2: poll — still pending
    invokeMock.mockResolvedValueOnce({ status: 'pending_approval', raw: {} });
    const poll1 = await checkBindStatus('req-42');
    expect(poll1.status).toBe('pending_approval');

    // Step 3: poll — active (server returns token now)
    invokeMock.mockResolvedValueOnce({
      status: 'active',
      device_token: 'issued-tok-99',
    });
    const poll2 = await checkBindStatus('req-42', 'issued-tok-99');
    expect(poll2.status).toBe('active');
  });

  it('register active with token → immediate use', async () => {
    invokeMock.mockResolvedValueOnce({
      token: 'immediate-tok',
      deviceId: 'dev-y',
      tenantId: 't-y',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });

    const reg = await registerDevice('join-code-2');
    expect(reg.token).toBe('immediate-tok');
    expect(reg.approvalStatus).toBe('active');
    expect(reg.requestId).toBeNull();

    // ensureDeviceToken should work with the stored token
    localStorage.setItem(DEVICE_TOKEN_KEY, reg.token);
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });
    const ensure = await ensureDeviceToken();
    expect(ensure.valid).toBe(true);
    expect(ensure.changed).toBe(false);
  });

  it('register active without token → treated as error', async () => {
    invokeMock.mockResolvedValueOnce({
      token: '',
      deviceId: 'dev-z',
      tenantId: 't-z',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });

    const reg = await registerDevice('join-code-3');
    // status is "active" and token is empty and no requestId — this is a real error
    const isPending = reg.approvalStatus === 'pending_approval' || Boolean(reg.requestId);
    expect(isPending).toBe(false);
    expect(reg.token).toBe('');
  });

  it('register with empty join code for active device', async () => {
    // 已审批设备使用空join code也可以正常工作
    invokeMock.mockResolvedValueOnce({
      token: 'existing-token-xyz',
      deviceId: 'dev-active',
      tenantId: 't-active',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });

    const reg = await registerDevice('');
    expect(reg.token).toBe('existing-token-xyz');
    expect(reg.approvalStatus).toBe('active');
    expect(reg.requestId).toBeNull();
  });
});

// ── isAuthTokenInvalid ──────────────────────────────────────

describe('isAuthTokenInvalid', () => {
  it('returns true for MCP envelope with auth.token_invalid error code', () => {
    expect(
      isAuthTokenInvalid({ ok: false, error: { code: 'auth.token_invalid', message: 'invalid' } }),
    ).toBe(true);
  });

  it('returns true for MCP envelope with unauthorized error code', () => {
    expect(
      isAuthTokenInvalid({ ok: false, error: { code: 'unauthorized', message: 'no access' } }),
    ).toBe(true);
  });

  it('returns false for MCP envelope with device_not_bound error code', () => {
    expect(
      isAuthTokenInvalid({ ok: false, error: { code: 'device_not_bound', message: 'not bound' } }),
    ).toBe(false);
  });

  it('returns false for MCP envelope with ok=true', () => {
    expect(isAuthTokenInvalid({ ok: true, data: {} })).toBe(false);
  });

  it('returns true for Error with HTTP 401 message', () => {
    expect(isAuthTokenInvalid(new Error('MCP tenant.get returned HTTP 401'))).toBe(true);
  });

  it('returns true for Error with auth.token_invalid message', () => {
    expect(isAuthTokenInvalid(new Error('auth.token_invalid: token expired'))).toBe(true);
  });

  it('returns false for Error with network timeout message', () => {
    expect(isAuthTokenInvalid(new Error('network timeout'))).toBe(false);
  });

  it('returns true for string containing HTTP 401', () => {
    expect(
      isAuthTokenInvalid('{"code":"upstream_http_error","message":"HTTP 401"}'),
    ).toBe(true);
  });

  it('returns false for null/undefined', () => {
    expect(isAuthTokenInvalid(null)).toBe(false);
    expect(isAuthTokenInvalid(undefined)).toBe(false);
  });
});

// ── refreshDeviceToken ─────────────────────────────────────

describe('refreshDeviceToken', () => {
  it('refreshes via fingerprint + tenant.get verify on success', async () => {
    // fingerprint
    invokeMock.mockResolvedValueOnce({
      token: 'refreshed-tok',
      deviceId: 'dev-r',
      tenantId: 't-r',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    // tenant.get verify
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });

    const result = await refreshDeviceToken();
    expect(result.success).toBe(true);
    expect(result.token).toBe('refreshed-tok');
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBe('refreshed-tok');
    expect(invokeMock).toHaveBeenCalledTimes(2);
  });

  it('singleton: concurrent calls share one fingerprint (not two)', async () => {
    // Only ONE set of mocks — both concurrent calls should share the same Promise
    invokeMock.mockResolvedValueOnce({
      token: 'singleton-tok',
      deviceId: 'dev-s',
      tenantId: 't-s',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });

    const [r1, r2] = await Promise.all([refreshDeviceToken(), refreshDeviceToken()]);
    expect(r1.success).toBe(true);
    expect(r2.success).toBe(true);
    expect(r1.token).toBe('singleton-tok');
    expect(r2.token).toBe('singleton-tok');
    // Only 2 invoke calls total (1 fingerprint + 1 tenant.get), NOT 4
    expect(invokeMock).toHaveBeenCalledTimes(2);
  });

  it('returns failure when fingerprint network fails', async () => {
    invokeMock.mockRejectedValueOnce(new Error('network timeout'));

    const result = await refreshDeviceToken();
    expect(result.success).toBe(false);
    expect(result.token).toBeNull();
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBeNull();
  });

  it('returns failure when fingerprint succeeds but tenant.get auth-rejects', async () => {
    invokeMock.mockResolvedValueOnce({
      token: 'fp-tok-but-rejected',
      deviceId: 'dev-x',
      tenantId: 't-x',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    invokeMock.mockResolvedValueOnce({
      ok: false,
      error: { code: 'auth.token_invalid', message: 'invalid' },
    });

    const result = await refreshDeviceToken();
    expect(result.success).toBe(false);
    expect(localStorage.getItem(DEVICE_TOKEN_KEY)).toBeNull();
  });
});

// ── mcpCallWithRefresh ─────────────────────────────────────

describe('mcpCallWithRefresh', () => {
  it('returns response directly when first call succeeds (ok=true)', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'valid-tok');
    invokeMock.mockResolvedValueOnce({ ok: true, data: { items: [] } });

    const r = await mcpCallWithRefresh('skill.search', { query: 'test' });
    expect(r.ok).toBe(true);
    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(invokeMock).toHaveBeenCalledWith('mcp_call_v2', {
      action: 'skill.search',
      params: { query: 'test' },
      token: 'valid-tok',
    });
  });

  it('refreshes and retries when first call returns auth.token_invalid', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'expired-tok');
    // First call: auth error
    invokeMock.mockResolvedValueOnce({
      ok: false,
      error: { code: 'auth.token_invalid', message: 'invalid token' },
    });
    // Refresh: fingerprint
    invokeMock.mockResolvedValueOnce({
      token: 'new-tok',
      deviceId: 'dev-r',
      tenantId: 't-r',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    // Refresh: tenant.get verify
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });
    // Retry with new token: success
    invokeMock.mockResolvedValueOnce({ ok: true, data: { items: ['skill1'] } });

    const r = await mcpCallWithRefresh('skill.search', { query: 'test' });
    expect(r.ok).toBe(true);
    expect(r.data.items).toEqual(['skill1']);
    expect(invokeMock).toHaveBeenCalledTimes(4);
    // Last call should use the refreshed token
    expect(invokeMock).toHaveBeenNthCalledWith(4, 'mcp_call_v2', {
      action: 'skill.search',
      params: { query: 'test' },
      token: 'new-tok',
    });
  });

  it('returns original auth error response when refresh fails', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'expired-tok');
    // First call: auth error
    invokeMock.mockResolvedValueOnce({
      ok: false,
      error: { code: 'auth.token_invalid', message: 'invalid token' },
    });
    // Refresh: fingerprint fails
    invokeMock.mockRejectedValueOnce(new Error('network timeout'));

    const r = await mcpCallWithRefresh('skill.search', { query: 'test' });
    expect(r.ok).toBe(false);
    expect(r.error.code).toBe('auth.token_invalid');
    expect(invokeMock).toHaveBeenCalledTimes(2); // 1 mcp_call + 1 failed fingerprint
  });

  it('returns response directly for non-auth error (device_not_bound)', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'valid-tok');
    invokeMock.mockResolvedValueOnce({
      ok: false,
      error: { code: 'device_not_bound', message: 'not bound' },
    });

    const r = await mcpCallWithRefresh('skill.search', { query: 'test' });
    expect(r.ok).toBe(false);
    expect(r.error.code).toBe('device_not_bound');
    expect(invokeMock).toHaveBeenCalledTimes(1); // No refresh attempt
  });

  it('refreshes and retries when invoke rejects with HTTP 401', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'expired-tok');
    // First call: invoke rejects with HTTP 401
    invokeMock.mockRejectedValueOnce(new Error('MCP skill.search returned HTTP 401'));
    // Refresh: fingerprint
    invokeMock.mockResolvedValueOnce({
      token: 'new-tok',
      deviceId: 'dev-r',
      tenantId: 't-r',
      isNewDevice: false,
      approvalStatus: 'active',
      nextStep: null,
      requestId: null,
    });
    // Refresh: tenant.get verify
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });
    // Retry: success
    invokeMock.mockResolvedValueOnce({ ok: true, data: {} });

    const r = await mcpCallWithRefresh('skill.search', {});
    expect(r.ok).toBe(true);
    expect(invokeMock).toHaveBeenCalledTimes(4);
  });

  it('throws non-auth invoke errors without retry', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'valid-tok');
    invokeMock.mockRejectedValueOnce(new Error('network timeout'));

    await expect(mcpCallWithRefresh('skill.search', {})).rejects.toThrow('network timeout');
    expect(invokeMock).toHaveBeenCalledTimes(1);
  });

  // ── 白名单门控：pending_approval/rejected 设备调非白名单 action 时提前抛错 ──

  it('throws device.not_approved when pending_approval device calls non-whitelist action', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'pending-tok');
    useDeviceStatusStore.getState().setStatus({ approvalStatus: 'pending_approval', token: 'pending-tok' });
    // skill.search 非白名单 → 门控直接抛 Error（带 code），不应触达 invoke。
    // 必须是 Error 实例：调用方 catch 后 instanceof Error / .message / String(e) 才能拿到
    // 可读消息，否则 searchAllSkills 会显示 "[object Object]"。
    const p = mcpCallWithRefresh('skill.search', { query: 'x' });
    await expect(p).rejects.toBeInstanceOf(Error);
    await expect(p).rejects.toMatchObject({
      code: 'device.not_approved',
      message: '设备未审批通过，此功能暂不可用',
    });
    expect(invokeMock).not.toHaveBeenCalled();
  });

  it('throws device.not_approved when rejected device calls non-whitelist action', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'rejected-tok');
    useDeviceStatusStore.getState().setStatus({ approvalStatus: 'rejected', token: 'rejected-tok' });
    const p = mcpCallWithRefresh('skill.search', {});
    await expect(p).rejects.toBeInstanceOf(Error);
    await expect(p).rejects.toMatchObject({ code: 'device.not_approved' });
    expect(invokeMock).not.toHaveBeenCalled();
  });

  it('allows pending_approval device to call whitelist action (tenant.get)', async () => {
    localStorage.setItem(DEVICE_TOKEN_KEY, 'pending-tok');
    useDeviceStatusStore.getState().setStatus({ approvalStatus: 'pending_approval', token: 'pending-tok' });
    invokeMock.mockResolvedValueOnce({ ok: true, data: { tenant_id: '' } });
    // tenant.get 在白名单内 → 门控放行，正常 invoke
    const r = await mcpCallWithRefresh('tenant.get', {});
    expect(r.ok).toBe(true);
    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(invokeMock).toHaveBeenCalledWith('mcp_call_v2', {
      action: 'tenant.get',
      params: {},
      token: 'pending-tok',
    });
  });
});
