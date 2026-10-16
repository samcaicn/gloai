import React from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { KyrnSettings, Result, SaveSettings } from '@/common/kyrn/types';
import enCommon from '@/renderer/services/i18n/locales/en-US/common.json';
import enMu from '@/renderer/services/i18n/locales/en-US/mu.json';
import enSettings from '@/renderer/services/i18n/locales/en-US/settings.json';
import SettingsArea from '@/renderer/pages/settings/KyrnSettings/SettingsArea';
import Welcome from '@/renderer/pages/welcome';
import { ONBOARDING_KEY } from '@/renderer/pages/welcome/onboarding';
import { useFirstRunWelcome } from '@/renderer/pages/welcome/useFirstRunWelcome';

const bridge = vi.hoisted(() => ({
  settings: vi.fn(),
  save: vi.fn(),
  availableModels: vi.fn(),
  testProvider: vi.fn(),
  loginStatus: vi.fn(),
  loginState: vi.fn(),
  loginStart: vi.fn(),
  loginAnswer: vi.fn(),
  loginCancel: vi.fn(),
  loginLogout: vi.fn(),
  localJudgeState: vi.fn(),
  localJudgeRun: vi.fn(),
}));
vi.mock('@/common/kyrn/bridge', () => ({
  kyrnBridge: {
    settings: { invoke: bridge.settings },
    save: { invoke: bridge.save },
    availableModels: { invoke: bridge.availableModels },
    testProvider: { invoke: bridge.testProvider },
    loginStatus: { invoke: bridge.loginStatus },
    loginState: { invoke: bridge.loginState },
    loginStart: { invoke: bridge.loginStart },
    loginAnswer: { invoke: bridge.loginAnswer },
    loginCancel: { invoke: bridge.loginCancel },
    loginLogout: { invoke: bridge.loginLogout },
    localJudgeState: { invoke: bridge.localJudgeState },
    localJudgeRun: { invoke: bridge.localJudgeRun },
  },
  // As the real one: a failure keeps the code the store gave it.
  unwrap: <T,>(result: Result<T>) => {
    if (!result.ok) throw Object.assign(new Error(result.error), result);
    return result.data;
  },
}));
// The real switcher changes the app's own i18next and writes the setting; here it only has to be there.
vi.mock('@/renderer/components/settings/LanguageSwitcher', () => ({
  default: () => <div data-testid='language-switcher' />,
}));

/** Someone who just installed mu: the built-in judges, no provider, no startup model, no key. */
function newUser(patch: Partial<KyrnSettings> = {}): KyrnSettings {
  return {
    revision: 'r1',
    tiers: ['laya'],
    judges: {
      jev: { type: 'jev', model: 'jev-latest', baseUrl: '', apiKeyEnv: 'TYPESAFE_API_KEY', timeoutMs: 10000 },
      laya: { type: 'local', model: '', baseUrl: 'http://127.0.0.1:47823', apiKeyEnv: '', timeoutMs: 4000 },
    },
    mode: 'shadow',
    betaCompression: false,
    autoCompaction: true,
    maxContextTokens: 0,
    keys: { TYPESAFE_API_KEY: false },
    harness: { status: 'missing' },
    decisionModes: {},
    features: {},
    models: {
      providers: [],
      foreign: [],
      defaults: { provider: '', model: '', thinkingLevel: '' },
      commented: false,
      problem: '',
    },
    permissions: { mode: '', from: 'default' },
    boardModel: { supported: false, model: '' },
    ...patch,
  } as KyrnSettings;
}

const i18n = createInstance();
beforeAll(async () => {
  await i18n.init({
    lng: 'en-US',
    resources: { 'en-US': { translation: { common: enCommon, mu: enMu, settings: enSettings } } },
    interpolation: { escapeValue: false },
  });
});

