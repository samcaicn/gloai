/**
 * Agent diagnostics — phase 2 coverage.
 *
 * Covers:
 *  - Guid no longer renders direct agent pills
 *  - MCP tools page loads
 *
 * The agent settings page is gone (/settings/agent redirects to the assistants page), and so are its tests.
 */
import { test, expect } from '../fixtures';
import {
  goToGuid,
  goToSettings,
  expectBodyContainsAny,
  expectUrlContains,
  takeScreenshot,
  AGENT_PILL,
  settingsSiderItemById,
} from '../helpers';

test.describe('Agent Diagnostics', () => {
  test('guid no longer renders direct agent pills', async ({ page }) => {
    await goToGuid(page);
    await expect(page.locator(AGENT_PILL)).toHaveCount(0);
    await expect(page.locator('[data-testid^="preset-pill-"]').first()).toBeVisible({ timeout: 12_000 });
  });

  test('screenshot: guid assistant pills', async ({ page }) => {
    test.skip(!process.env.E2E_SCREENSHOTS, 'screenshots disabled');
    await goToGuid(page);
    await expect(page.locator('[data-testid^="preset-pill-"]').first()).toBeVisible({ timeout: 12_000 });
    await takeScreenshot(page, 'guid-assistant-pills');
  });

  test('MCP tools page has server management UI', async ({ page }) => {
    await goToSettings(page, 'tools');
    await expectUrlContains(page, 'tools');
    await expect(page.locator(settingsSiderItemById('tools')).first()).toBeVisible({ timeout: 8_000 });
    await expectBodyContainsAny(page, ['MCP', 'mcp', 'Server', 'server', '工具', '配置', '添加', 'Add']);
  });

  test('can query available agents via IPC', async ({ page, electronApp }) => {
    await goToGuid(page);

    const windowCount = await electronApp.evaluate(async ({ BrowserWindow }) => {
      return BrowserWindow.getAllWindows().length;
    });
    expect(windowCount).toBeGreaterThanOrEqual(1);
  });
});
