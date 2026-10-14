import React from 'react';
import { useTranslation } from 'react-i18next';
import type { HarnessManifest } from '@/common/kyrn/manifest';
import { muErrorText, type MuError } from './muError';

type MuErrorMessageProps = {
  error: MuError;
  /** Resolves an option's label for `optionValue`. */
  manifest?: HarnessManifest;
  /** Puts the sentence into a sentence of the screen's own, e.g. "Not saved: {{reason}}". */
  frame?: (reason: string) => string;
};

/** A raw library, engine or OS message under a translated sentence: smaller, secondary, left to right. */
export function ErrorDetail({ children }: { children: React.ReactNode }) {
  return (
    <div className='mt-2px text-12px leading-18px text-t-secondary break-words' dir='ltr' data-testid='mu-error-detail'>
      {children}
    </div>
  );
}

/** A failure in the app language, with the raw message under it when that says more. */
export default function MuErrorMessage({ error, manifest, frame }: MuErrorMessageProps) {
  const { t, i18n } = useTranslation();
  const { text, detail } = muErrorText(t, i18n.language, error, manifest);
  return (
    <>
      <div>{frame ? frame(text) : text}</div>
      {detail ? <ErrorDetail>{detail}</ErrorDetail> : null}
    </>
  );
}
