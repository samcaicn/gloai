/**
 * 扫码页本身：进来的人是自己点进来的，所以「暂不登录」只是回去，不留下任何标记；
 * 而一旦登录成功，就回到他原本要去的地方。
 */

import React from 'react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import enLogin from '@/renderer/services/i18n/locales/en-US/login.json';
import LoginPage from '@renderer/pages/login';

const state = vi.hoisted(() => ({ phase: 'anonymous' as string }));

vi.mock('@renderer/services/ilinkSession', () => ({
  // 页面自己只看 phase；二维码界面要的那几个动作给空实现即可。
  useIlinkSession: () => ({
    phase: state.phase,
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
  }),
}));

const i18n = createInstance();
beforeAll(async () => {
  await i18n.init({
    lng: 'en-US',
    resources: { 'en-US': { translation: { login: enLogin } } },
    interpolation: { escapeValue: false },
  });
});

/** 落地页：把路由最终停在哪显示出来，跳没跳对一眼可见。 */
function Landing() {
  const location = useLocation();
  return <div data-testid='landing'>{`at ${location.pathname}${location.search}`}</div>;
}

const drawAt = (path: string) =>
  render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path='/login' element={<LoginPage />} />
          <Route path='/guid' element={<Landing />} />
          <Route path='/settings/ilink' element={<Landing />} />
        </Routes>
      </MemoryRouter>
    </I18nextProvider>
  );

beforeEach(() => {
  cleanup();
  localStorage.clear();
  state.phase = 'anonymous';
});

describe('the scan page', () => {
  it('shows the QR and the way out for someone who is not signed in', () => {
    drawAt('/login');

    expect(screen.getByTestId('ilink-qr-login')).toBeInTheDocument();
    expect(screen.getByTestId('ilink-login-skip')).toBeInTheDocument();
  });

  it('sends someone who skips back where they came from, and leaves no skip flag behind', () => {
    drawAt('/login');

    fireEvent.click(screen.getByTestId('ilink-login-skip'));

    expect(screen.getByTestId('landing')).toHaveTextContent('at /guid');
    // 门早就拆了：跳过不该再写任何「下次也不拦」的标记。
    expect(localStorage.length).toBe(0);
  });

  it('sends someone who signs in back where they came from', () => {
    state.phase = 'signedIn';
    drawAt('/login');

    expect(screen.getByTestId('landing')).toHaveTextContent('at /guid');
  });
});
