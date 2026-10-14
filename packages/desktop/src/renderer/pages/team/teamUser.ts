/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The owner every team is filed under.
 *
 * mu is a single-user desktop app with no sign-in, so the backend always
 * attributes this client's rows to the seeded local user. Team SWR keys and
 * `user_id` payloads must all agree on it, or a rename would revalidate a key
 * nobody is subscribed to.
 */
export const DESKTOP_USER_ID = 'system_default_user';
