/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

export type PermissionIntent = 'allow-once' | 'allow-always' | 'reject-once' | 'reject-always' | 'neutral';

export type PermissionOperationKind = 'execute' | 'edit' | 'read' | 'fetch' | 'tool';

export type PermissionPanelOption = {
  id: string;
  value: string;
  label: string;
  intent: PermissionIntent;
  testId: string;
  disabled?: boolean;
};

export const classifyLegacyPermission = (value: string): PermissionIntent => {
  switch (value) {
    case 'proceed_once':
    case 'allow_once':
      return 'allow-once';
    case 'proceed_always':
    case 'proceed_always_server':
    case 'proceed_always_tool':
    case 'allow_always':
      return 'allow-always';
    case 'cancel':
    case 'deny':
    case 'reject_once':
      return 'reject-once';
    case 'reject_always':
      return 'reject-always';
    default:
      return 'neutral';
  }
};

export const classifyAcpPermission = (kind: string): PermissionIntent => {
  switch (kind) {
    case 'allow_once':
      return 'allow-once';
    case 'allow_always':
      return 'allow-always';
    case 'reject_once':
      return 'reject-once';
    case 'reject_always':
      return 'reject-always';
    default:
      return 'neutral';
  }
};

type AcpPermissionKind = 'allow_once' | 'allow_always' | 'reject_once' | 'reject_always';

/**
 * The generic English names agents give the four standard ACP answers (the mu bridge sends "Allow" and "Reject" as
 * the English fallback of a harness confirmation, the Claude adapter "Allow", "Always Allow" and "Reject").
 */
const GENERIC_OPTION_NAMES: Record<AcpPermissionKind, readonly string[]> = {
  allow_once: ['allow', 'allow once', 'approve'],
  allow_always: ['always allow', 'allow always'],
  reject_once: ['reject', 'reject once', 'deny'],
  reject_always: ['always reject', 'reject always', 'always deny', 'deny always'],
};

const isAcpPermissionKind = (kind: string): kind is AcpPermissionKind => Object.hasOwn(GENERIC_OPTION_NAMES, kind);

/**
 * The i18n key that names an ACP permission answer in the reader's language, when the agent named it generically
 * (or not at all). Undefined when the agent's own wording must stay: a select choice or a specific answer such as
 * "Allow all edits in this folder".
 */
export const acpPermissionOptionLabelKey = (kind: string | undefined, name: string | undefined): string | undefined => {
  if (!kind || !isAcpPermissionKind(kind)) return undefined;
  const normalized = (name ?? '').trim().toLowerCase().replace(/\s+/g, ' ');
  if (normalized && !GENERIC_OPTION_NAMES[kind].includes(normalized)) return undefined;
  return `messages.permissionOption.${kind}`;
};

export const normalizePermissionOperationKind = (kind?: string): PermissionOperationKind => {
  switch (kind) {
    case 'exec':
    case 'execute':
      return 'execute';
    case 'edit':
      return 'edit';
    case 'info':
    case 'read':
      return 'read';
    case 'fetch':
      return 'fetch';
    default:
      return 'tool';
  }
};

export const getSafePermissionOptionId = (options: PermissionPanelOption[]): string | null =>
  options.find((option) => option.intent === 'allow-once' && !option.disabled)?.id ?? null;

export const getPermissionOptionsIdentity = (options: PermissionPanelOption[]): string =>
  JSON.stringify(options.map(({ id, value, intent, disabled }) => [id, value, intent, Boolean(disabled)]));
