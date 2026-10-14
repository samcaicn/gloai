import {
  fetchDeviceSalt,
  bindIlink,
  getDeviceId,
  ILINK_APP,
  type DeviceFeature,
  type DeviceIdResult,
} from './deviceId';

/**
 * WeChat iLink scan login.
 *
 * Verified contract (probed against the live service before writing this):
 *   GET /qr?app=…&callback=…  -> { ticket, content, expires_in, app, callback }
 *        `content` is the URL to draw as a QR code (https://liteapp.weixin.qq.com/q/…).
 *   GET /status?ticket=…      -> { status: 'pending' } while nobody has scanned,
 *                                { status: 'expired', waited: <ms> } once the ticket times out.
 *        The `waited` field means endpoint holds the request, i.e. it is meant to be polled in a loop.
 *
 * Anything beyond these two shapes is handled but not asserted: the server owns the success
 * payload shape, so `ScanStatus.raw` is always surfaced and no field is invented.
 */

export const ILINK_DEFAULT_APP = ILINK_APP;

export interface LoginQr {
  ticket: string;
  /** Encode this into a QR image for the user to scan with WeChat. */
  content: string;
  expiresIn: number;
  app: string;
}

/**
 * Everything the service returns once the human has scanned AND authorized.
 * Captured verbatim from a live `GET /status` reply (status: "confirmed"):
 *
 *   {
 *     "status": "confirmed",
 *     "ilink": "o9cq8...@im.wechat",          // iLink user id (= user_id)
 *     "bot_token": "....@im.bot:....",         // message-push credential
 *     "base_url": "https://ilinkai.weixin.qq.com",
 *     "user_id": "o9cq8...@im.wechat",
 *     "link_token": "LJ3DgyXkZM",             // message-push credential
 *     "context_token": "",
 *     "app": "tupai",
 *     "device_id": "",                        // empty until we bind our device
 *     "callback": ""
 *   }
 *
 * `ilink` / `botToken` / `baseUrl` / `linkToken` are what the desktop client
 * needs to later send pushes (see bot.ts). `deviceId` is empty here because
 * this flow did not register a device; callers should bind theirs via
 * deviceId.bindIlink after login.
 */
export interface ConfirmedLogin {
  ilink: string;
  userId: string;
  botToken: string;
  baseUrl: string;
  linkToken: string;
  contextToken: string;
  app: string;
  deviceId: string;
  raw: Record<string, unknown>;
}

export interface ScanStatus {
  /** 'pending' | 'expired' | 'confirmed' | whatever else the server answers. */
  status: string;
  /** Milliseconds the server held the request; only present on some answers. */
  waited?: number;
  /** iLink user id when the server hands one back. */
  ilink?: string;
  /** Full parsed confirmed payload, present once status === 'confirmed'. */
  confirmed?: ConfirmedLogin;
  raw: Record<string, unknown>;
}

export type QrListener = (qr: LoginQr) => void;
export type StatusListener = (status: ScanStatus) => void;

const REQUEST_TIMEOUT_MS = 25_000;
/** Ticket lifetime observed from GET /qr. Log a fresh one a little sooner. */
export const QR_LIFETIME_MS = 120_000;