beforeEach(() => {
  localStorage.clear();
  bridge.settings.mockResolvedValue({ ok: true, data: newUser() });
  bridge.availableModels.mockResolvedValue({ ok: true, data: { providers: [], thinkingLevels: [] } });
  bridge.loginStatus.mockResolvedValue({ ok: true, data: { signedIn: [] } });
  bridge.loginState.mockResolvedValue({ ok: true, data: { id: 0, phase: 'idle' } });
  bridge.localJudgeState.mockResolvedValue({
    ok: true,
    data: { support: 'ok', installed: true, running: true, url: 'http://127.0.0.1:47823' },
  });
  bridge.save.mockImplementation(async ({ models, credentials: _credentials, ...input }: SaveSettings) => {
    const base = newUser();
    return { ok: true, data: { ...base, ...input, revision: 'r2', models: { ...base.models, ...models } } };
  });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function at(path: string, element: React.ReactElement) {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={path} element={element} />
          <Route path='/guid' element={<div>landing page</div>} />
          <Route path='/welcome' element={<div>the guide</div>} />
        </Routes>
      </MemoryRouter>
    </I18nextProvider>
  );
}

describe('a settings section as its own page', () => {
  it('shows the judge as managed by AiBuddy, with no second menu and nothing to pick', async () => {
    at('/settings/judges', <SettingsArea section='judges' />);
    const page = await screen.findByTestId('mu-section-judges');
    expect(page).toHaveTextContent('Managed by AiBuddy');
    // No second menu, and no judge to pick: the page is a status line, not a form.
    expect(screen.queryByTestId('mu-nav-judges')).not.toBeInTheDocument();
    expect(within(page).queryByRole('radiogroup')).not.toBeInTheDocument();
    expect(within(page).queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(page).queryByRole('button')).not.toBeInTheDocument();
  });
});


describe('the first-run guide', () => {
  it('shows the AiBuddy cloud as the only way to connect', async () => {
    at('/welcome', <Welcome />);
    fireEvent.click(await screen.findByTestId('mu-welcome-begin'));
    expect(await screen.findByTestId('mu-welcome-step-cloud')).toBeInTheDocument();
    expect(screen.getByTestId('mu-welcome-way-cloud')).toBeInTheDocument();
    // No manual API endpoint, no Anthropic, no subscription sign-in in the guide.
    expect(screen.queryByTestId('mu-welcome-way-openai')).not.toBeInTheDocument();
    expect(screen.queryByTestId('mu-welcome-way-anthropic')).not.toBeInTheDocument();
    expect(screen.queryByTestId('mu-welcome-way-signedIn')).not.toBeInTheDocument();
  });

  it('connects to the cloud, sets the built-in judge, saves once at the end, and is not shown again', async () => {
    at('/welcome', <Welcome />);
    expect(await screen.findByText('Welcome to AiBuddy')).toBeInTheDocument();
    expect(screen.getByText('A check at every step')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('mu-welcome-begin'));
    fireEvent.click(screen.getByTestId('mu-welcome-next'));
    await screen.findByTestId('mu-welcome-step-ilink');
    fireEvent.click(screen.getByTestId('mu-welcome-next'));
    await screen.findByTestId('mu-welcome-step-done');
    expect(screen.getByText('AiBuddy / auto')).toBeInTheDocument();
    expect(bridge.save).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('mu-welcome-start'));
    expect(await screen.findByText('landing page')).toBeInTheDocument();
    expect(bridge.save).toHaveBeenCalledTimes(1);
    const saved = bridge.save.mock.calls[0][0] as SaveSettings;
    expect(saved.models?.providers).toMatchObject([
      {
        id: 'aibuddy',
        api: 'openai-completions',
        baseUrl: 'https://aiapi.tuptup.top',
        models: [{ id: 'auto' }],
      },
    ]);
    expect(saved.models?.defaults).toMatchObject({ provider: 'aibuddy', model: 'auto' });
    // The cloud judge is asked; no key the person types.
    expect(saved.tiers).toEqual(['jev']);
    expect(localStorage.getItem(ONBOARDING_KEY)).toBeTruthy();
  });

  it('offers the language before the first question, and on every step after it', async () => {
    at('/welcome', <Welcome />);
    const language = await screen.findByTestId('mu-welcome-language');
    expect(language).toHaveTextContent('Language');
    expect(within(language).getByTestId('language-switcher')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('mu-welcome-begin'));
    await screen.findByTestId('mu-welcome-step-cloud');
    expect(screen.getByTestId('mu-welcome-language')).toBeInTheDocument();
  });

  it('says in the reader’s words why the settings did not load, and why saving did not work', async () => {
    bridge.settings.mockResolvedValueOnce({
      ok: false,
      code: 'unreadable',
      params: { file: '/home/me/.mu/agent/settings.json' },
      error: "EACCES: permission denied, open '/home/me/.mu/agent/settings.json'",
    });
    at('/welcome', <Welcome />);
    expect(await screen.findByText('The settings could not be loaded')).toBeInTheDocument();
    expect(screen.getByText('/home/me/.mu/agent/settings.json could not be read.')).toBeInTheDocument();
    expect(screen.getByTestId('mu-error-detail')).toHaveTextContent('EACCES: permission denied');
    fireEvent.click(screen.getByText('Reload'));

    fireEvent.click(await screen.findByTestId('mu-welcome-begin'));
    fireEvent.click(screen.getByTestId('mu-welcome-next'));
    fireEvent.click(screen.getByTestId('mu-welcome-next'));
    bridge.save.mockResolvedValueOnce({ ok: false, code: 'credential', error: 'Invalid credential' });
    fireEvent.click(await screen.findByTestId('mu-welcome-start'));
    expect(await screen.findByText(/Saving did not work/)).toBeInTheDocument();
    expect(screen.queryByText(/Invalid credential/)).not.toBeInTheDocument();
  });

  it('skips a step with a quiet text button, even when the cloud is the only way', async () => {
    at('/welcome', <Welcome />);
    fireEvent.click(await screen.findByTestId('mu-welcome-begin'));
    // The way on is the primary button; skipping is not.
    expect(screen.getByTestId('mu-welcome-next')).toHaveClass('arco-btn-primary');
    expect(screen.getByTestId('mu-welcome-skip')).not.toHaveClass('arco-btn');
    fireEvent.click(screen.getByTestId('mu-welcome-skip'));
    expect(await screen.findByTestId('mu-welcome-step-ilink')).toBeInTheDocument();
    expect(bridge.save).not.toHaveBeenCalled();
  });

  it('can be skipped from the first page without saving anything', async () => {
    at('/welcome', <Welcome />);
    fireEvent.click(await screen.findByText('Skip setup'));
    expect(await screen.findByText('landing page')).toBeInTheDocument();
    expect(bridge.save).not.toHaveBeenCalled();
    expect(localStorage.getItem(ONBOARDING_KEY)).toBeTruthy();
  });

  it('opens by itself only for someone without a startup model', async () => {
    function Landing() {
      useFirstRunWelcome();
      return <div>landing</div>;
    }
    at('/home', <Landing />);
    expect(await screen.findByText('the guide')).toBeInTheDocument();
    cleanup();

    bridge.settings.mockResolvedValue({
      ok: true,
      data: newUser({
        models: { ...newUser().models, defaults: { provider: 'aibuddy', model: 'auto', thinkingLevel: '' } },
      }),
    });
    at('/home', <Landing />);
    await waitFor(() => expect(localStorage.getItem(ONBOARDING_KEY)).toBeTruthy());
    expect(screen.getByText('landing')).toBeInTheDocument();
    expect(screen.queryByText('the guide')).not.toBeInTheDocument();
  });
});
