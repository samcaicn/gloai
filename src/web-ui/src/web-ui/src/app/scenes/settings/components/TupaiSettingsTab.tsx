/**
 * TupaiSettingsTab — 设备设置面板。
 *
 * 包含两个 section：
 *   1. 设备状态：通过桥接层 registerDevice(joinCode) 注册设备并管理 token
 *   2. 关于：版本信息
 *
 * 主题切换已移至右上角 SceneBar 按钮（深色/浅色一键切换）。
 *
 * 交互流程（2026-07-22 重构）：
 *   - 取消待审批：pending → 点"取消待审批" → 确认对话框 → 停轮询 + 清 store →
 *     状态变"未知" → 输入框可用
 *   - 直接重新绑定：pending → 直接在输入框输入新 join_code → 点"注册" →
 *     自动停旧轮询 → 新注册覆盖 → 新轮询启动
 */

import { useCallback, useState, useEffect, useRef, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input } from '@/component-library';
import { confirmWarning } from '@/component-library/components/ConfirmDialog/confirmService';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('TupaiSettingsTab');
import {
  ConfigPageContent,
  ConfigPageHeader,
  ConfigPageLayout,
  ConfigPageRow,
  ConfigPageSection,
} from '@/infrastructure/config/components/common';
import {
  registerDevice,
  checkBindStatus,
  normalizeApprovalStatus,
} from '@/infrastructure/api/tupai';
import { useDeviceStatusStore } from '@/shared/stores/deviceStatusStore';
import { notificationService } from '@/shared/notification-system';
import './TupaiSettingsTab.scss';

// ==================== 设备 token ====================

const DEVICE_TOKEN_KEY = 'trae_device_token';

// ==================== 版本信息 ====================

// 优先从构建期注入读取，缺省回退硬编码
const APP_VERSION: string = import.meta.env.VITE_APP_VERSION ?? '1.8.9';
const BUILD_TIME = '2026-07-09';

/** 从未知错误对象中安全提取 message */
function readErrorMessage(err: unknown): string {
  if (err && typeof err === 'object' && 'message' in err) {
    const m = (err as { message?: unknown }).message;
    if (typeof m === 'string' && m) return m;
  }
  if (typeof err === 'string' && err) return err;
  return 'Device registration failed';
}

// ==================== Section 1: 设备状态 ====================

/** 轮询间隔退避参数（ms）：初始 1s，每次翻倍。
 *  与后端 device_register.rs::RETRY_BASE_MS 保持一致（fingerprint 失败时的
 *  重试间隔 1s/2s/4s），用户触发审批后立刻能感知到状态变化；
 *  若服务器短时间内仍 pending，间隔翻倍避免压垮后端。
 *  3 次翻倍后上限 8s（1+2+4+8），等待管理员审批的时间窗内仍能频繁刷新。 */
const POLL_INITIAL_MS = 1000;

