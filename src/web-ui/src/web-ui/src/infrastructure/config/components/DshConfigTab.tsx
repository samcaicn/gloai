import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  LoaderCircle,
  Plus,
  RefreshCw,
  Save,
  Server,
  Terminal,
  Trash2,
} from 'lucide-react';
import { Button, Input, Switch, Textarea } from '@/component-library';
import {
  ConfigPageContent,
  ConfigPageHeader,
  ConfigPageLayout,
  ConfigPageSection,
} from './common';
import { dshAPI, type DshUpstreamConfig } from '../../api/dsh';
import { runtimeRegistryAPI } from '../../api/runtimeRegistry';
import { useNotification } from '@/shared/notification-system';
import './DshConfigTab.scss';

interface FormState {
  id: string;
  displayName: string;
  endpoint: string;
  cliArgsTemplate: string;
  model: string;
  apiKey: string;
  enabled: boolean;
}

const blankForm = (): FormState => ({
  id: '',
  displayName: '',
  endpoint: '',
  cliArgsTemplate: '',
  model: '',
  apiKey: '',
  enabled: true,
});

const DshConfigTab: React.FC = () => {
  const { t } = useTranslation('settings/dsh');
  const { error: notifyError, success: notifySuccess } = useNotification();

  const [list, setList] = useState<DshUpstreamConfig[]>([]);
  const [liveInstalled, setLiveInstalled] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<DshUpstreamConfig | null>(null);
  const [form, setForm] = useState<FormState>(blankForm());
  const [formError, setFormError] = useState('');

  const refresh = useCallback(async () => {
    try {
      const [items, snap] = await Promise.all([
        dshAPI.listUpstreams(),
        runtimeRegistryAPI.listRuntimes().catch(() => null),
      ]);
      setList(items);
      const map: Record<string, boolean> = {};
      if (snap) {
        for (const inst of snap.instances) {
          if (inst.providerId.startsWith('dsh:')) {
            map[inst.providerId] = inst.installed;
          }
        }
      }
      setLiveInstalled(map);
    } catch (error) {
      notifyError(error instanceof Error ? error.message : String(error), {
        title: t('notifications.loadFailed'),
      });
    } finally {
      setLoading(false);
    }
  }, [notifyError, t]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const openAdd = () => {
    setEditing(null);
    setForm(blankForm());
    setFormError('');
  };

  const openEdit = (u: DshUpstreamConfig) => {
    setEditing(u);
    setForm({
      id: u.id,
      displayName: u.displayName,
      endpoint: u.endpoint,
      cliArgsTemplate: (u.cliArgsTemplate ?? []).join('\n'),
      model: u.model ?? '',
      apiKey: '', // secret is never echoed back; blank = keep existing
      enabled: u.enabled,
    });
    setFormError('');
  };

  const validateAndSave = async () => {
    setFormError('');
    const id = form.id.trim();
    if (!id) {
      setFormError(t('form.idRequired'));
      return;
    }
    const endpoint = form.endpoint.trim();
    if (!endpoint) {
      setFormError(t('form.endpointRequired'));
      return;
    }
    const isHttp = /^https?:\/\//.test(endpoint);
    const cli = form.cliArgsTemplate
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean);
    if (!isHttp && cli.length === 0) {
      setFormError(t('form.binaryNeedsTemplate'));
      return;
    }
    setSaving(true);
    try {
      const next = await dshAPI.upsertUpstream({
        id,
        displayName: form.displayName.trim() || id,
        endpoint,
        cliArgsTemplate: isHttp ? null : cli,
        model: form.model.trim() || null,
        apiKey: form.apiKey || null,
        enabled: form.enabled,
      });
      setList(next);
      setEditing(null);
      setForm(blankForm());
      notifySuccess(t('notifications.saved'));
    } catch (error) {
      notifyError(error instanceof Error ? error.message : String(error), {
        title: t('notifications.saveFailed'),
      });
    } finally {
      setSaving(false);
    }
  };

  const remove = async (id: string) => {
    try {
      const next = await dshAPI.removeUpstream(id);
      setList(next);
      notifySuccess(t('notifications.removed'));
    } catch (error) {
      notifyError(error instanceof Error ? error.message : String(error), {
        title: t('notifications.removeFailed'),
      });
    }
  };

  const toggleEnabled = async (u: DshUpstreamConfig) => {
    try {
      const next = await dshAPI.setUpstreamEnabled(u.id, !u.enabled);
      setList(next);
    } catch (error) {
      notifyError(error instanceof Error ? error.message : String(error), {
        title: t('notifications.toggleFailed'),
      });
    }
  };

  const transportOf = (endpoint: string): 'http' | 'cmd' => {
    const e = endpoint.trim();
    return /^https?:\/\//.test(e) ? 'http' : 'cmd';
  };

  return (
    <ConfigPageLayout className="bitfun-dsh">
      <ConfigPageHeader title={t('title')} subtitle={t('subtitle')} />

      <ConfigPageContent>
        <div className="bitfun-dsh__toolbar">
          <Button
            variant="primary"
            size="small"
            onClick={openAdd}
            disabled={editing !== null}
          >
            <Plus size={14} />
            {t('actions.add')}
          </Button>
          <Button
            variant="secondary"
            size="small"
            onClick={() => void refresh()}
            isLoading={loading}
          >
            <RefreshCw size={14} />
            {t('actions.refresh')}
          </Button>
        </div>

        <ConfigPageSection title={t('section.list')} description={t('section.listDesc')}>
          {loading ? (
            <div className="bitfun-dsh__empty">
              <LoaderCircle size={14} className="bitfun-dsh__spinner" />
              {t('list.loading')}
            </div>
          ) : list.length === 0 ? (
            <div className="bitfun-dsh__empty">{t('list.empty')}</div>
          ) : (
            <div className="bitfun-dsh__list">
              {list.map((u) => {
                const transport = transportOf(u.endpoint);
                const installed = liveInstalled[`dsh:${u.id}`] ?? false;
                return (
                  <div key={u.id} className="bitfun-dsh__row">
                    <span className="bitfun-dsh__row-icon">
                      <Server size={16} />
                    </span>
                    <div className="bitfun-dsh__row-main">
                      <span className="bitfun-dsh__row-name">
                        {u.displayName || u.id}
                        <span className="bitfun-dsh__row-id">dsh{u.id}</span>
                      </span>
                      <p className="bitfun-dsh__row-endpoint">{u.endpoint}</p>
                      <div className="bitfun-dsh__row-tags">
                        <span
                          className={`bitfun-dsh__tag bitfun-dsh__tag--${transport}`}
                        >
                          {transport === 'http' ? (
                            <Terminal size={11} />
                          ) : (
                            <Server size={11} />
                          )}
                          {transport === 'http'
                            ? t('form.transportHttp')
                            : t('form.transportCmd')}
                        </span>
                        {u.model && (
                          <span className="bitfun-dsh__tag">{u.model}</span>
                        )}
                        <span
                          className={`bitfun-dsh__status ${
                            installed ? 'is-ok' : 'is-bad'
                          }`}
                        >
                          {installed
                            ? t('list.statusOk')
                            : t('list.statusBad')}
                        </span>
                      </div>
                    </div>
                    <div className="bitfun-dsh__row-actions">
                      <Switch
                        size="small"
                        checked={u.enabled}
                        onChange={(e) => {
                          void toggleEnabled({
                            ...u,
                            enabled: (e.target as HTMLInputElement).checked,
                          });
                        }}
                      />
                      <Button
                        variant="secondary"
                        size="small"
                        onClick={() => openEdit(u)}
                        disabled={editing !== null}
                      >
                        {t('actions.edit')}
                      </Button>
                      <Button
                        variant="secondary"
                        size="small"
                        onClick={() => void remove(u.id)}
                        disabled={editing !== null}
                      >
                        <Trash2 size={14} />
                        {t('actions.remove')}
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </ConfigPageSection>

        {editing !== null && (
          <ConfigPageSection
            title={editing ? t('form.titleEdit') : t('form.titleAdd')}
            description={t('form.desc')}
          >
            <div className="bitfun-dsh__form">
              <label className="bitfun-dsh__field">
                <span className="bitfun-dsh__label">{t('form.id')}</span>
                <Input
                  value={form.id}
                  onChange={(e) => setForm({ ...form, id: e.target.value })}
                  placeholder={t('form.idHint')}
                  disabled={editing !== null}
                  size="medium"
                />
              </label>

              <label className="bitfun-dsh__field">
                <span className="bitfun-dsh__label">{t('form.displayName')}</span>
                <Input
                  value={form.displayName}
                  onChange={(e) =>
                    setForm({ ...form, displayName: e.target.value })
                  }
                  placeholder={t('form.displayName')}
                  size="medium"
                />
              </label>

              <label className="bitfun-dsh__field">
                <span className="bitfun-dsh__label">{t('form.endpoint')}</span>
                <Input
                  value={form.endpoint}
                  onChange={(e) =>
                    setForm({ ...form, endpoint: e.target.value })
                  }
                  placeholder={t('form.endpointHint')}
                  size="medium"
                />
                <span className="bitfun-dsh__hint">
                  {transportOf(form.endpoint) === 'http'
                    ? t('form.transportHttp')
                    : form.endpoint.trim()
                      ? t('form.transportCmd')
                      : t('form.endpointHint')}
                </span>
              </label>

              {transportOf(form.endpoint) === 'cmd' && (
                <label className="bitfun-dsh__field">
                  <span className="bitfun-dsh__label">
                    {t('form.cliArgsTemplate')}
                  </span>
                  <Textarea
                    value={form.cliArgsTemplate}
                    onChange={(e) =>
                      setForm({ ...form, cliArgsTemplate: e.target.value })
                    }
                    placeholder={t('form.cliArgsTemplateHint')}
                    rows={4}
                    spellCheck={false}
                  />
                </label>
              )}

              <label className="bitfun-dsh__field">
                <span className="bitfun-dsh__label">{t('form.model')}</span>
                <Input
                  value={form.model}
                  onChange={(e) => setForm({ ...form, model: e.target.value })}
                  placeholder={t('form.model')}
                  size="medium"
                />
              </label>

              <label className="bitfun-dsh__field">
                <span className="bitfun-dsh__label">{t('form.apiKey')}</span>
                <Input
                  type="password"
                  value={form.apiKey}
                  onChange={(e) => setForm({ ...form, apiKey: e.target.value })}
                  placeholder={t('form.apiKey')}
                  size="medium"
                />
                <span className="bitfun-dsh__hint">{t('form.apiKeyHint')}</span>
              </label>

              <label className="bitfun-dsh__field bitfun-dsh__field--row">
                <Switch
                  size="small"
                  checked={form.enabled}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      enabled: (e.target as HTMLInputElement).checked,
                    })
                  }
                />
                <span className="bitfun-dsh__label">{t('form.enabled')}</span>
              </label>

              {formError && <div className="bitfun-dsh__error">{formError}</div>}

              <div className="bitfun-dsh__form-actions">
                <Button
                  variant="ghost"
                  size="small"
                  onClick={() => {
                    setEditing(null);
                    setForm(blankForm());
                    setFormError('');
                  }}
                >
                  {t('actions.cancel')}
                </Button>
                <Button
                  variant="primary"
                  size="small"
                  onClick={() => void validateAndSave()}
                  isLoading={saving}
                >
                  <Save size={14} />
                  {t('actions.save')}
                </Button>
              </div>
            </div>
          </ConfigPageSection>
        )}
      </ConfigPageContent>
    </ConfigPageLayout>
  );
};

export default DshConfigTab;
