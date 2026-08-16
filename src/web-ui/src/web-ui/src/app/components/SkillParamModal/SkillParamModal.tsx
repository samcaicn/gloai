import React, { useCallback, useMemo, useState } from 'react';
import { Braces, Check, SkipForward } from 'lucide-react';
import { Modal, Button, Input } from '@/component-library';
import './SkillParamModal.scss';

export interface SkillParamField {
  name: string;
  type: 'string' | 'number' | 'boolean';
  description?: string;
  enum?: string[];
  required?: boolean;
  defaultValue?: unknown;
}

export interface SkillParamModalProps {
  isOpen: boolean;
  skillName: string;
  skillDescription: string;
  skillContent: string;
  params: SkillParamField[];
  onConfirm: (values: Record<string, unknown>) => void;
  onSkip: () => void;
  onClose: () => void;
}

export const SkillParamModal: React.FC<SkillParamModalProps> = ({
  isOpen,
  skillName,
  skillDescription,
  skillContent,
  params,
  onConfirm,
  onSkip,
  onClose,
}) => {
  const [values, setValues] = useState<Record<string, unknown>>(() => {
    const initial: Record<string, unknown> = {};
    for (const p of params) {
      if (p.defaultValue !== undefined) {
        initial[p.name] = p.defaultValue;
      } else if (p.enum && p.enum.length > 0) {
        initial[p.name] = p.enum[0];
      }
    }
    return initial;
  });

  const handleChange = useCallback((name: string, value: unknown) => {
    setValues((prev) => ({ ...prev, [name]: value }));
  }, []);

  const handleConfirm = useCallback(() => {
    onConfirm(values);
  }, [onConfirm, values]);

  const hasParams = params.length > 0;

  const previewContent = useMemo(() => {
    const maxPreview = 400;
    let preview = skillContent.slice(0, maxPreview);
    if (skillContent.length > maxPreview) preview += '\n...';
    return preview;
  }, [skillContent]);

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title=""
      size="medium"
      showCloseButton={true}
      closeOnOverlayClick={false}
    >
      <div className="skill-param-modal">
        {/* Hero */}
        <div className="skill-param-modal__hero">
          <div className="skill-param-modal__icon-wrapper">
            <Braces size={22} />
          </div>
          <h2 className="skill-param-modal__title">{skillName}</h2>
          {skillDescription && (
            <p className="skill-param-modal__desc">{skillDescription}</p>
          )}
        </div>

        <div className="skill-param-modal__content">
          {/* Skill content preview */}
          <div>
            <h3 className="skill-param-modal__section-label">技能描述</h3>
            <div className="skill-param-modal__skill-content">
              {previewContent}
            </div>
          </div>

          {/* Params form */}
          {hasParams && (
            <div>
              <h3 className="skill-param-modal__section-label">执行参数</h3>
              <div className="skill-param-modal__params">
                {params.map((field) => (
                  <div key={field.name} className="skill-param-modal__field">
                    <label className="skill-param-modal__field-label">
                      {field.name}
                      {field.required && (
                        <span className="skill-param-modal__field-required">
                          必填
                        </span>
                      )}
                    </label>
                    {field.description && (
                      <span className="skill-param-modal__field-desc">
                        {field.description}
                      </span>
                    )}
                    {field.enum && field.enum.length > 0 ? (
                      <select
                        className="skill-param-modal__field-select"
                        value={String(values[field.name] ?? '')}
                        onChange={(e) => handleChange(field.name, e.target.value)}
                      >
                        {field.enum.map((opt) => (
                          <option key={opt} value={opt}>
                            {opt}
                          </option>
                        ))}
                      </select>
                    ) : field.type === 'number' ? (
                      <Input
                        type="number"
                        className="skill-param-modal__field-input"
                        value={String(values[field.name] ?? '')}
                        onChange={(e) =>
                          handleChange(field.name, Number(e.target.value))
                        }
                      />
                    ) : field.type === 'boolean' ? (
                      <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                        <input
                          type="checkbox"
                          checked={Boolean(values[field.name])}
                          onChange={(e) =>
                            handleChange(field.name, e.target.checked)
                          }
                          style={{ width: 16, height: 16, accentColor: 'var(--color-primary)' }}
                        />
                        <span style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>
                          启用
                        </span>
                      </label>
                    ) : (
                      <Input
                        type="text"
                        className="skill-param-modal__field-input"
                        value={String(values[field.name] ?? '')}
                        onChange={(e) => handleChange(field.name, e.target.value)}
                        placeholder={`输入${field.name}`}
                      />
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="skill-param-modal__footer">
          <Button
            type="button"
            className="skill-param-modal__btn skill-param-modal__btn--skip"
            variant="ghost"
            size="small"
            onClick={onSkip}
          >
            <SkipForward size={14} />
            跳过
          </Button>
          {hasParams && (
            <Button
              type="button"
              className="skill-param-modal__btn skill-param-modal__btn--confirm"
              variant="primary"
              size="small"
              onClick={handleConfirm}
            >
              <Check size={14} />
              确认执行
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
};
