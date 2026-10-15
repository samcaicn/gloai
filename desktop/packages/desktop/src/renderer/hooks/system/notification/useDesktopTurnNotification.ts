/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { ipcBridge } from '@/common';
import { configService } from '@/common/config/configService';
import { isElectronDesktop } from '@/renderer/utils/platform';
import { getSnapshotConversationName } from '@/renderer/pages/conversation/GroupedHistory/hooks/useConversationListSync';
import { useIlinkSessionOptional } from '@renderer/services/ilinkSession';
import { createBrowserNotificationController, truncateConversationName } from './browserNotificationCore';

/**
 * Fire a native system notification when an agent turn finishes.
 *
 * The renderer only reports "a turn finished"; the main-process notification
 * bridge decides whether to actually show it (it skips when the main window is
 * focused and respects `system.notificationEnabled`). Clicks are handled by
 * `useNotificationClick`, which navigates to the originating conversation.
 */
export const useDesktopTurnNotification = (): void => {
  const { t } = useTranslation();
  // 已登录的话，同一句话再走一遍微信（iLink 消息推送）。人不在电脑前时最有用，
  // 所以只在窗口不可见时推，免得盯着屏幕的人被手机和电脑各提醒一次。
  // 用 optional：这个钩子在单测里是裸跑的，读不到登录态也不该抛错。
  const ilink = useIlinkSessionOptional();
  const session = ilink?.session;
  const push = ilink?.push;

  useEffect(() => {
    if (!isElectronDesktop()) return;

    const streamEmitter = ipcBridge.conversation?.responseStream;
    if (!streamEmitter) return;

    const controller = createBrowserNotificationController({
      // Cheap renderer-side gate; the main process still re-checks the setting
      // and the window-focus condition before showing anything.
      shouldShow: () => configService.get('system.notificationEnabled') !== false,
      bodyFor: (kind, conversationId) => {
        const name = conversationId ? getSnapshotConversationName(conversationId) : undefined;
        if (kind === 'confirmation') {
          return name
            ? t('settings.browserNotification.bodyConfirmationNamed', { name: truncateConversationName(name) })
            : t('settings.browserNotification.bodyConfirmation');
        }
        return name
          ? t('settings.browserNotification.bodyTurnCompletedNamed', { name: truncateConversationName(name) })
          : t('settings.browserNotification.bodyTurnCompleted');
      },
      show: ({ body, conversationId }) => {
        // Both turn-completed and confirmation (permission / question) kinds
        // fire a native notification. The main process still gates on the
        // setting and skips when the window is focused.
        void ipcBridge.notification.show.invoke({ title: 'AiBuddy', body, conversation_id: conversationId });
        // 推不出去也不该影响桌面通知本身：微信这边只是多一个收件渠道。
        if (session && push && document.hidden) void push(body).catch(() => undefined as void);
      },
    });

    const disposeStream = streamEmitter.on(controller.onStreamMessage);
    return () => {
      disposeStream();
    };
  }, [t, session, push]);
};
