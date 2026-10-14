/**
 * Assistant pills on the guid page, from the unified assistant catalog.
 *
 * The agent management page (Settings -> Agent) and its repair route are gone: /settings/agent now redirects to the
 * assistants page, so only the guid coverage stays here.
 */
import { test, expect } from '../fixtures';
import { ASSISTANT_PILL, goToGuid } from '../helpers';

test.describe('Assistant pills — E2E', () => {
  test('assistant pill bar on guid page renders available assistants', async ({ page }) => {
    await goToGuid(page);

    const pills = page.locator(ASSISTANT_PILL);
    await expect(pills.first()).toBeVisible({ timeout: 8_000 });
    await expect.poll(async () => pills.count(), { timeout: 8_000 }).toBeGreaterThanOrEqual(1);
  });

  test('selecting an assistant in pill bar activates chat input', async ({ page }) => {
    await goToGuid(page);

    const pills = page.locator(ASSISTANT_PILL);
    await expect(pills.first()).toBeVisible({ timeout: 8_000 });
    await pills.first().click();

    await expect(page.locator('textarea, [contenteditable="true"], [role="textbox"]').first()).toBeVisible({
      timeout: 8_000,
    });
  });
});
