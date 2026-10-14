import { randomUUID } from 'node:crypto';
import { app } from 'electron';
import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * 本机支付实例标识（mid）。
 *
 * 高熵随机、生成后持久化在 userData/pay/identity.json：
 *  - 微信回调按 mid 入账，换机/重装不丢（除非连 userData 一起删）；
 *  - 刻意不用硬件指纹：支付身份不需要绑定机器，重装可继续用同一余额更符合直觉。
 */

const MID_PREFIX = 'mu_';

function identityPath(): string {
  return path.join(app.getPath('userData'), 'pay', 'identity.json');
}

export function getMid(): string {
  try {
    const raw = fs.readFileSync(identityPath(), 'utf-8');
    const parsed = JSON.parse(raw) as { mid?: string };
    if (parsed.mid && parsed.mid.length >= 8) return parsed.mid;
  } catch {
    /* 首次运行：生成并落盘 */
  }
  const mid = `${MID_PREFIX}${randomUUID().replace(/-/g, '')}`;
  fs.mkdirSync(path.dirname(identityPath()), { recursive: true });
  fs.writeFileSync(identityPath(), JSON.stringify({ mid, createdAt: Date.now() }, null, 2), 'utf-8');
  return mid;
}
