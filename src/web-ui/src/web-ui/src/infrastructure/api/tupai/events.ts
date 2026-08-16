// 事件订阅封装：通用 subscribe<T>(event, handler)，基于 @tauri-apps/api/event 的 listen
import { listen, type Event } from '@tauri-apps/api/event';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('events');

export function subscribe<T>(event: string, handler: (data: T) => void): () => void {
  let disposed = false;
  let unlisten: () => void = () => {};

  if (!isTauriRuntime()) {
    return unlisten;
  }

  listen<T>(event, (e: Event<T>) => handler(e.payload))
    .then((fn) => {
      // 组件在 listen resolve 前已卸载: 立即清理刚注册的 listener, 防止泄漏。
      if (disposed) {
        fn();
      } else {
        unlisten = fn;
      }
    })
    .catch((e) => log.error(`subscribe ${event} failed`, { error: e }));

  return () => {
    disposed = true;
    unlisten();
  };
}
