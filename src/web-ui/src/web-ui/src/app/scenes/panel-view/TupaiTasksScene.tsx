/**
 * TupaiTasksScene — 任务调度面板（UI-5-2）。
 *
 * 对接后端 cron 命令（get_cron_jobs / create_cron_job / pause_cron_job /
 * resume_cron_job / trigger_cron_job / delete_cron_job），替代原 localStorage 方案。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Play, RefreshCw, Trash2, Pause, Square } from 'lucide-react';
import { cronList, cronCreate, cronPause, cronResume, cronTrigger, cronDelete } from '@/infrastructure/api/tupai';
import type { CronJob } from '@/infrastructure/api/tupai';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { createLogger } from '@/shared/utils/logger';
import './TupaiTasksScene.scss';

const log = createLogger('TupaiTasksScene');

/** 前端表单用的新建任务结构。 */
interface NewTaskForm {
  name: string;
  schedule: string;
}

const EMPTY_FORM: NewTaskForm = {
  name: '',
  schedule: '*/5 * * * *',
};

const TupaiTasksScene: React.FC = () => {
  const { t } = useI18n('common');
  const [jobs, setJobs] = useState<CronJob[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState<boolean>(false);
  const [form, setForm] = useState<NewTaskForm>(EMPTY_FORM);
  const [creating, setCreating] = useState<boolean>(false);

  // ---- 加载 cron 任务 ----
  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const jobList = await cronList();
      setJobs(jobList);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to load cron jobs', err);
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  // ---- 新建任务 ----
  const handleOpenForm = useCallback(() => {
    setForm(EMPTY_FORM);
    setShowForm(true);
  }, []);

  const handleCancelForm = useCallback(() => {
    setShowForm(false);
    setForm(EMPTY_FORM);
  }, []);

  const handleSubmitForm = useCallback(async () => {
    if (!form.name.trim() || !form.schedule.trim()) return;
    setCreating(true);
    try {
      await cronCreate({
        prompt: form.name.trim(),
        schedule: form.schedule.trim(),
        name: form.name.trim(),
        deliver: null,
      });
      setShowForm(false);
      setForm(EMPTY_FORM);
      void loadAll();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to create cron job', err);
      setError(t('tasksScene.createFailed', { error: message }));
    } finally {
      setCreating(false);
    }
  }, [form, loadAll, t]);

  // ---- 操作按钮 ----
  const handleDelete = useCallback(async (id: string) => {
    try {
      await cronDelete(id);
      void loadAll();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to delete cron job', err);
      setError(t('tasksScene.deleteFailed', { error: message }));
    }
  }, [loadAll, t]);

  const handlePause = useCallback(async (id: string) => {
    try {
      await cronPause(id);
      void loadAll();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to pause cron job', err);
      setError(t('tasksScene.pauseFailed', { error: message }));
    }
  }, [loadAll, t]);

  const handleResume = useCallback(async (id: string) => {
    try {
      await cronResume(id);
      void loadAll();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to resume cron job', err);
      setError(t('tasksScene.resumeFailed', { error: message }));
    }
  }, [loadAll, t]);

  const handleTrigger = useCallback(async (id: string) => {
    try {
      await cronTrigger(id);
      void loadAll();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to trigger cron job', err);
      setError(t('tasksScene.triggerFailedMsg', { error: message }));
    }
  }, [loadAll, t]);

  // ---- 操作按钮 ----

  return (
    <div className="tupai-tasks">
      <div className="tupai-tasks__header">
        <h2 className="tupai-tasks__title">{t('tasksScene.schedulerTitle')}</h2>
        <div className="tupai-tasks__header-actions">
          <button
            type="button"
            className="tupai-tasks__btn tupai-tasks__btn--ghost"
            onClick={() => void loadAll()}
            disabled={loading}
          >
            <RefreshCw size={14} />
            <span>{t('actions.refresh')}</span>
          </button>
          <button
            type="button"
            className="tupai-tasks__btn tupai-tasks__btn--primary"
            onClick={handleOpenForm}
          >
            <Play size={14} />
            <span>{t('tasksScene.newJob')}</span>
          </button>
        </div>
      </div>

      {error && (
        <div className="tupai-tasks__error">
          <AlertTriangle size={16} />
          <span>{error}</span>
          <button className="tupai-tasks__btn tupai-tasks__btn--ghost" onClick={() => setError(null)}>
            {t('tasksScene.clear')}
          </button>
        </div>
      )}

      {showForm ? (
        <div className="tupai-tasks__form">
          <div className="tupai-tasks__form-row">
            <label className="tupai-tasks__field">
              <span className="tupai-tasks__field-label">{t('tasksScene.name')}</span>
              <input
                type="text"
                className="tupai-tasks__input"
                value={form.name}
                onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                placeholder={t('tasksScene.nameExample')}
              />
            </label>
            <label className="tupai-tasks__field">
              <span className="tupai-tasks__field-label">{t('tasksScene.schedule')}</span>
              <input
                type="text"
                className="tupai-tasks__input"
                value={form.schedule}
                onChange={(e) => setForm((f) => ({ ...f, schedule: e.target.value }))}
                placeholder="*/5 * * * *"
              />
            </label>
          </div>
          <div className="tupai-tasks__form-actions">
            <button
              type="button"
              className="tupai-tasks__btn tupai-tasks__btn--ghost"
              onClick={handleCancelForm}
            >
              {t('actions.cancel')}
            </button>
            <button
              type="button"
              className="tupai-tasks__btn tupai-tasks__btn--primary"
              onClick={handleSubmitForm}
              disabled={!form.name.trim() || !form.schedule.trim() || creating}
            >
              {creating ? t('status.saving') : t('actions.save')}
            </button>
          </div>
        </div>
      ) : null}

      <div className="tupai-tasks__list">
        {loading && jobs.length === 0 ? (
          <div className="tupai-tasks__empty">{t('status.loading')}</div>
        ) : jobs.length === 0 ? (
          <div className="tupai-tasks__empty">{t('tasksScene.emptyWithHint')}</div>
        ) : (
          jobs.map((job) => (
            <div
              key={job.id}
              className={`tupai-tasks__card${job.enabled ? '' : ' tupai-tasks__card--disabled'}`}
            >
              <div className="tupai-tasks__card-main">
                <div className="tupai-tasks__card-name">{job.name || job.id}</div>
                <div className="tupai-tasks__card-meta">
                  <span className="tupai-tasks__chip">{job.schedule.display}</span>
                  <span className="tupai-tasks__chip">{job.name || job.id}</span>
                  <span className={`tupai-tasks__chip${job.enabled ? ' tupai-tasks__chip--on' : ' tupai-tasks__chip--off'}`}>
                    {job.enabled ? t('tasksScene.enabled') : t('tasksScene.paused')}
                  </span>
                </div>
              </div>
              <div className="tupai-tasks__card-actions">
                <button
                  type="button"
                  className="tupai-tasks__btn tupai-tasks__btn--ghost"
                  onClick={() => void handlePause(job.id)}
                  title={t('actions.pause')}
                >
                  <Pause size={14} />
                  <span>{t('actions.pause')}</span>
                </button>
                {!job.enabled && (
                  <button
                    type="button"
                    className="tupai-tasks__btn tupai-tasks__btn--ghost"
                    onClick={() => void handleResume(job.id)}
                    title={t('actions.resume')}
                  >
                    <Square size={14} />
                    <span>{t('actions.resume')}</span>
                  </button>
                )}
                <button
                  type="button"
                  className="tupai-tasks__btn tupai-tasks__btn--ghost"
                  onClick={() => void handleTrigger(job.id)}
                  title={t('tasksScene.trigger')}
                >
                  <Play size={14} />
                  <span>{t('tasksScene.trigger')}</span>
                </button>
                <button
                  type="button"
                  className="tupai-tasks__btn tupai-tasks__btn--danger"
                  onClick={() => void handleDelete(job.id)}
                  aria-label={t('actions.delete')}
                >
                  <Trash2 size={14} />
                  <span>{t('actions.delete')}</span>
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

export default TupaiTasksScene;
