/**
 * LocalModelPathsSection
 *
 * Stores the on-disk paths for the OCR and VLM models that
 * automation runs (PP-OCR, PaddleOCR-VL, …) load from at runtime.
 *
 * Storage layout in the configManager:
 *
 *   ai.local_models = {
 *     ocr_path: string,
 *     vlm_path: string,
 *   }
 *
 * The Rust side already has `change_model_path` /
 * `scan_models` / `delete_model` for the main model directory.
 * For now the OCR/VLM paths are read directly by the
 * automation layer via `configManager.getConfig('ai.local_models')`
 * — if we later need scan/delete per type we can extend the
 * Rust side or add a thin wrapper command.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FolderOpen, X, Save, Loader } from 'lucide-react';
import { open as openDialog } from '@tauri-apps/plugin-dialog';
import { Button, IconButton, Input } from '@/component-library';
import { ConfigPageSection, ConfigPageRow } from './common';
import { configManager } from '../services/ConfigManager';
import { notificationService } from '@/shared/notification-system';
import { createLogger } from '@/shared/utils/logger';
import './LocalModelPathsSection.scss';

const log = createLogger('LocalModelPathsSection');

export interface LocalModelPaths {
  ocrPath: string;
  vlmPath: string;
}

const DEFAULT_PATHS: LocalModelPaths = {
  ocrPath: '',
  vlmPath: '',
};

export const LocalModelPathsSection: React.FC = () => {
  const { t } = useTranslation('settings/ai-model');
  const [paths, setPaths] = useState<LocalModelPaths>(DEFAULT_PATHS);
  const [draft, setDraft] = useState<LocalModelPaths>(DEFAULT_PATHS);
  const [isSaving, setIsSaving] = useState(false);

  const loadPaths = useCallback(async () => {
    try {
      const stored = await configManager.getConfig<Partial<LocalModelPaths>>('ai.local_models');
      const next: LocalModelPaths = {
        ocrPath: typeof stored?.ocrPath === 'string' ? stored.ocrPath : '',
        vlmPath: typeof stored?.vlmPath === 'string' ? stored.vlmPath : '',
      };
      setPaths(next);
      setDraft(next);
    } catch (error) {
      log.warn('Failed to load local model paths', { error });
    }
  }, []);

  useEffect(() => {
    void loadPaths();
    const unsubscribe = configManager.watch('ai.local_models', () => {
      void loadPaths();
    });
    return () => {
      unsubscribe();
    };
  }, [loadPaths]);

  const handleBrowse = useCallback(async (field: keyof LocalModelPaths) => {
    try {
      const selected = await openDialog({
        multiple: false,
        directory: true,
        defaultPath: draft[field] || undefined,
      });
      if (typeof selected === 'string' && selected.trim()) {
        setDraft(prev => ({ ...prev, [field]: selected }));
      }
    } catch (error) {
      log.warn('Failed to open path dialog', { error });
      notificationService.error(t('localModelPaths.saveFailed', {
        error: error instanceof Error ? error.message : String(error),
      }));
    }
  }, [draft, t]);

  const handleClear = useCallback((field: keyof LocalModelPaths) => {
    setDraft(prev => ({ ...prev, [field]: '' }));
  }, []);

  const handleSave = useCallback(async () => {
    setIsSaving(true);
    try {
      const next: LocalModelPaths = {
        ocrPath: draft.ocrPath.trim(),
        vlmPath: draft.vlmPath.trim(),
      };
      await configManager.setConfig('ai.local_models', next);
      setPaths(next);
      notificationService.success(t('localModelPaths.saveSuccess'), { duration: 1500 });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      notificationService.error(t('localModelPaths.saveFailed', { error: message }));
    } finally {
      setIsSaving(false);
    }
  }, [draft, t]);

  const dirty = paths.ocrPath !== draft.ocrPath || paths.vlmPath !== draft.vlmPath;

  return (
    <ConfigPageSection
      title={t('localModelPaths.sectionTitle')}
      description={t('localModelPaths.sectionDescription')}
      extra={(
        <Button
          variant="primary"
          size="small"
          onClick={handleSave}
          disabled={!dirty || isSaving}
        >
          {isSaving ? <Loader size={14} className="bitfun-local-model-paths__spin" /> : <Save size={14} />}
          {t('proxy.save')}
        </Button>
      )}
    >
      <ConfigPageRow
        label={t('localModelPaths.ocrPath')}
        description={t('localModelPaths.ocrPathHint')}
        align="center"
      >
        <div className="bitfun-local-model-paths__row">
          <Input
            value={draft.ocrPath}
            onChange={(e) => setDraft(prev => ({ ...prev, ocrPath: e.target.value }))}
            placeholder={t('localModelPaths.placeholder')}
            inputSize="small"
          />
          <IconButton
            variant="ghost"
            size="small"
            onClick={() => handleBrowse('ocrPath')}
            tooltip={t('localModelPaths.browse')}
          >
            <FolderOpen size={14} />
          </IconButton>
          {draft.ocrPath && (
            <IconButton
              variant="ghost"
              size="small"
              onClick={() => handleClear('ocrPath')}
              tooltip={t('localModelPaths.clear')}
            >
              <X size={14} />
            </IconButton>
          )}
        </div>
      </ConfigPageRow>

      <ConfigPageRow
        label={t('localModelPaths.vlmPath')}
        description={t('localModelPaths.vlmPathHint')}
        align="center"
      >
        <div className="bitfun-local-model-paths__row">
          <Input
            value={draft.vlmPath}
            onChange={(e) => setDraft(prev => ({ ...prev, vlmPath: e.target.value }))}
            placeholder={t('localModelPaths.placeholder')}
            inputSize="small"
          />
          <IconButton
            variant="ghost"
            size="small"
            onClick={() => handleBrowse('vlmPath')}
            tooltip={t('localModelPaths.browse')}
          >
            <FolderOpen size={14} />
          </IconButton>
          {draft.vlmPath && (
            <IconButton
              variant="ghost"
              size="small"
              onClick={() => handleClear('vlmPath')}
              tooltip={t('localModelPaths.clear')}
            >
              <X size={14} />
            </IconButton>
          )}
        </div>
      </ConfigPageRow>
    </ConfigPageSection>
  );
};

export default LocalModelPathsSection;
