import { create } from 'zustand';

export type DeviceApprovalStatus = 'active' | 'pending_approval' | 'rejected' | 'unknown';

const DEVICE_TOKEN_KEY = 'trae_device_token';

function readInitialToken(): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(DEVICE_TOKEN_KEY) : null;
  } catch {
    return null;
  }
}

const initialToken = readInitialToken();
// 不再默认 pending：启动时 ensureDeviceToken 会异步验证 MCP，
// 验证成功后直接置 active，避免在验证完成前误显示"审核中"。
const initialStatus: DeviceApprovalStatus = 'unknown';

interface DeviceStatusState {
  /** 设备审批状态：active=绿灯，pending_approval=黄灯，rejected=黄灯，unknown=红灯 */
  approvalStatus: DeviceApprovalStatus;
  /** 当前的 device_token（用于判断是否有 token） */
  token: string | null;
  /** 当前 bind 请求 ID（pending 审批轮询用；审批完成或解绑后置 null）。
   *  放在 store 而非组件局部 state，使 DeviceSection 卸载/重挂载时不丢失轮询状态。 */
  requestId: string | null;
  /** 更新状态（由 ensureDeviceToken、registerDevice 调用） */
  setStatus: (status: { approvalStatus: DeviceApprovalStatus; token: string | null }) => void;
  /** 设置 bind 请求 ID（由 registerDevice pending 分支、pollBindStatus 调用） */
  setRequestId: (requestId: string | null) => void;
  /** 客户端侧取消待审批绑定：清除 requestId，根据是否有 token 重置审批状态。
   *  不调服务器 API（服务器不支持 client.bind.cancel），服务器端 pending 请求自然过期。 */
  clearPending: () => void;
  /** 重置为未知状态 */
  reset: () => void;
}

export const useDeviceStatusStore = create<DeviceStatusState>((set) => ({
  approvalStatus: initialStatus,
  token: initialToken,
  requestId: null,
  setStatus: ({ approvalStatus, token }) => set({ approvalStatus, token }),
  setRequestId: (requestId) => set({ requestId }),
  clearPending: () => {
    set({
      requestId: null,
      // 有 token 但取消 pending → 状态回到 unknown（token 有效但未绑租户）
      // 无 token → 也是 unknown
      approvalStatus: 'unknown',
      // token 保留——fingerprint 签发的，重新绑定时还需要
      // （不写 token 字段即保留原值）
    });
  },
  reset: () => set({ approvalStatus: 'unknown', token: null, requestId: null }),
}));

/** 兼容旧代码的同步读取（不触发重渲染） */
export function getDeviceStatusSync(): { approvalStatus: DeviceApprovalStatus; token: string | null } {
  return useDeviceStatusStore.getState();
}