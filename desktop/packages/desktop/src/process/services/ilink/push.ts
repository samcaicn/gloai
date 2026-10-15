import { IlinkBot, type BotSendResult, type IlinkBotCreds } from './bot';
import { readIlinkSession, type IlinkSession } from './session';

/**
 * 消息推送通道（主进程单例）。
 *
 * 一个进程只有一个 bot：要么由扫码登录现场建（`activateIlinkPush`），要么在
 * 启动时用上次的登录态恢复（`restoreIlinkPush`）。渲染层的 botInit 也走这里，
 * 免得两处各持一个实例、互相覆盖。
 *
 * 其他主进程服务要发消息，直接 `pushIlinkText(...)`：不必知道登录态从哪来，
 * 没登录时会拿到 ok=false 而不是抛错（推送是增强，不该把主流程拖挂）。
 */

let bot: IlinkBot | null = null;

export function isPushReady(): boolean {
  return bot !== null;
}

export function activateIlinkPush(creds: IlinkBotCreds): void {
  bot = new IlinkBot(creds);
}

export function deactivateIlinkPush(): void {
  bot = null;
}

/** 当前登录态对应的推送收件人；未登录为 undefined。 */
export function pushRecipient(): string | undefined {
  return bot?.ilink || undefined;
}

/** 推一条文本。未就绪时返回 ok=false，调用方自行决定是否提示。 */
export async function pushIlinkText(text: string, toUser?: string): Promise<BotSendResult> {
  if (!bot) return { ok: false, status: 0, body: 'ilink push not ready (not signed in)' };
  // 没收件人就推给登录者本人：bot 只知道这一个身份，推给别人没有意义。
  return bot.sendMessage(text, toUser ?? bot.ilink);
}

/** 启动时用落盘的登录态恢复推送通道；返回恢复出的会话，未登录返回 null。 */
export function restoreIlinkPush(): IlinkSession | null {
  const session = readIlinkSession();
  if (!session) return null;
  if (!session.baseUrl || !session.botToken) return null;
  activateIlinkPush({
    baseUrl: session.baseUrl,
    botToken: session.botToken,
    linkToken: session.linkToken,
    ilink: session.ilink,
    userId: session.userId,
  });
  return session;
}
