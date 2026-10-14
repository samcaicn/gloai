#!/usr/bin/env node
/**
 * GCM 交叉验证：用 Node 内置 crypto（OpenSSL，完全独立的实现）校验
 * tools/scriptpack/packcrypto.py 手写 AES-256-GCM。
 *
 * 存在的理由：Python 侧自签自验永远发现不了 GCM 实现的 off-by-one
 * （密钥流起点写成 J0 而不是 inc32(J0) 时，空明文向量还蒙对，多分组全错），
 * 而 Android JCE 一验就拒。必须有一个外部实现做裁判。
 *
 * 用法（selftest 与 CI 均调用）：
 *   node gcm_crosscheck.js decrypt  从 stdin 读 {"key","nonce","ct","aad"} -> 解出明文 hex
 *   node gcm_crosscheck.js encrypt  从 stdin 读 {"key","nonce","pt","aad"} -> 输出 ct||tag hex
 */
const crypto = require('crypto');

function readStdin() {
  return new Promise((resolve, reject) => {
    let buf = '';
    process.stdin.on('data', (d) => (buf += d));
    process.stdin.on('end', () => resolve(buf));
    process.stdin.on('error', reject);
  });
}

(async () => {
  const mode = process.argv[2] || 'decrypt';
  const input = JSON.parse(await readStdin());
  const key = Buffer.from(input.key, 'hex');
  const nonce = Buffer.from(input.nonce, 'hex');
  const aad = Buffer.from(input.aad || '', 'hex');

  if (mode === 'encrypt') {
    const pt = Buffer.from(input.pt, 'hex');
    const c = crypto.createCipheriv('aes-256-gcm', key, nonce, { authTagLength: 16 });
    c.setAAD(aad);
    const ct = Buffer.concat([c.update(pt), c.final()]);
    process.stdout.write(Buffer.concat([ct, c.getAuthTag()]).toString('hex'));
    return;
  }

  const blob = Buffer.from(input.ct, 'hex');
  const ct = blob.subarray(0, blob.length - 16);
  const tag = blob.subarray(blob.length - 16);
  const d = crypto.createDecipheriv('aes-256-gcm', key, nonce, { authTagLength: 16 });
  d.setAAD(aad);
  d.setAuthTag(tag);
  try {
    const pt = Buffer.concat([d.update(ct), d.final()]);
    process.stdout.write(pt.toString('hex'));
  } catch (e) {
    process.stderr.write('NODE_DECRYPT_REJECTED: ' + e.message);
    process.exit(3);
  }
})();
