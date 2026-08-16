/**
 * Announcement system Tauri API client.
 *
 * Wraps all Tauri `invoke` calls for the announcement system so that the
 * rest of the frontend never touches `invoke` directly.
 */
import { invoke } from '@/infrastructure/api/tupai/invoke';
import type { AnnouncementCard } from '../types';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('AnnouncementService');

// 设备 token 与其它 tupai 桥接层一致，存于 localStorage `trae_device_token`。
// 透传给后端 get_pending_announcements → MCP client.check_update 用于
// 客户端更新查询（后端 mcp_call_v2 需要 device token 鉴权）。
const DEVICE_TOKEN_KEY = 'trae_device_token';

function readDeviceToken(): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(DEVICE_TOKEN_KEY) : null;
  } catch {
    return null;
  }
}

export const announcementService = {
  /**
   * Fetch the ordered list of cards that should be displayed in this session.
   * This also triggers the scheduler (increments open-count, updates version)
   * and asks the backend to check for a client update via MCP.
   * Should be called once per application start.
   */
  async getPendingAnnouncements(): Promise<AnnouncementCard[]> {
    try {
      const token = readDeviceToken();
      const result = await invoke<AnnouncementCard[]>('get_pending_announcements', {
        token: token ?? null,
      });
      return result ?? [];
    } catch (e) {
      log.error('Failed to get pending announcements', e);
      return [];
    }
  },

  /** Mark a card as seen (modal was opened or action button was clicked). */
  async markSeen(id: string): Promise<void> {
    try {
      await invoke('mark_announcement_seen', { request: { id } });
    } catch (e) {
      log.error('Failed to mark announcement seen', { id, error: e });
    }
  },

  /** Dismiss a card for the current version cycle. */
  async dismiss(id: string): Promise<void> {
    try {
      await invoke('dismiss_announcement', { request: { id } });
    } catch (e) {
      log.error('Failed to dismiss announcement', { id, error: e });
    }
  },

  /** Permanently suppress a card. */
  async neverShow(id: string): Promise<void> {
    try {
      await invoke('never_show_announcement', { request: { id } });
    } catch (e) {
      log.error('Failed to suppress announcement', { id, error: e });
    }
  },

  /**
   * Manually trigger a specific card by ID.
   * Returns `null` if no card with that ID is registered.
   */
  async triggerCard(id: string): Promise<AnnouncementCard | null> {
    try {
      const result = await invoke<AnnouncementCard | null>('trigger_announcement', { request: { id } });
      return result ?? null;
    } catch (e) {
      log.error('Failed to trigger announcement', { id, error: e });
      return null;
    }
  },

  /** Fetch all currently eligible tip cards (for a tips browser). */
  async getTips(): Promise<AnnouncementCard[]> {
    try {
      const result = await invoke<AnnouncementCard[]>('get_announcement_tips');
      return result ?? [];
    } catch (e) {
      log.error('Failed to get announcement tips', e);
      return [];
    }
  },

  /**
   * DEBUG ONLY — trigger a set of known card IDs and return the resolved cards.
   *
   * `trigger_announcement` bypasses all scheduler filters (seen/dismissed/version),
   * making it ideal for in-dev testing of card UI without clearing persisted state.
   */
  async debugTriggerCards(ids: string[]): Promise<AnnouncementCard[]> {
    const results = await Promise.all(
      ids.map((id) => announcementService.triggerCard(id)),
    );
    return results.filter((c): c is AnnouncementCard => c !== null);
  },
};
