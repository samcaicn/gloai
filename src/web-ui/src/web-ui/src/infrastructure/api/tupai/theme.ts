// 主题色（前端独立，与 Rust 解耦）：localStorage 'tupai-scheme' 读写
// 不依赖 Tauri invoke，纯前端实现，通过 window 自定义事件 'tupai-scheme-change' 通知变化

import { createLogger } from '@/shared/utils/logger';

const log = createLogger('theme');

export type Scheme =
  | 'cyberpunk-cyan'
  | 'cyberpunk-magenta'
  | 'cyberpunk-green'
  | 'cyberpunk-yellow'
  | 'cyberpunk-red'
  | 'bitfun-dark'
  | 'bitfun-light';

const STORAGE_KEY = 'tupai-scheme';
const DEFAULT_SCHEME: Scheme = 'cyberpunk-cyan';
const CHANGE_EVENT = 'tupai-scheme-change';

const SCHEMES: Scheme[] = [
  'cyberpunk-cyan',
  'cyberpunk-magenta',
  'cyberpunk-green',
  'cyberpunk-yellow',
  'cyberpunk-red',
  'bitfun-dark',
  'bitfun-light',
];

function isScheme(value: unknown): value is Scheme {
  return typeof value === 'string' && (SCHEMES as string[]).includes(value);
}

/** 读取当前主题色，未设置时返回默认值 'cyberpunk-cyan' */
export function getScheme(): Scheme {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (isScheme(stored)) {
    return stored;
  }
  return DEFAULT_SCHEME;
}

/** 设置主题色并持久化到 localStorage，非法值会被忽略 */
export function setScheme(scheme: Scheme): void {
  if (!isScheme(scheme)) {
    log.warn(`invalid scheme: ${scheme}`);
    return;
  }
  localStorage.setItem(STORAGE_KEY, scheme);
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: scheme }));
}

/** 订阅主题色变化，返回取消订阅函数 */
export function onSchemeChange(handler: (scheme: Scheme) => void): () => void {
  const listener = (e: Event): void => {
    const customEvent = e as CustomEvent<Scheme>;
    handler(customEvent.detail);
  };
  window.addEventListener(CHANGE_EVENT, listener);
  return () => window.removeEventListener(CHANGE_EVENT, listener);
}
