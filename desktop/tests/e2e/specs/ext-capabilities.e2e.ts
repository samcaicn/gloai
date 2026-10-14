import { test, expect } from '../fixtures';
import { getExtensionSnapshot, goToExtensionSettings, waitForSettle } from '../helpers';

test.describe('Extension: Complete Capabilities', () => {
  test('all extension contribution categories are loaded and queryable', async ({ page }) => {
    const snapshot = await getExtensionSnapshot(page);

    const extensionNames = snapshot.loadedExtensions.map((ext) => ext.name);
    expect(extensionNames).toEqual(expect.arrayContaining(['e2e-full-extension', 'hello-world', 'ext-feishu']));

    const acpAdapterIds = snapshot.acpAdapters.map((item) => item.id);
    expect(acpAdapterIds).toEqual(
      expect.arrayContaining(['e2e-cli-agent', 'e2e-http-agent', 'hello-stdio-agent', 'hello-http-agent'])
    );

    const assistantIds = snapshot.assistants.map((item) => item.id);
    expect(assistantIds).toEqual(expect.arrayContaining(['ext-e2e-test-assistant', 'ext-hello-assistant']));

    const agentIds = snapshot.agents.map((item) => item.id);
    expect(agentIds).toEqual(expect.arrayContaining(['ext-hello-coder', 'ext-hello-researcher']));

    const mcpServerNames = snapshot.mcp_servers.map((item) => item.name);
    expect(mcpServerNames).toEqual(expect.arrayContaining(['e2e-echo-server', 'hello-echo-mcp']));

    const skillNames = snapshot.skills.map((item) => item.name);
    expect(skillNames).toEqual(expect.arrayContaining(['e2e-test-skill', 'hello-quick-summary']));

    const themeIds = snapshot.themes.map((item) => item.id);
    expect(themeIds).toEqual(
      expect.arrayContaining(['ext-e2e-full-extension-e2e-dark-theme', 'ext-hello-world-ocean-breeze'])
    );

    const settingsTabIds = snapshot.settingsTabs.map((item) => item.id);
    expect(settingsTabIds).toEqual(
      expect.arrayContaining([
        'ext-e2e-full-extension-e2e-settings',
        'ext-e2e-full-extension-e2e-before-about',
        'ext-hello-world-hello-settings',
      ])
    );

    const allEntryUrlsValid = snapshot.settingsTabs.every(
      (item) =>
        item.url.startsWith('/api/extensions/') || item.url.startsWith('http://') || item.url.startsWith('https://')
    );
    expect(allEntryUrlsValid).toBeTruthy();

    const feishuWebui = snapshot.webuiContributions.find((item) => item.extensionName === 'ext-feishu');
    expect(feishuWebui).toBeTruthy();
    const feishuApiPaths = feishuWebui?.apiRoutes.map((item) => item.path) || [];
    expect(feishuApiPaths).toEqual(expect.arrayContaining(['/ext-feishu/collect', '/ext-feishu/stats']));
    const feishuAssetPrefixes = feishuWebui?.staticAssets.map((item) => item.urlPrefix) || [];
    expect(feishuAssetPrefixes).toEqual(expect.arrayContaining(['/ext-feishu/assets']));
  });

  test('all known extension settings tabs can be opened and rendered', async ({ page }) => {
    const tabIds = [
      'ext-e2e-full-extension-e2e-settings',
      'ext-e2e-full-extension-e2e-before-about',
      'ext-hello-world-hello-settings',
    ];

    for (const tabId of tabIds) {
      await goToExtensionSettings(page, tabId);
      await waitForSettle(page, 4_000);

      const bodyText = await page.locator('body').textContent();
      expect((bodyText || '').length).toBeGreaterThan(30);
    }

    const iframeCount = await page.locator('iframe[title*="Extension settings"]').count();
    expect(iframeCount).toBeGreaterThan(0);
  });

  test('extension snapshot IPC call completes within budget', async ({ page }) => {
    const start = Date.now();
    await getExtensionSnapshot(page);
    const elapsedMs = Date.now() - start;

    expect(elapsedMs).toBeLessThan(5_000);
  });
});