function DeviceSection() {
  const { t } = useTranslation('common');
  const [joinCode, setJoinCode] = useState('');
  // 订阅 store：approvalStatus / token / requestId 由 ensureDeviceToken / registerDevice /
  // pollBindStatus 统一维护（unregisterDevice 已移除——客户端无解绑入口）。重启时
  // ensureDeviceToken 后台 MCP 验证成功 → store 变为 active → 这里自动重渲染。
  // requestId 放 store 使组件卸载/重挂载不丢失轮询状态。
  const approvalStatus = useDeviceStatusStore((s) => s.approvalStatus);
  const token = useDeviceStatusStore((s) => s.token);
  const requestId = useDeviceStatusStore((s) => s.requestId);
  const setRequestId = useDeviceStatusStore((s) => s.setRequestId);
  const clearPending = useDeviceStatusStore((s) => s.clearPending);
  const [registering, setRegistering] = useState(false);
  const [polling, setPolling] = useState(false);
  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** 当前退避轮次（0 = 首次间隔，每次触发后 +1） */
  const backoffRef = useRef(0);

  // ── 轮询审批状态 ────────────────────────────────────────
  const pollBindStatus = useCallback(async (rid: string, tok?: string) => {
    try {
      const result = await checkBindStatus(rid, tok || undefined);
      const status = normalizeApprovalStatus(result.status);
      if (status === 'active') {
        // 服务器可能在审批通过时签发新 token（在 raw.data.device_token 里）。
        // 必须提取并保存，否则 token 丢失导致 connected=false、MCP 调用 401。
        const rawObj = (result.raw ?? {}) as { data?: unknown };
        const dataObj = (rawObj.data ?? result.raw ?? {}) as {
          device_token?: string;
          token?: string;
        };
        const newToken = dataObj.device_token || dataObj.token;
        const currentToken = useDeviceStatusStore.getState().token;
        const finalToken = newToken || currentToken;
        if (newToken && newToken !== currentToken) {
          try {
            localStorage.setItem(DEVICE_TOKEN_KEY, newToken);
          } catch {
            // localStorage 不可写时仅更新内存态
          }
        }
        useDeviceStatusStore.getState().setStatus({ approvalStatus: 'active', token: finalToken });
        setRequestId(null);
        setPolling(false);
        notificationService.success(t('tupaiSettings.deviceApproved'));
      } else if (status === 'rejected') {
        // 服务器不返回 rejected（收敛到 pending_approval），此分支仅作防御，
        // 绝不用「设备被拒绝」这类误导文案——统一按未绑定/待绑处理。
        const currentToken = useDeviceStatusStore.getState().token;
        useDeviceStatusStore.getState().setStatus({ approvalStatus: 'pending_approval', token: currentToken });
        setRequestId(null);
        setPolling(false);
        notificationService.warning(t('tupaiSettings.devicePendingApproval'));
      }
      // pending_approval → 继续轮询
    } catch (err) {
      // 轮询出错不中断,下次重试
      log.warn('pollBindStatus error:', { error: err });
    }
  }, [t, setRequestId]);

  // 启动/恢复轮询
  useEffect(() => {
    if (approvalStatus !== 'pending_approval' || !requestId) return;
    setPolling(true);
    backoffRef.current = 0;

    const scheduleNext = () => {
      const delay = POLL_INITIAL_MS * 2 ** backoffRef.current;
      backoffRef.current += 1;
      pollTimerRef.current = setTimeout(async () => {
        await pollBindStatus(requestId, token || undefined);
        // 如果状态还是 pending,继续调度下一次
        if (useDeviceStatusStore.getState().approvalStatus === 'pending_approval') {
          scheduleNext();
        }
      }, delay);
    };

      // 首次立即轮询一次
    void pollBindStatus(requestId, token || undefined).then(() => {
      if (useDeviceStatusStore.getState().approvalStatus === 'pending_approval') {
        scheduleNext();
      }
    });

    return () => {
      if (pollTimerRef.current) {
        clearTimeout(pollTimerRef.current);
        pollTimerRef.current = null;
      }
      setPolling(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [approvalStatus, requestId, token]);

  const handleRegister = useCallback(async () => {
    const code = joinCode.trim();
    if (!code) {
      notificationService.error(t('tupaiSettings.enterJoinCode'));
      return;
    }
    // 如果有正在进行的 pending 轮询，先停止（允许 pending 时直接输入新 join_code 覆盖）
    if (pollTimerRef.current) {
      clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    setPolling(false);
    // 清除旧的 pending 状态，避免 store 中残留旧 requestId
    clearPending();
    setRegistering(true);
    backoffRef.current = 0;
    try {
      // registerDevice 内部已更新 store（approvalStatus + token），订阅 store 的 UI 自动刷新
      const result = await registerDevice(code);
      const newToken =
        result && typeof result === 'object' && typeof result.token === 'string'
          ? result.token
          : '';
      const rid = result?.requestId ?? null;
      const status = normalizeApprovalStatus(result?.approvalStatus);
      // 有 requestId 说明服务器已受理、进入审批队列，token 还没签发属正常行为。
      const isPending = status === 'pending_approval' || Boolean(rid);

      if (!newToken && !isPending) {
        notificationService.error(t('tupaiSettings.noTokenReturned'));
        return;
      }

      if (newToken) {
        try {
          localStorage.setItem(DEVICE_TOKEN_KEY, newToken);
        } catch {
          // localStorage 不用时仅更新内存态，不影响注册结果反馈
        }
      }

      // 持久化 request_id（用于轮询）
      setRequestId(rid);

      setJoinCode('');

      if (status === 'active') {
        notificationService.success(t('tupaiSettings.deviceApproved'));
      } else if (isPending) {
        notificationService.warning(t('tupaiSettings.devicePendingApproval'));
      } else if (status === 'rejected') {
        // 服务器不返回 rejected，此分支仅防御；不显示「设备被拒绝」，按未绑定处理。
        notificationService.warning(t('tupaiSettings.devicePendingApproval'));
      } else {
        notificationService.info(t('tupaiSettings.deviceRegistered'));
      }

      if (newToken) {
        window.dispatchEvent(new Event('tupai:device-token-changed'));
      }
    } catch (err) {
      const msg = readErrorMessage(err);
      notificationService.error(msg || t('tupaiSettings.deviceRegisterFailed'));
    } finally {
      setRegistering(false);
    }
  }, [joinCode, t, setRequestId, clearPending]);

  const handleCancelPending = useCallback(async () => {
    const confirmed = await confirmWarning(
      t('tupaiSettings.cancelPendingTitle'),
      t('tupaiSettings.cancelPendingMessage'),
    );
    if (!confirmed) return;

    // 停止轮询定时器
    if (pollTimerRef.current) {
      clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    setPolling(false);
    backoffRef.current = 0;

    // 客户端侧清除 pending 状态（不调服务器，服务器端请求自然过期）
    clearPending();

    notificationService.info(t('tupaiSettings.cancelPendingSuccess'));
  }, [t, clearPending]);

  const connected = Boolean(token);
  // pending_approval 审批阶段有 requestId 但还没有 token，视为"已提交待审批"而非"未连接"
  const hasPendingBind = approvalStatus === 'pending_approval' && Boolean(requestId);
  // 仅展示截断后的 token 前缀，避免完整泄露
  const tokenPreview =
    token && token.length > 12 ? `${token.slice(0, 8)}…${token.slice(-4)}` : token;

  // 审批状态 → 状态标签 class + 文案
  // 服务器无 rejected 状态：rejected 防御性收敛到 pending（未绑定），不显示"被拒绝"。
  const statusClassName = (() => {
    if (connected && approvalStatus === 'active') return 'is-connected';
    if (hasPendingBind || (connected && approvalStatus === 'pending_approval')) return 'is-pending';
    if (connected && approvalStatus === 'rejected') return 'is-pending';
    if (connected) return 'is-connected';
    return '';
  })();

  const statusLabel = (() => {
    if (hasPendingBind) return t('tupaiSettings.devicePending');
    if (connected && approvalStatus === 'active') return t('tupaiSettings.deviceConnected');
    if (connected && approvalStatus === 'pending_approval') return t('tupaiSettings.devicePending');
    if (connected && approvalStatus === 'rejected') return t('tupaiSettings.devicePending');
    if (connected) return t('tupaiSettings.deviceConnected');
    return t('tupaiSettings.deviceDisconnected');
  })();

  const statusDescription = (connected || hasPendingBind)
    ? `${hasPendingBind && !connected ? `request_id: ${requestId}` : `token: ${tokenPreview}`}${approvalStatus !== 'unknown' ? ` · ${t(`tupaiSettings.approvalStatus.${approvalStatus}`)}` : ''}${polling ? ` · ${t('tupaiSettings.polling')}` : ''}`
    : t('tupaiSettings.deviceNotRegistered');

  // 输入框/注册按钮禁用：仅"注册中"或"已连接且 active"时禁用。
  // pending/rejected/unknown 状态即使有 token 也允许输入新 join_code 重新绑定。
  const inputDisabled = registering || (connected && approvalStatus === 'active');

  return (
    <ConfigPageSection
      title={t('tupaiSettings.deviceTitle')}
      description={t('tupaiSettings.deviceDesc')}
    >
      <ConfigPageRow
        label={t('tupaiSettings.deviceStatus')}
        description={statusDescription}
        align="center"
      >
        <span
          className={[
            'tupai-device-status',
            statusClassName,
          ]
            .filter(Boolean)
            .join(' ')}
        >
          {statusLabel}
        </span>
      </ConfigPageRow>
      {/* 注册绑定输入框始终显示（即使已注册保持可见），
           已注册设备自动禁用输入框和注册按钮。 */}
      <ConfigPageRow
        label={t('tupaiSettings.registerTitle')}
        description={t('tupaiSettings.registerDesc')}
        align="center"
      >
        <div className="tupai-device-register">
          <Input
            className="tupai-device-register__input"
            value={joinCode}
            onChange={(e) => setJoinCode(e.target.value)}
            placeholder={t('tupaiSettings.joinCodePlaceholder')}
            disabled={inputDisabled}
            inputSize="medium"
          />
          <Button
            variant="primary"
            size="medium"
            isLoading={registering}
            disabled={inputDisabled}
            onClick={() => {
              void handleRegister();
            }}
          >
            {t('tupaiSettings.registerBtn')}
          </Button>
        </div>
      </ConfigPageRow>
      {/* pending_approval 状态时显示审批提示 + 取消按钮 + 重新绑定提示 */}
      {approvalStatus === 'pending_approval' && (
        <div className="tupai-device-approval-hint">
          <div className="tupai-device-approval-hint__text">
            {polling ? t('tupaiSettings.pollingHint') : t('tupaiSettings.pendingHint')}
          </div>
          {hasPendingBind && (
            <div className="tupai-device-approval-hint__actions">
              <Button
                variant="ghost"
                size="small"
                onClick={() => { void handleCancelPending(); }}
              >
                {t('tupaiSettings.cancelPendingBtn')}
              </Button>
            </div>
          )}
          <div className="tupai-device-approval-hint__rebind">
            {t('tupaiSettings.rebindHint')}
          </div>
        </div>
      )}
    </ConfigPageSection>
  );
}

// ==================== Section 2: 关于 ====================

function AboutSection() {
  const { t } = useTranslation('common');
  return (
    <ConfigPageSection title={t('tupaiSettings.aboutTitle')} description={t('tupaiSettings.aboutDesc')}>
      <ConfigPageRow label={t('tupaiSettings.tupaiVersion')} align="center">
        <span className="tupai-about-value">{APP_VERSION}</span>
      </ConfigPageRow>
      <ConfigPageRow label={t('tupaiSettings.buildTime')} align="center">
        <span className="tupai-about-value">{BUILD_TIME}</span>
      </ConfigPageRow>
    </ConfigPageSection>
  );
}

// ==================== 主组件 ====================

const TupaiSettingsTab: FC = () => {
  const { t } = useTranslation('common');
  return (
    <ConfigPageLayout className="tupai-settings-tab">
      <ConfigPageHeader title={t('tupaiSettings.deviceTabTitle')} subtitle={t('tupaiSettings.headerSubtitle')} />
      <ConfigPageContent className="tupai-settings-tab__content">
        <DeviceSection />
        <AboutSection />
      </ConfigPageContent>
    </ConfigPageLayout>
  );
};

export default TupaiSettingsTab;
