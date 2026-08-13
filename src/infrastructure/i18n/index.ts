import { createContext, createElement, useContext, useMemo, type ReactNode } from "react";
import { en } from "./en";
import { zh, type MessageKey } from "./zh";
import type { Locale } from "@/shared/types";

const dictionaries = { zh, en } as const;

interface I18nValue {
  locale: Locale;
  t: (key: MessageKey) => string;
}

const I18nContext = createContext<I18nValue>({
  locale: "zh",
  t: (key) => zh[key],
});

export function I18nProvider({
  locale,
  children,
}: {
  locale: Locale;
  children: ReactNode;
}) {
  const value = useMemo<I18nValue>(() => {
    const table = dictionaries[locale] ?? zh;
    return {
      locale,
      t: (key) => table[key] ?? zh[key] ?? key,
    };
  }, [locale]);
  return createElement(I18nContext.Provider, { value }, children);
}

export function useI18n(): I18nValue {
  return useContext(I18nContext);
}

export type { MessageKey };
