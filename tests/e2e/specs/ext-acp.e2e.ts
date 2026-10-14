/**
 * Extensions – ACP Adapters tests.
 *
 * Validates that extension-contributed ACP adapters leave the guid page's assistant pills working. The agent settings
 * page that listed the adapters is gone; ext-ipc-queries.e2e.ts checks the adapters through the bridge.
 */
import { test, expect } from '../fixtures';
import { goToGuid, ASSISTANT_PILL } from '../helpers';

test.describe('Extension: ACP Adapters', () => {
  test('assistant pill bar on guid page still works with extensions', async ({ page }) => {
    await goToGuid(page);

    const assistantPills = page.locator(ASSISTANT_PILL);
    await expect(assistantPills.first()).toBeVisible({ timeout: 5000 });
    const count = await assistantPills.count();
    expect(count).toBeGreaterThanOrEqual(1);
  });

  test('clicking an assistant pill does not crash with extensions loaded', async ({ page }) => {
    await goToGuid(page);

    const assistantPills = page.locator(ASSISTANT_PILL);
    await expect(assistantPills.first()).toBeVisible({ timeout: 5000 });

    await assistantPills.first().click();
    await expect(assistantPills.first()).toBeVisible();

    // Page should still be stable
    const body = await page.locator('body').textContent();
    expect(body).toBeTruthy();
  });
});
