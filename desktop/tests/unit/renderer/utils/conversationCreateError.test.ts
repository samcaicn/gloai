/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import type { TFunction } from 'i18next';
import { describe, expect, it } from 'vitest';
import { BackendHttpError } from '@/common/adapter/httpBridge';
import {
  getConversationCreateErrorDetail,
  getConversationCreateErrorMessage,
  getConversationRuntimeWorkspaceErrorDetail,
  getConversationRuntimeWorkspaceErrorMessage,
  normalizeConversationCreateErrorCode,
  normalizeConversationRuntimeWorkspaceErrorCode,
} from '@/renderer/pages/conversation/utils/conversationCreateError';

const httpError = (code: string, error: string, details?: unknown) =>
  new BackendHttpError({
    method: 'POST',
    path: '/api/conversations',
    status: 400,
    body: { success: false, code, error, details },
  });

const translations: Record<string, string> = {
  'common.unknownError': 'Unknown error',
  'conversation.createFailedWithDetail': "Couldn't create the conversation: {{error}}",
  'conversation.agentStartFailedWithDetail': "Couldn't start the agent: {{error}}",
  'conversation.createError.codes.WORKSPACE_PATH_UNAVAILABLE':
    'The selected workspace path is unavailable. Make sure it exists and is accessible.',
  'conversation.createError.pathVariants.WORKSPACE_PATH_UNAVAILABLE':
    'The selected workspace path is unavailable. Make sure the workspace path "{{workspacePath}}" exists and is accessible.',
  'conversation.agentError.codes.WORKSPACE_PATH_RUNTIME_UNAVAILABLE.body':
    'Make sure the current workspace path exists.',
  'conversation.agentError.codes.WORKSPACE_PATH_RUNTIME_UNAVAILABLE.bodyWithPath':
    'The current Agent failed to run in the workspace path "{{workspacePath}}". Make sure the workspace path exists.',
};

const t = ((key: string, options?: Record<string, unknown>) =>
  (translations[key] ?? key).replace(/\{\{(\w+)\}\}/g, (_match, name: string) =>
    String(options?.[name] ?? '')
  )) as unknown as TFunction;

