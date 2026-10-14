import { isBackendHttpError } from '@/common/adapter/httpBridge';
import { formatByteSize } from '@/renderer/services/i18n/format';
import { formatNameList } from '@/renderer/services/i18n/list';
import type { TFunction } from 'i18next';

type SkillImportFailure = {
  source_name: string;
  code: string;
  error_path?: string;
  actual_bytes?: number;
  limit_bytes?: number;
  line?: number;
  column?: number;
};

type SkillImportResult = {
  skill_name?: string;
  skill_names?: string[];
  failed?: SkillImportFailure[];
};

type SkillImportNotice = {
  type: 'success' | 'warning' | 'error';
  message: string;
  importedNames: string[];
};

const SKILL_IMPORT_ERROR_CODES = new Set([
  'SKILL_INVALID_FRONTMATTER',
  'SKILL_IMPORT_NO_SKILL_FOUND',
  'SKILL_IMPORT_INVALID_SOURCE',
  'SKILL_IMPORT_SYMLINK_ENTRY',
  'SKILL_IMPORT_FILE_TOO_LARGE',
  'SKILL_IMPORT_TOTAL_TOO_LARGE',
  'SKILL_IMPORT_INVALID_ZIP',
  'SKILL_IMPORT_INVALID_NAME',
  'SKILL_IMPORT_FAILED',
]);

const getImportedNames = (result: SkillImportResult): string[] =>
  result.skill_names?.length ? result.skill_names : result.skill_name ? [result.skill_name] : [];

const getSkillImportCodeMessage = (code: string, t: TFunction): string =>
  SKILL_IMPORT_ERROR_CODES.has(code)
    ? t(`settings.skillsHub.importErrors.${code}`, {
        defaultValue: t('settings.skillsHub.importError'),
      })
    : t('settings.skillsHub.importError');

/**
 * A skill import size limit or file size in the app language ("12 MB", "1,5 MB" in de-DE): whole units from 10 up,
 * one decimal below. Null when the backend sent no size.
 *
 * @param language app language (`i18n.language`)
 */
export const formatSkillSize = (bytes: number | undefined, language?: string): string | null => {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes)) return null;
  if (bytes < 1024) return formatByteSize(bytes, language, 0);
  const unit = bytes >= 1024 * 1024 ? 1024 * 1024 : 1024;
  return formatByteSize(bytes, language, bytes / unit >= 10 ? 0 : 1);
};

const getFailureDetailMessage = (failure: SkillImportFailure, t: TFunction, language?: string): string => {
  if (failure.code === 'SKILL_IMPORT_FILE_TOO_LARGE') {
    const actual = formatSkillSize(failure.actual_bytes, language);
    const limit = formatSkillSize(failure.limit_bytes, language);
    if (failure.error_path && actual && limit) {
      return t('settings.skillsHub.importFailureFileSizeDetail', {
        path: failure.error_path,
        actual,
        limit,
      });
    }
  }
  if (failure.code === 'SKILL_IMPORT_TOTAL_TOO_LARGE') {
    const actual = formatSkillSize(failure.actual_bytes, language);
    const limit = formatSkillSize(failure.limit_bytes, language);
    if (actual && limit) {
      return t('settings.skillsHub.importFailureTotalSizeDetail', {
        actual,
        limit,
      });
    }
  }
  if (failure.line || failure.column) {
    return t('settings.skillsHub.importFailureLocationDetail', {
      line: failure.line ?? '-',
      column: failure.column ?? '-',
    });
  }
  return '';
};

/** A sentence's own full stop, dropped when the sentence becomes one item of a list. */
const TRAILING_STOP = /[.。．!！]+$/u;

/**
 * "name: why" for each failed source, joined by the app language's list separator ("; " in English, "；" in Chinese).
 * The reasons are whole sentences, so a conjunction list ("A, B, and C") would read wrongly.
 */
const formatFailures = (failures: SkillImportFailure[], t: TFunction, language?: string): string =>
  failures
    .map((failure) => {
      const detail = getFailureDetailMessage(failure, t, language);
      const message = getSkillImportCodeMessage(failure.code, t).trim().replace(TRAILING_STOP, '');
      return detail
        ? t('settings.skillsHub.importFailureItemWithDetail', { name: failure.source_name, message, detail })
        : t('settings.skillsHub.importFailureItem', { name: failure.source_name, message });
    })
    .join(t('settings.skillsHub.importFailureSeparator'));

export const getSkillImportErrorMessage = (error: unknown, t: TFunction): string => {
  if (isBackendHttpError(error) && error.code) {
    return getSkillImportCodeMessage(error.code, t);
  }
  return t('settings.skillsHub.importError');
};

/**
 * The toast after an import, in the app language.
 *
 * @param language app language (`i18n.language`), for sizes and lists
 */
export const buildSkillImportNotice = (
  result: SkillImportResult,
  t: TFunction,
  language?: string
): SkillImportNotice => {
  const importedNames = getImportedNames(result);
  const failures = result.failed ?? [];

  if (failures.length > 0 && importedNames.length > 0) {
    return {
      type: 'warning',
      importedNames,
      // `successCount` feeds the older singular-plural-agnostic text that locales without plural forms still use.
      message: t('settings.skillsHub.importPartialSuccess', {
        count: importedNames.length,
        successCount: importedNames.length,
        failureCount: failures.length,
        failures: formatFailures(failures, t, language),
      }),
    };
  }

  if (failures.length > 0) {
    return {
      type: 'error',
      importedNames,
      // `failureCount` feeds the older text that locales without plural forms still use.
      message: t('settings.skillsHub.importAllFailed', {
        count: failures.length,
        failureCount: failures.length,
        failures: formatFailures(failures, t, language),
      }),
    };
  }

  return {
    type: 'success',
    importedNames,
    message: t('settings.skillsHub.importSuccessDetailed', {
      count: importedNames.length,
      names: formatNameList(importedNames, language),
    }),
  };
};
