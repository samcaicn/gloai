import { execFile } from 'node:child_process';
import { randomBytes, createHmac } from 'node:crypto';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

/**
 * iLink authorization service. It answers two questions for this machine:
 *
 *   1. "Which device is this?" — a stable `device_id` that survives an OS reinstall.
 *   2. "Who is logged in?"     — see `login.ts` for the WeChat scan flow.
 *
 * Privacy model (do not weaken):
 *   - Hardware identifiers are read on this machine and **never** leave it.
 *   - Only `HMAC-SHA256(salt, value)` hex digests are uploaded — 64 chars, irreversible.
 *   - The salt is issued by the server per `app`, so the same hardware hashes differently
 *     across apps and cannot be correlated between them.
 *   - No username, hostname, IP, file path, software list or browser fingerprint is collected,
 *     and raw values are never written to disk or logs.
 */

export const ILINK_BASE = process.env.ILINK_BASE ?? 'https://ilink.tuptup.top';

/** App-audience for the device code. The server issues a per-app salt, so keep this stable. */
export const ILINK_APP = process.env.ILINK_APP ?? 'tupai';

const REQUEST_TIMEOUT_MS = 15_000;

export type FeatureKey = 'mb' | 'bios' | 'cpu' | 'disk' | 'mac';

/** One irreversible hardware digest. `h` is lowercase hex, 64 chars. */
export interface DeviceFeature {
  k: FeatureKey;
  h: string;
}

export interface DeviceIdResult {
  deviceId: string;
  isNew: boolean;
  /** 0..1 share of features that matched the existing device. */
  confidence: number;
  matched: number;
  source: 'server' | 'cache' | 'fallback';
}

/** Raw hardware identifiers. Local only — never serialized off this machine. */
export type RawFeatures = Partial<Record<FeatureKey, string>>;

/** Where to keep the last known device id, so a short outage does not mint a new one. */
export interface DeviceStore {
  get(key: string): Promise<string | undefined> | string | undefined;
  set(key: string, value: string): Promise<void> | void;
}

const FEATURE_KEYS: readonly FeatureKey[] = ['mb', 'bios', 'cpu', 'disk', 'mac'];

/** In-memory fallback store; the host app should pass one backed by disk. */
export const memoryStore: DeviceStore = (() => {
  const map = new Map<string, string>();
  return {
    get: (key) => map.get(key),
    set: (key, value) => void map.set(key, value),
  };
})();

/** HMAC-SHA256(salt, value), lowercase hex. This is the only shape we upload. */
export function hashFeature(salt: string, value: string): string {
  return createHmac('sha256', salt).update(value, 'utf8').digest('hex');
}

/** Turn raw identifiers into uploadable digests, skipping anything the OS did not give us. */
export function buildFeatures(salt: string, raw: RawFeatures): DeviceFeature[] {
  const features: DeviceFeature[] = [];
  for (const k of FEATURE_KEYS) {
    const value = raw[k];
    if (typeof value === 'string' && value.trim() !== '') features.push({ k, h: hashFeature(salt, value) });
  }
  return features;
}

interface JsonResponse {
  ok?: boolean;
  status: number;
  body: Record<string, unknown> | undefined;
}

