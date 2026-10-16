import { createInstance } from 'i18next';
import { describe, expect, it } from 'vitest';
import { MU_NAME_POST_PROCESSOR, muNamePostProcessor, showAsMu, withTextsAsMu } from '@/common/kyrn/displayName';

describe('showAsMu', () => {
  it('shows the upstream product name as mu, in either spelling and next to CJK text', () => {
    expect(showAsMu('关于 AionUi')).toBe('关于 AiBuddy');
    expect(showAsMu('重启AionUI，或把这个 MCP server 改成绝对命令路径。')).toBe(
      '重启AiBuddy，或把这个 MCP server 改成绝对命令路径。'
    );
    expect(showAsMu('AionUi - Login')).toBe('AiBuddy - Login');
    expect(showAsMu('Restart AionUi. Then retry.')).toBe('Restart AiBuddy. Then retry.');
    expect(showAsMu('AionUi AionUi')).toBe('AiBuddy AiBuddy');
    expect(showAsMu('Chat with (AionUi) assistant')).toBe('Chat with (AiBuddy) assistant');
  });

  it('shows the backend, the built-in agent and its CLI as mu too', () => {
    expect(showAsMu('AionCore 无法启动')).toBe('AiBuddy 无法启动');
    expect(showAsMu('AionCore usually provisions its managed Node runtime automatically.')).toBe(
      'AiBuddy usually provisions its managed Node runtime automatically.'
    );
    expect(showAsMu('Aionrs agent error: timeout')).toBe('AiBuddy agent error: timeout');
    expect(showAsMu('Please select a model for Aion CLI')).toBe('Please select a model for AiBuddy');
    expect(showAsMu('Send message to AionCLI...')).toBe('Send message to AiBuddy...');
    expect(showAsMu("AionCore's log")).toBe("AiBuddy's log");
  });

  it('shows the name as mu in German compound nouns, which join it to the next word with a hyphen', () => {
    expect(showAsMu('Die AionUi-Installation ist unvollständig.')).toBe('Die AiBuddy-Installation ist unvollständig.');
    expect(showAsMu('Liebe AionUi-Nutzerin, lieber AionUi-Nutzer,')).toBe('Liebe AiBuddy-Nutzerin, lieber AiBuddy-Nutzer,');
    expect(showAsMu('die AionUi-Oberfläche')).toBe('die AiBuddy-Oberfläche');
    expect(showAsMu('das lokale AionCore-Backend')).toBe('das lokale AiBuddy-Backend');
  });

  it('leaves links, paths, file names, identifiers and the dev data folder alone', () => {
    for (const text of [
      'https://github.com/iOfficeAI/AionUi/releases',
      'github.com/iOfficeAI/AionUi',
      'support@AionUi.com',
      'AionUi.app',
      'AionUi-update-1.zip',
      '~/Library/Application Support/AionUi-Dev',
      '~/Library/Application Support/AionUi/config',
      'AionUi-Dev',
      'the AionUi-Dev-2 folder',
      'AIONUI_STREAM_BROKEN',
      'cleanAionUITimestamp',
      'AionrsSendBox',
      'aionrs',
      'aionui-browser',
      'bundled-aioncore/darwin-arm64',
      '[[AION_FILES]]',
    ]) {
      expect(showAsMu(text), text).toBe(text);
    }
  });

  it('keeps a real data path while the sentence around it becomes mu', () => {
    expect(showAsMu('AionUi logs are in ~/Library/Application Support/AionUi-Dev/logs')).toBe(
      'AiBuddy logs are in ~/Library/Application Support/AionUi-Dev/logs'
    );
  });

  it('returns a string without the name untouched', () => {
    const text = '判定已交给执行层；这不代表操作已经完成。';
    expect(showAsMu(text)).toBe(text);
  });
});

describe('withTextsAsMu', () => {
  it('shows the named texts of a backend record as mu and leaves every other field as it came', () => {
    const record = {
      id: 'aionui-assistant',
      name: 'AionUi Butler',
      name_i18n: { 'en-US': 'AionUi Butler', 'zh-CN': 'AionUi 管家' },
      description: 'Helps you manage AionUi itself',
      prompts: ['Open AionUi from my phone', 'Check AionCore logs'],
      prompts_i18n: { 'en-US': ['Open AionUi from my phone'] },
      agent_type: 'aionrs',
      context: 'You are AionUi’s built-in butler.',
    };
    expect(withTextsAsMu(record, ['name', 'name_i18n', 'description', 'prompts', 'prompts_i18n'] as const)).toEqual({
      id: 'aionui-assistant',
      name: 'AiBuddy Butler',
      name_i18n: { 'en-US': 'AiBuddy Butler', 'zh-CN': 'AiBuddy 管家' },
      description: 'Helps you manage AiBuddy itself',
      prompts: ['Open AiBuddy from my phone', 'Check AiBuddy logs'],
      prompts_i18n: { 'en-US': ['Open AiBuddy from my phone'] },
      agent_type: 'aionrs',
      context: 'You are AionUi’s built-in butler.',
    });
    // The input is not changed in place.
    expect(record.name).toBe('AionUi Butler');
  });

  it('leaves missing and non-text fields alone', () => {
    const record: { name: string; description?: string; enabled: boolean } = { name: 'AiBuddy', enabled: true };
    expect(withTextsAsMu(record, ['name', 'description', 'enabled'])).toEqual({ name: 'AiBuddy', enabled: true });
  });
});

describe('muNamePostProcessor', () => {
  it('applies to translations, interpolated values and default values of an i18next instance', async () => {
    const i18n = createInstance();
    await i18n.use(muNamePostProcessor).init({
      lng: 'zh-CN',
      resources: {
        'zh-CN': {
          translation: {
            tray: { about: '关于 AionUi' },
            update: '请将 AionUi 更新到 {{version}}，AionCore 无需处理。',
            agent: '{{name}} 正在运行',
          },
        },
      },
      interpolation: { escapeValue: false },
      postProcess: [MU_NAME_POST_PROCESSOR],
    });
    expect(i18n.t('tray.about')).toBe('关于 AiBuddy');
    expect(i18n.t('update', { version: 'v2' })).toBe('请将 AiBuddy 更新到 v2，AiBuddy 无需处理。');
    expect(i18n.t('agent', { name: 'Aion CLI' })).toBe('AiBuddy 正在运行');
    expect(i18n.t('missing.key', { defaultValue: 'Chat with AionUi assistant via Telegram' })).toBe(
      'Chat with AiBuddy assistant via Telegram'
    );
  });
});
