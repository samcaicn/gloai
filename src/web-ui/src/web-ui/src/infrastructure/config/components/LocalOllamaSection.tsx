/**
 * LocalOllamaSection
 *
 * Manages the local Ollama configuration that skills fall back to
 * when the user explicitly points a skill at `localhost:11434`.
 *
 *   * The base URL is hardcoded to `http://127.0.0.1:11434` (per
 *     the user's "本地写死" instruction) and is rendered as
 *     read-only text so the user can see what's actually being
 *     hit.
 *   * The API key is optional. Ollama's default config doesn't
 *     require one, but operators sometimes front it with a
 *     reverse proxy that does.
 *   * `enabledModelIds` is the list the rest of the app reads
 *     from when a skill says "use the local Ollama" — the model
 *     picker here lets the user curate that allow-list.
 *
 * The whole config is stored under
 * `configManager['ai.local_ollama']` so any other module (e.g.
 * the skills router) can `getConfig('ai.local_ollama')` without
 * having to import this component.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Server,
  Plus,
  X,
  RefreshCw,
  Loader,
  Eye,
  EyeOff,
  CheckCircle2,
  XCircle,
} from 'lucide-react';
import { Button, IconButton, Input } from '@/component-library';
import { ConfigPageSection, ConfigPageRow } from './common';
import { configManager } from '../services/ConfigManager';
import { aiApi } from '@/infrastructure/api/service-api/AIApi';
import { notificationService } from '@/shared/notification-system';
import { createLogger } from '@/shared/utils/logger';
import './LocalOllamaSection.scss';

const log = createLogger('LocalOllamaSection');

const OLLAMA_BASE_URL = 'http://127.0.0.1:11434';

export interface LocalOllamaConfig {
  baseUrl: string;
  apiKey: string;
  enabledModelIds: string[];
}

const DEFAULT_CONFIG: LocalOllamaConfig = {
  baseUrl: OLLAMA_BASE_URL,
  apiKey: '',
  enabledModelIds: [],
};

const sanitizeList = (list: unknown): string[] => {
  if (!Array.isArray(list)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of list) {
    if (typeof item !== 'string') continue;
    const trimmed = item.trim();
    if (!trimmed) continue;
    if (seen.has(trimmed)) continue;
    seen.add(trimmed);
    out.push(trimmed);
  }
  return out;
};

export const LocalOllamaSection: React.FC = () => {
  const { t } = useTranslation('settings/ai-model');
  const [config, setConfig] = useState<LocalOllamaConfig>(DEFAULT_CONFIG);
  const [draft, setDraft] = useState<LocalOllamaConfig>(DEFAULT_CONFIG);
  const [newModelId, setNewModelId] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [isFetching, setIsFetching] = useState(false);
  const [isTesting, setIsTesting] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [health, setHealth] = useState<'idle' | 'ok' | 'failed'>('idle');
  const [healthMessage, setHealthMessage] = useState<string | null>(null);

  const loadConfig = useCallback(async () => {
    try {
      const stored = await configManager.getConfig<Partial<LocalOllamaConfig>>('ai.local_ollama');
      const merged: LocalOllamaConfig = {
        baseUrl: typeof stored?.baseUrl === 'string' && stored.baseUrl.trim()
          ? stored.baseUrl
          : OLLAMA_BASE_URL,
        apiKey: typeof stored?.apiKey === 'string' ? stored.apiKey : '',
        enabledModelIds: sanitizeList(stored?.enabledModelIds),
      };
      setConfig(merged);
      setDraft(merged);
    } catch (error) {
      log.warn('Failed to load local Ollama config', { error });
    }
  }, []);

  useEffect(() => {
    void loadConfig();
    const unsubscribe = configManager.watch('ai.local_ollama', () => {
      void loadConfig();
    });
    return () => {
      unsubscribe();
    };
  }, [loadConfig]);

  const handleTestConnection = useCallback(async () => {
    setIsTesting(true);
    try {
      const result = await aiApi.testOllamaEndpoint();
      if (result.ok) {
        setHealth('ok');
        setHealthMessage(null);
        notificationService.success(t('localOllama.connectionOk'), { duration: 1500 });
      } else {
        setHealth('failed');
        setHealthMessage(result.message ?? '');
        notificationService.error(t('localOllama.connectionFailed', {
          message: result.message ?? '',
        }));
      }
    } catch (error) {
      setHealth('failed');
      const message = error instanceof Error ? error.message : String(error);
      setHealthMessage(message);
      notificationService.error(t('localOllama.loadFailed', { error: message }));
    } finally {
      setIsTesting(false);
    }
  }, [t]);

  const handleFetchModels = useCallback(async () => {
    setIsFetching(true);
    try {
      const result = await aiApi.listOllamaModels();
      const seen = new Set(draft.enabledModelIds);
      const newIds = (Array.isArray(result?.models) ? result.models : [])
        .map(m => m.name)
        .filter((name): name is string => typeof name === 'string' && name.trim().length > 0)
        .filter(name => !seen.has(name));
      if (newIds.length === 0) {
        notificationService.info(t('localOllama.empty'), { duration: 1500 });
        return;
      }
      setDraft(prev => ({
        ...prev,
        enabledModelIds: [...prev.enabledModelIds, ...newIds],
      }));
      notificationService.success(t('localOllama.saveSuccess'), { duration: 1500 });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      notificationService.error(t('localOllama.loadFailed', { error: message }));
    } finally {
      setIsFetching(false);
    }
  }, [draft.enabledModelIds, t]);

  const handleAddModel = useCallback(() => {
    const trimmed = newModelId.trim();
    if (!trimmed) return;
    if (draft.enabledModelIds.includes(trimmed)) {
      notificationService.info(t('localOllama.addModelPlaceholder'), { duration: 1500 });
      return;
    }
    setDraft(prev => ({
      ...prev,
      enabledModelIds: [...prev.enabledModelIds, trimmed],
    }));
    setNewModelId('');
  }, [newModelId, draft.enabledModelIds, t]);

  const handleRemoveModel = useCallback((modelId: string) => {
    setDraft(prev => ({
      ...prev,
      enabledModelIds: prev.enabledModelIds.filter(id => id !== modelId),
    }));
  }, []);

  const handleSave = useCallback(async () => {
    setIsSaving(true);
    try {
      const next: LocalOllamaConfig = {
        baseUrl: OLLAMA_BASE_URL,
        apiKey: draft.apiKey.trim(),
        enabledModelIds: sanitizeList(draft.enabledModelIds),
      };
      await configManager.setConfig('ai.local_ollama', next);
      setConfig(next);
      setDraft(next);
      notificationService.success(t('localOllama.saveSuccess'), { duration: 1500 });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      notificationService.error(t('localOllama.saveFailed', { error: message }));
    } finally {
      setIsSaving(false);
    }
  }, [draft, t]);

  const dirty = JSON.stringify(config) !== JSON.stringify(draft);

  return (
    <ConfigPageSection
      title={t('localOllama.sectionTitle')}
      description={t('localOllama.sectionDescription')}
      extra={(
        <div className="bitfun-local-ollama__actions">
          <Button
            variant="secondary"
            size="small"
            onClick={handleTestConnection}
            disabled={isTesting}
          >
            {isTesting ? <Loader size={14} className="bitfun-local-ollama__spin" /> : <Server size={14} />}
            {t('localOllama.testConnection')}
          </Button>
          <Button
            variant="primary"
            size="small"
            onClick={handleSave}
            disabled={!dirty || isSaving}
          >
            {isSaving ? <Loader size={14} className="bitfun-local-ollama__spin" /> : null}
            {t('proxy.save')}
          </Button>
        </div>
      )}
    >
      <ConfigPageRow label={t('localOllama.baseUrlLabel')} align="center">
        <Input
          value={OLLAMA_BASE_URL}
          readOnly
          onFocus={(e) => e.currentTarget.select()}
          inputSize="small"
          className="bitfun-local-ollama__readonly-input"
        />
      </ConfigPageRow>

      <ConfigPageRow
        label={t('localOllama.apiKeyLabel')}
        description={t('localOllama.apiKeyHint')}
        align="center"
      >
        <div className="bitfun-local-ollama__api-key-row">
          <Input
            type={showApiKey ? 'text' : 'password'}
            value={draft.apiKey}
            onChange={(e) => setDraft(prev => ({ ...prev, apiKey: e.target.value }))}
            placeholder="••••••••"
            inputSize="small"
          />
          <IconButton
            variant="ghost"
            size="small"
            onClick={() => setShowApiKey(prev => !prev)}
            tooltip={showApiKey ? '隐藏' : '显示'}
          >
            {showApiKey ? <EyeOff size={14} /> : <Eye size={14} />}
          </IconButton>
          {health === 'ok' && (
            <span className="bitfun-local-ollama__health-ok">
              <CheckCircle2 size={14} />
              {t('localOllama.connectionOk')}
            </span>
          )}
          {health === 'failed' && (
            <span className="bitfun-local-ollama__health-failed">
              <XCircle size={14} />
              {t('localOllama.connectionFailed', { message: healthMessage ?? '' })}
            </span>
          )}
        </div>
      </ConfigPageRow>

      <ConfigPageRow
        label={t('localOllama.enabledModels')}
        description={t('localOllama.enabledModelsHint')}
        align="start"
        multiline
        wide
      >
        <div className="bitfun-local-ollama__enabled-models">
          <div className="bitfun-local-ollama__model-add-row">
            <Input
              value={newModelId}
              onChange={(e) => setNewModelId(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleAddModel();
                }
              }}
              placeholder={t('localOllama.addModelPlaceholder')}
              inputSize="small"
            />
            <Button
              variant="secondary"
              size="small"
              onClick={handleAddModel}
              disabled={!newModelId.trim()}
            >
              <Plus size={14} />
              {t('localOllama.addModel')}
            </Button>
            <Button
              variant="ghost"
              size="small"
              onClick={handleFetchModels}
              disabled={isFetching}
            >
              {isFetching
                ? <Loader size={14} className="bitfun-local-ollama__spin" />
                : <RefreshCw size={14} />}
              {isFetching ? t('localOllama.fetching') : t('localOllama.fetchModels')}
            </Button>
          </div>
          {draft.enabledModelIds.length === 0 ? (
            <div className="bitfun-local-ollama__model-empty">{t('localOllama.empty')}</div>
          ) : (
            <div className="bitfun-local-ollama__model-list">
              {draft.enabledModelIds.map(id => (
                <span key={id} className="bitfun-local-ollama__model-chip">
                  <span className="bitfun-local-ollama__model-chip-label">{id}</span>
                  <button
                    type="button"
                    className="bitfun-local-ollama__model-chip-remove"
                    onClick={() => handleRemoveModel(id)}
                    aria-label={t('localOllama.removeModel')}
                  >
                    <X size={12} />
                  </button>
                </span>
              ))}
            </div>
          )}
        </div>
      </ConfigPageRow>
    </ConfigPageSection>
  );
};

export default LocalOllamaSection;