async function request(path: string, init?: RequestInit): Promise<JsonResponse> {
  const response = await fetch(`${ILINK_BASE}${path}`, {
    ...init,
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  let body: Record<string, unknown> | undefined;
  try {
    body = (await response.json()) as Record<string, unknown>;
  } catch {
    body = undefined;
  }
  return { ok: response.ok, status: response.status, body };
}

function str(source: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = source?.[key];
  return typeof value === 'string' ? value : undefined;
}

function num(source: Record<string, unknown> | undefined, key: string): number {
  const value = source?.[key];
  return typeof value === 'number' ? value : 0;
}

/** GET /device/salt?app=… The salt is fixed per app, so it is safe (and cheap) to cache. */
export async function fetchDeviceSalt(app: string = ILINK_APP): Promise<string> {
  const result = await request(`/device/salt?app=${encodeURIComponent(app)}`);
  const salt = str(result.body, 'salt');
  if (result.status !== 200 || !salt) throw new Error(`device/salt failed: HTTP ${result.status}`);
  return salt;
}

/** Read the local hardware identifiers, best-effort per platform. Never throws. */
export async function collectRawFeatures(): Promise<RawFeatures> {
  try {
    if (process.platform === 'win32') return await collectWindows();
    if (process.platform === 'darwin') return await collectMac();
    return await collectLinux();
  } catch {
    // A locked-down machine simply contributes fewer features; matching degrades gracefully.
    return {};
  }
}

const WINDOWS_SCRIPT = `
$b = Get-CimInstance Win32_BaseBoard
$s = Get-CimInstance Win32_BIOS
$c = Get-CimInstance Win32_Processor
$d = Get-PhysicalDisk | Select-Object -First 1
$n = Get-NetAdapter -Physical | Where-Object { $_.Status -eq 'Up' } | Select-Object -First 1
@{ mb = $b.SerialNumber; bios = $s.SerialNumber; cpu = $c.ProcessorId; disk = $d.SerialNumber; mac = $n.MacAddress } |
	ConvertTo-Json -Compress
`.trim();

async function collectWindows(): Promise<RawFeatures> {
  const { stdout } = await execFileAsync('powershell', ['-NoProfile', '-NonInteractive', '-Command', WINDOWS_SCRIPT], {
    timeout: 20_000,
    maxBuffer: 1 << 20,
  });
  return JSON.parse(String(stdout).trim()) as RawFeatures;
}

async function collectMac(): Promise<RawFeatures> {
  const raw: RawFeatures = {};
  const { stdout: serial } = await execFileAsync('ioreg', ['-rd1', '-c', 'IOPlatformExpertDevice'], {
    timeout: 15_000,
  });
  const platform = /"IOPlatformSerialNumber"\s*=\s*"([^"]+)"/.exec(serial);
  if (platform) raw.mb = platform[1];
  const { stdout: cpu } = await execFileAsync('sysctl', ['-n', 'machdep.cpu.brand_string'], { timeout: 15_000 });
  if (cpu.trim()) raw.cpu = cpu.trim();
  const { stdout: disk } = await execFileAsync('diskutil', ['info', '/'], { timeout: 15_000 });
  const uuid = /(?:Volume UUID|Disk \/ Partition UUID):\s*(\S+)/.exec(disk);
  if (uuid) raw.disk = uuid[1];
  return raw;
}

async function collectLinux(): Promise<RawFeatures> {
  const raw: RawFeatures = {};
  const dmi = { mb: '/sys/class/dmi/id/board_serial', bios: '/sys/class/dmi/id/bios_version' } as const;
  for (const [key, file] of Object.entries(dmi)) {
    try {
      const text = await readText(file);
      if (text) raw[key as 'mb' | 'bios'] = text;
    } catch {
      /* unreadable on this kernel */
    }
  }
  try {
    const cpuinfo = await readText('/proc/cpuinfo');
    const model = /Model name\s*:\s*(.+)/.exec(cpuinfo);
    if (model) raw.cpu = model[1].trim();
  } catch {
    /* no cpuinfo */
  }
  try {
    const { stdout } = await execFileAsync('lsblk', ['-no', 'SERIAL', '-d'], { timeout: 10_000 });
    const first = String(stdout)
      .split('\n')
      .map((line) => line.trim())
      .find((line) => line !== '');
    if (first) raw.disk = first;
  } catch {
    /* lsblk unavailable */
  }
  return raw;
}

async function readText(file: string): Promise<string> {
  const { readFile } = await import('node:fs/promises');
  return String(await readFile(file, 'utf8')).trim();
}

