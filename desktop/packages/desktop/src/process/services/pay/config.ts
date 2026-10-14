import { app } from 'electron';
import * as fs from 'node:fs';
import * as path from 'node:path';
import type { PayConfig } from './types';

/**
 * 支付服务配置：落盘在 userData/pay/config.json。
 *
 * 优先级：环境变量 MU_PAY_BASE > 配置文件 > 本地默认（wrangler dev）。
 * 这样不改代码、不重新打包，就能把客户端指向任意部署好的 Worker。
 */

const DEFAULT_CONFIG: PayConfig = { apiBase: 'http://127.0.0.1:8787' };

function configDir(): string {
  return path.join(app.getPath('userData'), 'pay');
}

function configPath(): string {
  return path.join(configDir(), 'config.json');
}

export function getPayConfig(): PayConfig {
  const envBase = process.env.MU_PAY_BASE?.trim();
  if (envBase) return { apiBase: envBase.replace(/\/+$/, '') };
  try {
    const raw = fs.readFileSync(configPath(), 'utf-8');
    const parsed = JSON.parse(raw) as Partial<PayConfig>;
    if (typeof parsed.apiBase === 'string' && parsed.apiBase.trim()) {
      return { apiBase: parsed.apiBase.trim().replace(/\/+$/, '') };
    }
  } catch {
    /* 没有配置文件就用默认值 */
  }
  return DEFAULT_CONFIG;
}

export function setPayConfig(apiBase: string): PayConfig {
  const next: PayConfig = { apiBase: apiBase.trim().replace(/\/+$/, '') };
  if (!next.apiBase) throw new Error('apiBase 不能为空');
  fs.mkdirSync(configDir(), { recursive: true });
  fs.writeFileSync(configPath(), JSON.stringify(next, null, 2), 'utf-8');
  return next;
}

/** 是否已指向真实部署的 Worker（本地默认地址视为未配置）。 */
export function isConfigured(config: PayConfig): boolean {
  return !!config.apiBase && !config.apiBase.includes('127.0.0.1') && !config.apiBase.includes('localhost');
}
