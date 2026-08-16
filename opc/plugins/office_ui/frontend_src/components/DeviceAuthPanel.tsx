import { useEffect, useRef, useState } from 'react';
import { VerificationCodeInput } from './VerificationCodeInput';
import {
  ensureDeviceToken,
  getDeviceApprovalStatus,
  pollUntilApproved,
  registerDevice,
  verifyToken,
  type DeviceApprovalStatus,
} from '../lib/deviceAuth';
import { useDeviceStatus } from '../stores/deviceStatusStore';

interface DeviceAuthPanelProps {
  open: boolean;
  onClose: () => void;
}

const STATUS_LABEL: Record<DeviceApprovalStatus, string> = {
  active: '已激活 · 可使用',
  pending_approval: '待审核 · 未授权',
  rejected: '已拒绝',
  unknown: '未连接',
  unregistered: '未注册',
};

const STATUS_COLOR: Record<DeviceApprovalStatus, string> = {
  active: 'var(--device-ok, #2ecc71)',
  pending_approval: 'var(--device-warn, #f1c40f)',
  rejected: 'var(--device-warn, #f1c40f)',
  unknown: 'var(--device-bad, #e74c3c)',
  unregistered: 'var(--device-bad, #e74c3c)',
};

/**
 * 设备授权面板（设置内，默认隐藏，由 VITE_SHOW_DEVICE_AUTH 控制入口）。
 *
 * 能力：
 *  1. 注册本机指纹 → 获取 device_token（pending_approval）
 *  2. 输入验证码（join_code）→ 提交审核 / 自动激活
 *  3. 轮询审批状态直到 active
 *  4. active 后后端放行 MCP / LLM 执行
 */
export function DeviceAuthPanel({ open, onClose }: DeviceAuthPanelProps) {
  const { approvalStatus, token, requestId } = useDeviceStatus();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [polling, setPolling] = useState(false);
  const pollRef = useRef(false);

  // 打开时刷新一次状态。
  useEffect(() => {
    if (open) {
      setError(null);
      ensureDeviceToken().catch(() => undefined);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // 有 pending 的 requestId 时自动轮询。
  useEffect(() => {
    if (!open || !requestId || !token || approvalStatus === 'active') return;
    let cancelled = false;
    pollRef.current = true;
    setPolling(true);
    pollUntilApproved(requestId, token)
      .then(async (final) => {
        if (cancelled) return;
        if (final === 'active') {
          // 操作员审核通过后刷新 store（verify 会回写 active 状态）。
          await verifyToken(token).catch(() => undefined);
        }
      })
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) setPolling(false);
      });
    return () => {
      cancelled = true;
      pollRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, requestId, token, approvalStatus]);

  if (!open) return null;

  const handleRegisterFingerprint = async () => {
    setBusy(true);
    setError(null);
    try {
      await registerDevice('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const handleCodeComplete = async (code: string) => {
    if (!code) return;
    setBusy(true);
    setError(null);
    try {
      const r = await registerDevice(code);
      if (r.approvalStatus === 'active') {
        // 已激活
      } else if (r.requestId) {
        setPolling(true);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const status = getDeviceApprovalStatus();

  return (
    <div className="device-auth-overlay" role="dialog" aria-modal="true" aria-label="设备授权">
      <div className="device-auth-panel">
        <div className="device-auth-panel__head">
          <h2>设备授权</h2>
          <button className="icon-btn" onClick={onClose} aria-label="关闭" title="关闭">
            ✕
          </button>
        </div>

        <div className="device-auth-status">
          <span
            className="device-auth-dot"
            style={{ background: STATUS_COLOR[status] }}
            aria-hidden
          />
          <span className="device-auth-status__label">{STATUS_LABEL[status]}</span>
        </div>

        <p className="device-auth-desc">
          本机指纹需在操作员审核通过后，方可调用 MCP 工具与 LLM。未授权时对话与智能体执行将被服务端拦截。
        </p>

        {error && <div className="device-auth-error">{error}</div>}

        <div className="device-auth-section">
          <h3>1. 注册本机指纹</h3>
          <p className="device-auth-hint">
            生成本机唯一指纹并获取设备令牌（状态：待审核）。{token ? '指纹已注册。' : ''}
          </p>
          <button className="btn-primary" onClick={handleRegisterFingerprint} disabled={busy}>
            {token ? '重新注册指纹' : '注册本机指纹'}
          </button>
        </div>

        <div className="device-auth-section">
          <h3>2. 输入验证码（审核通过）</h3>
          <p className="device-auth-hint">
            向操作员获取验证码（join_code）。验证码有效将直接激活本设备；否则进入待审核轮询。
          </p>
          <VerificationCodeInput
            length={6}
            disabled={busy}
            onComplete={handleCodeComplete}
          />
          <p className="device-auth-hint device-auth-hint--muted">
            {polling ? '正在等待审核结果…' : '填满 6 位后自动提交。'}
          </p>
        </div>

        <div className="device-auth-foot">
          <span className="device-auth-foot__token">
            token: {token ? `${token.slice(0, 8)}…` : '（无）'}
          </span>
        </div>
      </div>
    </div>
  );
}

// 兼容默认导出命名（部分导入场景）。
export default DeviceAuthPanel;