async function request(
  path: string,
  init?: RequestInit
): Promise<{ status: number; body: Record<string, unknown> | undefined }> {
  const response = await fetch(`${process.env.ILINK_BASE ?? 'https://ilink.tuptup.top'}${path}`, {
    ...init,
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  let body: Record<string, unknown> | undefined;
  try {
    body = (await response.json()) as Record<string, unknown>;
  } catch {
    body = undefined;
  }
  return { status: response.status, body };
}

function str(source: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = source?.[key];
  return typeof value === 'string' ? value : undefined;
}

/** Ask the service for a fresh login ticket. Each call mints a NEW ticket. */
export async function createLoginQr(opts: { app?: string; callback?: string } = {}): Promise<LoginQr> {
  const app = opts.app ?? ILINK_APP;
  const query = new URLSearchParams({ app });
  if (opts.callback) query.set('callback', opts.callback);
  const result = await request(`/qr?${query.toString()}`);
  const ticket = str(result.body, 'ticket');
  const content = str(result.body, 'content');
  if (result.status !== 200 || !ticket || !content) {
    throw new Error(`qr create failed: HTTP ${result.status}`);
  }
  return {
    ticket,
    content,
    expiresIn: typeof result.body?.expires_in === 'number' ? (result.body.expires_in as number) : QR_LIFETIME_MS / 1000,
    app: str(result.body, 'app') ?? app,
  };
}

/** Candidates the server may use for the authenticated iLink id. Checked in order. */
const ILINK_FIELDS = ['ilink', 'ilink_id', 'userId', 'user_id', 'uid', 'openid', 'open_id'] as const;

function pickIlink(raw: Record<string, unknown>): string | undefined {
  for (const key of ILINK_FIELDS) {
    const value = raw[key];
    if (typeof value === 'string' && value !== '') return value;
  }
  return undefined;
}

/** Parse the full 'confirmed' payload into a typed object (see ConfirmedLogin). */
function parseConfirmed(raw: Record<string, unknown>): ConfirmedLogin {
  const s = (k: string) => (typeof raw[k] === 'string' ? (raw[k] as string) : '');
  return {
    ilink: s('ilink'),
    userId: s('user_id'),
    botToken: s('bot_token'),
    baseUrl: s('base_url'),
    linkToken: s('link_token'),
    contextToken: s('context_token'),
    app: s('app'),
    deviceId: s('device_id'),
    raw,
  };
}

/** Single /status call. The endpoint long-polls server-side. */
export async function pollLoginStatus(ticket: string, timeoutMs = REQUEST_TIMEOUT_MS): Promise<ScanStatus> {
  const result = await request(`/status?ticket=${encodeURIComponent(ticket)}`, {
    signal: AbortSignal.timeout(timeoutMs),
  });
  const raw = result.body ?? {};
  const status = str(raw, 'status') ?? `http_${result.status}`;
  const waited = typeof raw.waited === 'number' ? (raw.waited as number) : undefined;
  const ilink = pickIlink(raw);
  const confirmed = status === 'confirmed' ? parseConfirmed(raw) : undefined;
  return {
    status,
    ...(waited === undefined ? {} : { waited }),
    ...(ilink === undefined ? {} : { ilink }),
    ...(confirmed === undefined ? {} : { confirmed }),
    raw,
  };
}

export interface WaitOptions {
  ticket: string;
  /** Stop polling after this long, whatever the server says. */
  deadlineMs?: number;
  /** Sleep between polls when the server answers instantly. */
  intervalMs?: number;
  onStatus?: StatusListener;
}

/**
 * Poll until the ticket leaves 'pending'.
 *
 * Note: this only ever resolves 'pending' -> something else. It does NOT guess at success:
 * the caller inspects the returned status / ilink.
 */
export async function waitForScan(opts: WaitOptions): Promise<ScanStatus> {
  const deadline = Date.now() + (opts.deadlineMs ?? QR_LIFETIME_MS);
  const interval = opts.intervalMs ?? 1_500;
  let last: ScanStatus | undefined;
  while (Date.now() < deadline) {
    const remaining = deadline - Date.now();
    try {
      last = await pollLoginStatus(opts.ticket, Math.max(1_000, Math.min(REQUEST_TIMEOUT_MS, remaining)));
    } catch (error) {
      last = { status: 'network_error', raw: { message: error instanceof Error ? error.message : String(error) } };
    }
    opts.onStatus?.(last);
    if (last.status !== 'pending') return last;
    if (last.status === 'pending' && Date.now() + interval < deadline) {
      await sleep(Math.min(interval, deadline - Date.now()));
    }
  }
  return last ?? { status: 'timeout', raw: {} };
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
}

export interface LoginAndBindResult {
  qr: LoginQr;
  status: ScanStatus;
  bound: boolean;
  ilink?: string;
  confirmed?: ConfirmedLogin;
  device?: DeviceIdResult;
}

/**
 * Full flow: mint a QR, wait for the scan, then attach the resulting iLink id to this device.
 * `onQr` fires as soon as there is something to draw; `onStatus` fires on every poll.
 */
export async function loginAndBind(
  opts: {
    app?: string;
    callback?: string;
    features?: DeviceFeature[];
    deadlineMs?: number;
    onQr?: QrListener;
    onStatus?: StatusListener;
  } = {}
): Promise<LoginAndBindResult> {
  const qr = await createLoginQr({ app: opts.app, callback: opts.callback });
  opts.onQr?.(qr);
  const status = await waitForScan({
    ticket: qr.ticket,
    deadlineMs: opts.deadlineMs ?? QR_LIFETIME_MS,
    onStatus: opts.onStatus,
  });
  const ilink = status.ilink;
  if (!ilink) return { qr, status, bound: false };
  const device = await getDeviceId({ app: opts.app, features: opts.features, ilink });
  return { qr, status, bound: true, ilink, confirmed: status.confirmed, device };
}

/** Convenience re-export so callers do not have to reach into the device module. */
export { bindIlink, getDeviceId, fetchDeviceSalt };
