import { useEffect, useRef, useState } from 'react';

interface VerificationCodeInputProps {
  length?: number;
  disabled?: boolean;
  placeholder?: string;
  onComplete: (code: string) => void;
  onChange?: (code: string) => void;
}

/**
 * 分格验证码输入框（OTP 风格）。每个字符独占一格，支持：
 *  - 退格回退一格
 *  - 粘贴自动填充
 *  - 全部填满后触发 onComplete
 *
 * 用于设备绑定 / 操作员审核的 join_code 输入。
 */
export function VerificationCodeInput({
  length = 6,
  disabled = false,
  placeholder = '·',
  onComplete,
  onChange,
}: VerificationCodeInputProps) {
  const [values, setValues] = useState<string[]>(() => Array(length).fill(''));
  const inputsRef = useRef<Array<HTMLInputElement | null>>([]);

  useEffect(() => {
    const code = values.join('');
    onChange?.(code);
    if (code.length === length && !values.includes('')) {
      onComplete(code);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [values]);

  const focusIndex = (i: number) => {
    const el = inputsRef.current[i];
    if (el) {
      el.focus();
      el.select();
    }
  };

  const setChar = (i: number, ch: string) => {
    setValues((prev) => {
      const next = [...prev];
      next[i] = ch;
      return next;
    });
  };

  const handleChange = (i: number, raw: string) => {
    const ch = raw.replace(/[^a-zA-Z0-9]/g, '').slice(-1).toUpperCase();
    if (!ch) return;
    setChar(i, ch);
    if (i < length - 1) focusIndex(i + 1);
  };

  const handleKeyDown = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace') {
      e.preventDefault();
      if (values[i]) {
        setChar(i, '');
      } else if (i > 0) {
        setChar(i - 1, '');
        focusIndex(i - 1);
      }
    } else if (e.key === 'ArrowLeft' && i > 0) {
      focusIndex(i - 1);
    } else if (e.key === 'ArrowRight' && i < length - 1) {
      focusIndex(i + 1);
    }
  };

  const handlePaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const text = e.clipboardData.getData('text').replace(/[^a-zA-Z0-9]/g, '').toUpperCase().slice(0, length);
    if (!text) return;
    const next = Array(length).fill('');
    for (let k = 0; k < text.length; k++) next[k] = text[k];
    setValues(next);
    focusIndex(Math.min(text.length, length - 1));
  };

  return (
    <div className="code-input" role="group" aria-label="verification code">
      {values.map((v, i) => (
        <input
          key={i}
          ref={(el) => {
            inputsRef.current[i] = el;
          }}
          className="code-input__box"
          type="text"
          inputMode="text"
          autoComplete="one-time-code"
          maxLength={1}
          disabled={disabled}
          value={v}
          placeholder={placeholder}
          onChange={(e) => handleChange(i, e.target.value)}
          onKeyDown={(e) => handleKeyDown(i, e)}
          onPaste={handlePaste}
          onFocus={(e) => e.currentTarget.select()}
          aria-label={`digit ${i + 1}`}
        />
      ))}
    </div>
  );
}
