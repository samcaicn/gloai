import type { ConfirmedLogin } from './login';

/**
 * iLink bot message push client.
 *
 * The `confirmed` login payload (see login.ts) returns three credentials the
 * desktop client uses to talk to WeChat on the user's behalf:
 *   - base_url : "https://ilinkai.weixin.qq.com"
 *   - bot_token: "<id>@im.bot:<secret>"
 *   - link_token: "<opaque>"
 *
 * These are passed straight through from the login response — no part of them
 * is invented here.
 *
 * ⚠️ UNVERIFIED CONTRACT: the message-send / message-receive endpoints below
 * follow the standard WeChat iLink bot open API shape
 * (`/openapi/message/send`, `/openapi/message/receive`). They could NOT be
 * confirmed against the live service from the build network — every probe of
 * `ilinkai.weixin.qq.com` returned HTTP 404 (the host is not directly callable
 * from here; it is presumably reachable only from WeChat's own infrastructure
 * or via the ilink.tuptup.top service as a proxy). The methods are implemented
 * against the expected contract and will surface the real error if the path or
 * field names differ. Treat `base path` / field names as needing one live
 * example response to lock in.
 */

export interface IlinkBotCreds {
  baseUrl: string;
  botToken: string;
  linkToken: string;
  ilink: string;
  userId: string;
}

export function credsFromConfirmed(c: ConfirmedLogin): IlinkBotCreds {
  return {
    baseUrl: c.baseUrl,
    botToken: c.botToken,
    linkToken: c.linkToken,
    ilink: c.ilink,
    userId: c.userId,
  };
}

export interface BotSendResult {
  ok: boolean;
  status: number;
  body: string;
}

const REQUEST_TIMEOUT_MS = 20_000;

export class IlinkBot {
  private readonly baseUrl: string;
  private readonly botToken: string;
  private readonly linkToken: string;
  readonly ilink: string;
  readonly userId: string;

  constructor(creds: IlinkBotCreds) {
    this.baseUrl = creds.baseUrl.replace(/\/+$/, '');
    this.botToken = creds.botToken;
    this.linkToken = creds.linkToken;
    this.ilink = creds.ilink;
    this.userId = creds.userId;
  }

  /**
   * Push a text message to the user's WeChat.
   * `toUser` defaults to the logged-in iLink id when omitted.
   */
  async sendMessage(text: string, toUser?: string): Promise<BotSendResult> {
    const url =
      `${this.baseUrl}/openapi/message/send` +
      `?bot_token=${encodeURIComponent(this.botToken)}` +
      `&link_token=${encodeURIComponent(this.linkToken)}`;
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          to_user: toUser ?? this.ilink,
          msg_type: 'text',
          content: text,
        }),
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
      const body = await response.text();
      return { ok: response.ok, status: response.status, body };
    } catch (error) {
      return {
        ok: false,
        status: 0,
        body: error instanceof Error ? error.message : String(error),
      };
    }
  }

  /**
   * Long-poll for an inbound message/event from the user's WeChat.
   * Returns whatever the server answers; the caller inspects the shape.
   */
  async receiveMessage(): Promise<{ status: number; body: string }> {
    const url =
      `${this.baseUrl}/openapi/message/receive` +
      `?bot_token=${encodeURIComponent(this.botToken)}` +
      `&link_token=${encodeURIComponent(this.linkToken)}`;
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    const body = await response.text();
    return { status: response.status, body };
  }
}
