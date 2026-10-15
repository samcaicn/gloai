/**
 * 那张二维码界面自己：什么时候自己起、什么时候等人点、失败之后文案变成什么。
 *
 * 登录态 provider 整个 mock 掉——轮询的编排在 ilinkSession.dom.test.tsx 里测过，
 * 这里只看这块界面按状态画出什么、把点击送到哪个回调。
 */

import React from 'react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider } from 'react-i18next';
import enLogin from '@/renderer/services/i18n/locales/en-US/login.json';
import IlinkQrLogin from '@renderer/components/ilink/IlinkQrLogin';

const state = vi.hoisted(() => ({
  session: {} as {
    phase: string;
    qr: { ticket: string; content: string; expiresIn: number; app: string } | null;
    status: string | null;
    error: string | null;
    scanning: boolean;
    startLogin: ReturnType<typeof vi.fn>;
    refreshLogin: ReturnType<typeof vi.fn>;
    cancelLogin: ReturnType<typeof vi.fn>;
  },
}));

vi.mock('@renderer/services/ilinkSession', () => ({
  useIlinkSession: () => state.session,
}));

const i18n = createInstance();
beforeAll(async () => {
  await i18n.init({
    lng: 'en-US',
    resources: { 'en-US': { translation: { login: enLogin } } },
    interpolation: { escapeValue: false },
  });
});

const draw = (props: { autoStart?: boolean; onSignedIn?: () => void } = {}) =>
  render(
    <I18nextProvider i18n={i18n}>
      <IlinkQrLogin {...props} />
    </I18nextProvider>
  );

const withState = (patch: Partial<typeof state.session>) => {
  state.session = {
    phase: 'anonymous',
    qr: null,
    status: null,
    error: null,
    scanning: false,
    startLogin: vi.fn(),
    refreshLogin: vi.fn(),
    cancelLogin: vi.fn(),
    ...patch,
  };
};

beforeEach(() => {
  cleanup();
  withState({});
});

describe('IlinkQrLogin', () => {
  it('starts the scan itself when asked to, and waits for the ticket with a spinner', () => {
    draw({ autoStart: true });

    expect(state.session.startLogin).toHaveBeenCalledTimes(1);
    // 票还没回来：转圈，而不是一块空白。
    expect(screen.getByTestId('ilink-qr-login')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('does not start anything on its own: the settings page waits for a click', () => {
    draw();
    expect(state.session.startLogin).not.toHaveBeenCalled();
  });

  it('draws the ticket the provider handed it as a QR code', () => {
    withState({
      phase: 'scanning',
      scanning: true,
      status: 'pending',
      qr: { ticket: 'T1', content: 'https://liteapp.weixin.qq.com/q/abc', expiresIn: 60, app: 'tupai' },
    });
    draw();

    // qrcode.react renders an <svg>; the value it encodes is what WeChat will open.
    const svg = screen.getByTestId('ilink-qr-login').querySelector('svg');
    expect(svg).not.toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent(/Waiting/i);
  });

  it('offers refresh and cancel while a scan is running, and both reach the provider', () => {
    withState({
      phase: 'scanning',
      scanning: true,
      status: 'pending',
      qr: { ticket: 'T1', content: 'x', expiresIn: 60, app: 'tupai' },
    });
    draw();

    fireEvent.click(screen.getByText('New QR code'));
    expect(state.session.refreshLogin).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByText('Cancel'));
    expect(state.session.cancelLogin).toHaveBeenCalledTimes(1);
  });

  it('waits for a click before a scan, and says connect rather than try again', () => {
    draw();

    fireEvent.click(screen.getByText('Scan to connect'));
    expect(state.session.startLogin).toHaveBeenCalledTimes(1);
  });

  it('shows the failure and changes the button to a retry', () => {
    withState({ phase: 'error', error: 'network down' });
    draw();

    expect(screen.getByRole('alert')).toHaveTextContent('network down');
    fireEvent.click(screen.getByText('Try again'));
    expect(state.session.startLogin).toHaveBeenCalledTimes(1);
  });

  it('reports a signed-in scan to whoever is hosting the page', () => {
    const onSignedIn = vi.fn();
    withState({ phase: 'signedIn' });
    draw({ onSignedIn });

    expect(onSignedIn).toHaveBeenCalledTimes(1);
  });
});
