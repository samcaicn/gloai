// 系统健康事件订阅 Hook。
// 集中订阅后端 emit 的 5 个瞬态事件并转发到通知系统 (toast)：
//   tray://init-failed       → 托盘初始化失败警告
//   mesh://firewall-warning   → P2P 网络被防火墙拦截警告
//   startup://degraded        → 启动存在降级项警告
//   startup://ready           → dev-only debug 日志 (不打扰用户)
//   app://second-instance     → dev-only debug 日志 (窗口已在后端自动聚焦)
//
// 设计原则：
//   - 瞬态事件走 toast, 不阻塞用户。持久横幅 (权限缺失) 见 OsCompatibilityBanner。
//   - isTauriRuntime 守卫: 非 Tauri 环境 (jsdom dev) 直接 return, 不订阅。
//   - 遵守 AGENTS.md: 所有 listen 调用在此 hook 内, UI 组件不直接碰 Tauri API。
import { useEffect } from 'react';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { notificationService } from '@/shared/notification-system';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('useSystemHealthEvents');

interface ToastSpec {
  message: string;
  title: string;
  kind: 'warning' | 'info';
}

/** 事件名 → toast 规格。未列入此表的事件仅记日志, 不弹 toast。 */
const TOAST_EVENTS: Record<string, ToastSpec> = {
  'tray://init-failed': {
    message: '托盘初始化失败, 部分快捷功能 (全局热键 / 最小化到托盘) 可能不可用',
    title: '托盘初始化失败',
    kind: 'warning',
  },
  'mesh://firewall-warning': {
    message: 'P2P 网络可能被防火墙拦截, 请放行 UDP 端口后重试',
    title: '网络受限',
    kind: 'warning',
  },
  'startup://degraded': {
    message: '启动存在降级项, 部分功能可能受限, 详见日志',
    title: '启动降级',
    kind: 'warning',
  },
  'cdp-browser-launch-request': {
    message: '技能执行需要浏览器控制，正在自动启动 CDP 浏览器...',
    title: 'CDP 浏览器启动',
    kind: 'info',
  },
};

/** 仅记日志的事件 (不打扰用户)。 */
const SILENT_EVENTS = new Set(['startup://ready', 'app://second-instance']);

const ALL_EVENTS = [...Object.keys(TOAST_EVENTS), ...SILENT_EVENTS];

/**
 * 订阅系统健康事件。在 App 根组件挂载一次即可, 无参数, 无返回值。
 * 自动在卸载时清理所有监听器, 避免重复订阅导致 toast 重复弹出。
 */
export function useSystemHealthEvents(): void {
  useEffect(() => {
    if (!isTauriRuntime()) {
      return;
    }

    let disposed = false;
    const unlisteners: Array<() => void> = [];

    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        Promise.all(
          ALL_EVENTS.map(eventName =>
            listen(eventName, () => {
              if (SILENT_EVENTS.has(eventName)) {
                // startup://ready 与 app://second-instance 无需打扰用户:
                // 前者表示启动正常, 后者的窗口聚焦已由 single-instance 插件在后端完成。
                log.debug('system event (silent)', { eventName });
                return;
              }
              const spec = TOAST_EVENTS[eventName];
              if (!spec) {
                return;
              }
              if (spec.kind === 'warning') {
                notificationService.warning(spec.message, { title: spec.title });
              } else {
                notificationService.info(spec.message, { title: spec.title });
              }
            }),
          ),
        ),
      )
      .then(removers => {
        // 若组件已卸载而监听器刚注册成功, 立即清理, 避免泄漏。
        if (disposed) {
          removers.forEach(fn => fn());
          return;
        }
        unlisteners.push(...removers);
      })
      .catch(error => {
        if (!disposed) {
          log.warn('Failed to subscribe system health events', error);
        }
      });

    return () => {
      disposed = true;
      unlisteners.forEach(fn => fn());
      unlisteners.length = 0;
    };
  }, []);
}
