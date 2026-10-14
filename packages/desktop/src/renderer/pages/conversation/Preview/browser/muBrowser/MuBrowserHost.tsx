import React, { useEffect, useRef } from 'react';
import { kyrnBrowserBridge, type BrowserPanelRequest } from '@/common/kyrn/browserBridge';
import { announcePreviewOpened } from '../../context/previewOpeners';
import { browserNow, openBrowserPage, switchBrowserTab } from '../browserStore';
import { BROWSER_BLANK_URL, MAX_BROWSER_TABS } from '../constants';
import ConfirmDialog from './ConfirmDialog';
import { decideOpen } from './panelPolicy';
import { browserRunsNow, useBrowserRuns } from './runsStore';
import { giveKeyboard } from './webviews';

const nextFrame = () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));

/**
 * The browser's side of the bridge: opens (or hands out again) a page when mu starts a run, puts the keyboard on a
 * page before mu types, brings a page forward when mu asks a question, and shows that question. Mounted with the
 * conversation page, so the main process knows the browser can be shown exactly while it can.
 *
 * Everything here is the agent's doing, and announced as such: a run's page opening only marks the work panel's
 * 浏览器 tab as new. Typing into the page and asking the person to confirm a step are done where the person can see
 * them, so those two bring the work panel up on the browser (`agent-watched`); a page out of view cannot take the
 * keyboard.
 */
const MuBrowserHost: React.FC<{ conversationId?: string }> = ({ conversationId }) => {
  const runs = useBrowserRuns();
  // Requests arrive outside React's rendering: they read the conversation on screen through a ref.
  const visible = useRef(conversationId);
  visible.current = conversationId;

  useEffect(() => {
    if (!conversationId) return undefined;
    void kyrnBrowserBridge.panel.invoke({ available: true });
    return () => void kyrnBrowserBridge.panel.invoke({ available: false });
  }, [conversationId]);

  useEffect(() => {
    const handle = async (request: BrowserPanelRequest) => {
      // The page comes into view: in front in the browser, and the work panel up on the browser.
      const bringIntoView = (tabId: string) => {
        switchBrowserTab(tabId);
        announcePreviewOpened('agent-watched', 'browser');
      };
      if (request.kind === 'attention') {
        if (request.waiting) bringIntoView(request.tabId);
        return;
      }
      if (request.kind === 'keyboard') {
        // mu types where the person can see it: a page out of view is brought into view first.
        let held = browserNow().activeTabId === request.tabId && giveKeyboard(request.tabId);
        if (!held) {
          bringIntoView(request.tabId);
          await nextFrame();
          await nextFrame();
          held = giveKeyboard(request.tabId);
        }
        void kyrnBrowserBridge.answer.invoke({ kind: 'keyboard', requestId: request.requestId, held });
        return;
      }
      const owner = request.conversation ?? visible.current ?? '';
      const decision = decideOpen(
        browserNow().tabs.map((tab) => ({ id: tab.id, isBrowser: true, isMu: Boolean(tab.muRun) })),
        browserRunsNow(),
        owner,
        MAX_BROWSER_TABS
      );
      if (decision.kind === 'refuse') {
        void kyrnBrowserBridge.answer.invoke({ kind: 'open', requestId: request.requestId, error: decision.error });
        return;
      }
      let tabId: string | null;
      if (decision.kind === 'reuse') {
        // The main process blanks the page before the run starts. Doing it from here as well would be a second
        // navigation that can land after mu's own and wipe the page it had just opened.
        tabId = decision.tabId;
        switchBrowserTab(tabId);
        announcePreviewOpened('agent', 'browser');
      } else {
        tabId = openBrowserPage(BROWSER_BLANK_URL, { by: 'agent', muRun: request.requestId });
      }
      void kyrnBrowserBridge.answer.invoke(
        tabId
          ? { kind: 'open', requestId: request.requestId, tabId, conversationId: owner }
          : { kind: 'open', requestId: request.requestId, error: "The app's browser could not open a tab" }
      );
    };
    return kyrnBrowserBridge.request.on((request) => void handle(request));
  }, []);

  // One question at a time, the one that has waited longest.
  const asking = Object.values(runs)
    .filter((run) => run.confirm)
    .toSorted((a, b) => (a.confirm?.askedAt ?? 0) - (b.confirm?.askedAt ?? 0))[0];
  return asking ? <ConfirmDialog key={asking.confirm?.id} run={asking} /> : null;
};

export default MuBrowserHost;