export interface DeviceIdOptions {
  app?: string;
  /** Pre-hashed features. Omit to read this machine's hardware. */
  features?: DeviceFeature[];
  /** iLink user id to attach immediately after resolving the device. */
  ilink?: string;
  /** Persistence for the resolved id. Defaults to memory. */
  store?: DeviceStore;
}

/**
 * Resolve a stable device id for this machine.
 *
 * Order: server register -> cached value on network failure -> locally minted id (never throws).
 * The local fallback keeps features working offline; it is NOT a device code and the server
 * never sees it.
 */
export async function getDeviceId(opts: DeviceIdOptions = {}): Promise<DeviceIdResult> {
  const app = opts.app ?? ILINK_APP;
  const store = opts.store ?? memoryStore;
  const cacheKey = `ilink.device.${app}`;
  let features = opts.features;

  if (features === undefined) {
    const salt = await fetchDeviceSalt(app);
    features = buildFeatures(salt, await collectRawFeatures());
  }

  if (features.length === 0) {
    // Nothing to identify by: fall back so callers are never blocked on auth.
    const cached = await store.get(cacheKey);
    return {
      deviceId: cached ?? localId(),
      isNew: false,
      confidence: 0,
      matched: 0,
      source: cached ? 'cache' : 'fallback',
    };
  }

  try {
    const result = await request('/device/register', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ app, features, ...(opts.ilink ? { ilink: opts.ilink } : {}) }),
    });
    const deviceId = str(result.body, 'device_id');
    if (result.status !== 200 || !deviceId) throw new Error(`register failed: HTTP ${result.status}`);
    await store.set(cacheKey, deviceId);
    return {
      deviceId,
      isNew: result.body?.is_new === true,
      confidence: num(result.body, 'confidence'),
      matched: num(result.body, 'matched'),
      source: 'server',
    };
  } catch {
    const cached = await store.get(cacheKey);
    return {
      deviceId: cached ?? localId(),
      isNew: false,
      confidence: 0,
      matched: 0,
      source: cached ? 'cache' : 'fallback',
    };
  }
}

/** Last-resort id used only when the service is unreachable and nothing is cached. */
function localId(): string {
  return `dv_local_${randomBytes(8).toString('hex')}`;
}

/** Wrap raw identifiers into features using this app's current salt. */
export async function hashLocalFeatures(app: string = ILINK_APP): Promise<DeviceFeature[]> {
  const salt = await fetchDeviceSalt(app);
  return buildFeatures(salt, await collectRawFeatures());
}

export interface DeviceRecord {
  deviceId: string;
  app: string;
  ilink?: string;
  firstSeen?: string;
  lastSeen?: string;
}

/** GET /device?device_id=… Returns null when the server does not know it (e.g. after reset). */
export async function getDevice(deviceId: string): Promise<DeviceRecord | null> {
  const result = await request(`/device?device_id=${encodeURIComponent(deviceId)}`);
  if (result.status !== 200 || result.body?.ok !== true) return null;
  const record: DeviceRecord = { deviceId, app: str(result.body, 'app') ?? '' };
  const ilink = str(result.body, 'ilink');
  if (ilink) record.ilink = ilink;
  const firstSeen = str(result.body, 'first_seen');
  if (firstSeen) record.firstSeen = firstSeen;
  const lastSeen = str(result.body, 'last_seen');
  if (lastSeen) record.lastSeen = lastSeen;
  return record;
}

/** Attach an iLink user to this device (call once the scan login succeeds). */
export async function bindIlink(deviceId: string, ilink: string): Promise<boolean> {
  const result = await request('/device/bind', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ device_id: deviceId, ilink }),
  });
  return result.status === 200 && result.body?.ok === true;
}

/** Forget this device server-side — wipes its features and iLink binding. User-controllable. */
export async function resetDevice(deviceId: string): Promise<boolean> {
  const result = await request('/device/reset', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ device_id: deviceId }),
  });
  return result.status === 200 && result.body?.ok === true;
}
