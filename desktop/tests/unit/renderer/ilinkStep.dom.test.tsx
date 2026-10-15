/**
 * 引导里「连微信」那一步：三种样子。
 *
 * 关键是第三种——没有 provider 的时候（比如单测直接渲染欢迎页）这块不能炸，
 * 只留一句「去设置里连」。已登录的人看到的是「已连接」，不该再被塞一张二维码。
 */

import React from 'react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider } from 'react-i18next';
import enLogin from '@/renderer/services/i18n/locales/en-US/login.json';
import enMu from '@/renderer/services/i18n/locales/en-US/mu.json';
import IlinkStep from '@renderer/pages/welcome/IlinkStep';

const state = vi.hoisted(() => ({ session: null as unknown }));

vi.mock('@renderer/services/ilinkSession', () => ({
  useIlinkSessionOptional: () => state.session,
  // 二维码界面用强 hook：只有 optional 说「有登录态」时它才会被渲染，
  // 所以这里给它同一份对象，等价于 provider 真的在。
  useIlinkSession: () => state.session,
}));

const i18n = createInstance();
beforeAll(async () => {
  await i18n.init({
    lng: 'en-US',
    resources: { 'en-US': { translation: { login: enLogin, mu: enMu } } },
    interpolation: { escapeValue: false },
  });
});

const draw = () =>
  render(
    <I18nextProvider i18n={i18n}>
      <IlinkStep />
    </I18nextProvider>
  );

const anonymous = {
  phase: 'anonymous',
  session: null,
  qr: null,
  device: null,
  status: null,
  error: null,
  scanning: false,
  pushReady: false,
  startLogin: vi.fn(),
  refreshLogin: vi.fn(),
  cancelLogin: vi.fn(),
  logout: vi.fn(),
  push: vi.fn(),
};

beforeEach(() => {
  cleanup();
  state.session = { ...anonymous };
});

describe('the WeChat step of the first-run guide', () => {
  it('shows the QR for someone not connected yet, and says it can wait', () => {
    draw();

    expect(screen.getByTestId('ilink-qr-login')).toBeInTheDocument();
    expect(screen.getByText(/connect any time from the settings/i)).toBeInTheDocument();
  });

  it('shows connected instead of a QR for someone who already scanned', () => {
    state.session = { ...anonymous, phase: 'signedIn', session: { ilink: 'o9cq8@im.wechat' } };
    draw();

    expect(screen.getByTestId('mu-welcome-ilink-connected')).toBeInTheDocument();
    expect(screen.queryByTestId('ilink-qr-login')).not.toBeInTheDocument();
  });

  it('degrades to one line when there is no login state at all, instead of failing', () => {
    state.session = null;
    draw();

    expect(screen.queryByTestId('ilink-qr-login')).not.toBeInTheDocument();
    expect(screen.getByText(/try again under Settings/i)).toBeInTheDocument();
  });
});