describe('conversationCreateError', () => {
  it('prefers the dedicated backend error code', () => {
    const error = httpError('WORKSPACE_PATH_UNAVAILABLE', 'Bad request: Workspace path contains whitespace', {
      workspace_path: '/tmp/Archive ',
    });

    expect(normalizeConversationCreateErrorCode(error)).toBe('WORKSPACE_PATH_UNAVAILABLE');
    expect(getConversationCreateErrorMessage(error, t)).toBe(
      'The selected workspace path is unavailable. Make sure the workspace path "/tmp/Archive " exists and is accessible.'
    );
  });

  it('aliases the older trailing-whitespace backend code to the new frontend code', () => {
    const error = httpError(
      'WORKSPACE_TRAILING_WHITESPACE_UNSUPPORTED',
      'Bad request: Workspace directory names ending in whitespace are not supported'
    );

    expect(normalizeConversationCreateErrorCode(error)).toBe('WORKSPACE_PATH_UNAVAILABLE');
  });

  it('falls back to legacy backend text matching for older builds', () => {
    const error = httpError(
      'BAD_REQUEST',
      'Bad request: Workspace directory names ending in whitespace are not supported: /tmp/My Dir '
    );

    expect(normalizeConversationCreateErrorCode(error)).toBe('WORKSPACE_PATH_UNAVAILABLE');
  });

  it('leads an unrelated backend message with a translated headline, the raw text only as its detail', () => {
    const error = httpError('BAD_REQUEST', 'Bad request: Something else failed');

    expect(normalizeConversationCreateErrorCode(error)).toBeUndefined();
    expect(getConversationCreateErrorMessage(error, t)).toBe(
      "Couldn't create the conversation: Bad request: Something else failed"
    );
    expect(getConversationCreateErrorDetail(error, t)).toBe('Bad request: Something else failed');
  });

  it("words the headline for the caller's action when one is given", () => {
    const error = httpError('BAD_REQUEST', 'Bad request: Something else failed');

    expect(getConversationCreateErrorMessage(error, t, (detail) => `Couldn't create the team: ${detail}`)).toBe(
      "Couldn't create the team: Bad request: Something else failed"
    );
  });

  it('names an unknown error under the headline when the error carries no text', () => {
    expect(getConversationCreateErrorMessage(new Error(''), t)).toBe("Couldn't create the conversation: Unknown error");
  });

  it('explains an unavailable workspace path in a sentence of its own when the path is missing', () => {
    const error = httpError('WORKSPACE_PATH_UNAVAILABLE', 'Bad request: Workspace path contains whitespace');

    expect(normalizeConversationCreateErrorCode(error)).toBe('WORKSPACE_PATH_UNAVAILABLE');
    expect(getConversationCreateErrorMessage(error, t)).toBe(
      'The selected workspace path is unavailable. Make sure it exists and is accessible.'
    );
    expect(getConversationCreateErrorDetail(error, t)).toBe(
      'The selected workspace path is unavailable. Make sure it exists and is accessible.'
    );
  });

  it('does not treat runtime workspace code as a create error', () => {
    const error = httpError(
      'WORKSPACE_PATH_RUNTIME_UNAVAILABLE',
      'Bad request: Workspace path is no longer supported for send or warmup',
      { workspace_path: '/tmp/Archive ', msg_id: 'deadbeef' }
    );

    expect(normalizeConversationCreateErrorCode(error)).toBeUndefined();
    expect(normalizeConversationRuntimeWorkspaceErrorCode(error)).toBe('WORKSPACE_PATH_RUNTIME_UNAVAILABLE');
    expect(getConversationRuntimeWorkspaceErrorMessage(error, t)).toBe(
      'The current Agent failed to run in the workspace path "/tmp/Archive ". Make sure the workspace path exists.'
    );
    expect(getConversationRuntimeWorkspaceErrorDetail(error, t)).toBe(
      'The current Agent failed to run in the workspace path "/tmp/Archive ". Make sure the workspace path exists.'
    );
  });

  it('shows an unavailable runtime workspace without a path as its translated sentence', () => {
    const error = httpError('WORKSPACE_PATH_RUNTIME_UNAVAILABLE', 'Bad request: Workspace path is gone');

    expect(getConversationRuntimeWorkspaceErrorMessage(error, t)).toBe('Make sure the current workspace path exists.');
  });

  it('leads any other runtime failure with a translated headline, and keeps the raw text for a detail line', () => {
    const error = httpError('BAD_REQUEST', 'Bad request: spawn failed');

    expect(normalizeConversationRuntimeWorkspaceErrorCode(error)).toBeUndefined();
    expect(getConversationRuntimeWorkspaceErrorMessage(error, t)).toBe(
      "Couldn't start the agent: Bad request: spawn failed"
    );
    expect(getConversationRuntimeWorkspaceErrorMessage(error, t, (detail) => `Run failed: ${detail}`)).toBe(
      'Run failed: Bad request: spawn failed'
    );
    expect(getConversationRuntimeWorkspaceErrorDetail(error, t)).toBe('Bad request: spawn failed');
  });

  it('extracts backend payloads from stringified BackendHttpError messages', () => {
    const error =
      'Backend POST /api/teams failed (400): {"success":false,"error":"Workspace path is unavailable: /Users/zhoukai/Documents/Archive . Make sure the selected workspace path exists and is accessible.","code":"WORKSPACE_PATH_UNAVAILABLE","details":{"workspace_path":"/Users/zhoukai/Documents/Archive ","operation":"create"}}';

    expect(normalizeConversationCreateErrorCode(error)).toBe('WORKSPACE_PATH_UNAVAILABLE');
    expect(getConversationCreateErrorMessage(error, t)).toBe(
      'The selected workspace path is unavailable. Make sure the workspace path "/Users/zhoukai/Documents/Archive " exists and is accessible.'
    );
  });
});
