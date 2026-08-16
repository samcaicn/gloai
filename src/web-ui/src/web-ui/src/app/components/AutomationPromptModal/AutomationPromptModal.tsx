/**
 * AutomationPromptModal — Track F "互动输入" front-end surface.
 *
 * Subscribes to `automation:ask_user` events emitted by the Rust
 * executor (`pc_automation::executor::mod.rs`) when a `SkillStep`
 * carries an `InteractionPrompt`. Renders a small modal with a form
 * shaped by `prompt.inputType`:
 *
 *   * `text`    — a single text input (default value pre-filled).
 *   * `choice`  — one button per `prompt.choices`.
 *   * `confirm` — a yes/no pair.
 *
 * On submit the answer is delivered via `answerPrompt`; on dismiss
 * (close button / overlay / Escape) via `cancelPrompt`. The executor
 * unblocks either way (answer fulfills the oneshot, cancel drops the
 * sender so the receiver errors and falls back to `default_value`).
 *
 * The component is self-contained: it owns its open state and
 * subscription, so mounting it once (e.g. in the app shell) is
 * enough to enable the feature. It renders nothing while idle.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { HelpCircle, Check, X, SkipForward } from 'lucide-react';
import { Modal, Button, Input } from '@/component-library';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { createLogger } from '@/shared/utils/logger';
import {
  onAskUser,
  answerPrompt,
  cancelPrompt,
  type AskUserPayload,
  type PromptInputType,
} from '@/infrastructure/api/tupai/automationPrompt';
import './AutomationPromptModal.scss';

const log = createLogger('AutomationPromptModal');

const INPUT_TYPE_LABEL: Record<PromptInputType, string> = {
  text: '请输入',
  choice: '请选择',
  multichoice: '请选择（多选）',
  confirm: '请确认',
};

export const AutomationPromptModal: React.FC = () => {
  const [current, setCurrent] = useState<AskUserPayload | null>(null);
  const [textValue, setTextValue] = useState<string>('');
  const [selectedIds, setSelectedIds] = useState<string[]>([]);

  // Subscribe to `automation:ask_user` for the lifetime of the
  // component. Non-Tauri runtimes (pnpm dev / web preview) skip
  // the subscription so the import stays side-effect-free.
  useEffect(() => {
    if (!isTauriRuntime()) {
      return;
    }
    let unlisten: (() => void) | undefined;
    onAskUser((payload) => {
      log.info('received automation:ask_user', { correlationId: payload.correlationId });
      // Pre-fill the text input with the default value (if any)
      // so the user can just hit Enter on a pre-supplied suggestion.
      const prefilled =
        typeof payload.prompt.defaultValue === 'string'
          ? payload.prompt.defaultValue
          : '';
      setTextValue(prefilled);
      setSelectedIds([]);
      setCurrent(payload);
    })
      .then((fn) => {
        unlisten = fn;
      })
      .catch((e) => log.error('subscribe automation:ask_user failed', { error: e }));
    return () => {
      unlisten?.();
    };
  }, []);

  const close = useCallback(() => {
    setCurrent(null);
    setTextValue('');
    setSelectedIds([]);
  }, []);

  const handleSubmit = useCallback(
    async (value: unknown) => {
      if (!current) return;
      const correlationId = current.correlationId;
      try {
        await answerPrompt(correlationId, value, false);
      } catch (e) {
        log.error('answerPrompt failed', { correlationId, error: e });
      }
      close();
    },
    [current, close],
  );

  const handleCancel = useCallback(async () => {
    if (!current) return;
    const correlationId = current.correlationId;
    try {
      await cancelPrompt(correlationId);
    } catch (e) {
      log.error('cancelPrompt failed', { correlationId, error: e });
    }
    close();
  }, [current, close]);

  const handleTextSubmit = useCallback(() => {
    handleSubmit(textValue);
  }, [handleSubmit, textValue]);

  const handleMultiToggle = useCallback((id: string) => {
    setSelectedIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  }, []);

  const handleMultiSubmit = useCallback(() => {
    handleSubmit(selectedIds);
  }, [handleSubmit, selectedIds]);

  const prompt = current?.prompt;

  return (
    <Modal
      isOpen={!!current}
      onClose={handleCancel}
      title=""
      size="medium"
      showCloseButton={true}
      closeOnOverlayClick={false}
    >
      {current && prompt && (
        <div className="automation-prompt-modal">
          {/* Hero */}
          <div className="automation-prompt-modal__hero">
            <div className="automation-prompt-modal__icon-wrapper">
              <HelpCircle size={24} />
            </div>
            <h2 className="automation-prompt-modal__title">
              {INPUT_TYPE_LABEL[prompt.inputType]}
            </h2>
            <p className="automation-prompt-modal__question">{prompt.question}</p>
          </div>

          {/* Body — switches on inputType */}
          <div className="automation-prompt-modal__content">
            {prompt.inputType === 'text' && (
              <div className="automation-prompt-modal__field">
                <Input
                  type="text"
                  value={textValue}
                  onChange={(e) => setTextValue(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      handleTextSubmit();
                    }
                  }}
                  autoFocus
                  placeholder={INPUT_TYPE_LABEL.text}
                />
              </div>
            )}

            {prompt.inputType === 'choice' && (
              <div className="automation-prompt-modal__choices">
                {prompt.choices.map((c) => (
                  <Button
                    key={c.id}
                    type="button"
                    className="automation-prompt-modal__choice-btn"
                    variant="secondary"
                    size="small"
                    onClick={() => handleSubmit(c.id)}
                  >
                    {c.label}
                  </Button>
                ))}
              </div>
            )}

            {prompt.inputType === 'multichoice' && (
              <div className="automation-prompt-modal__choices">
                {prompt.choices.map((c) => (
                  <label
                    key={c.id}
                    className="automation-prompt-modal__multi-item"
                  >
                    <input
                      type="checkbox"
                      className="automation-prompt-modal__multi-checkbox"
                      checked={selectedIds.includes(c.id)}
                      onChange={() => handleMultiToggle(c.id)}
                    />
                    <span className="automation-prompt-modal__multi-label">
                      {c.label}
                    </span>
                  </label>
                ))}
              </div>
            )}

            {prompt.inputType === 'confirm' && (
              <div className="automation-prompt-modal__choices automation-prompt-modal__choices--confirm">
                <Button
                  type="button"
                  className="automation-prompt-modal__btn automation-prompt-modal__btn--cancel"
                  variant="ghost"
                  size="small"
                  onClick={() => handleSubmit('false')}
                >
                  <X size={14} />
                  否
                </Button>
                <Button
                  type="button"
                  className="automation-prompt-modal__btn automation-prompt-modal__btn--confirm"
                  variant="primary"
                  size="small"
                  onClick={() => handleSubmit('true')}
                >
                  <Check size={14} />
                  是
                </Button>
              </div>
            )}
          </div>

          {/* Footer — all types get a skip button;
              text and multichoice also get explicit submit. */}
          <div className="automation-prompt-modal__footer">
            <Button
              type="button"
              className="automation-prompt-modal__btn automation-prompt-modal__btn--skip"
              variant="ghost"
              size="small"
              onClick={handleCancel}
            >
              <SkipForward size={14} />
              跳过
            </Button>
            {(prompt.inputType === 'text' || prompt.inputType === 'multichoice') && (
              <Button
                type="button"
                className="automation-prompt-modal__btn automation-prompt-modal__btn--confirm"
                variant="primary"
                size="small"
                onClick={prompt.inputType === 'text' ? handleTextSubmit : handleMultiSubmit}
              >
                <Check size={14} />
                提交
              </Button>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
};
