/**
 * TasksScene — 定时任务管理界面（本地调度）。
 *
 * 完全本地化的定时任务管理：任务 + 执行历史落盘到
 * `<app_data>/tupai/cron/`，应用进程内自管调度器（30s tick）。
 * 无需远端 Dashboard / Gateway，离线也能用。
 *
 * 支持：
 *   1. 任务列表（名称 / 提示词 / 调度 / 状态 / 下次 / 上次 / 累计统计）
 *   2. 新建任务（name / prompt / schedule cron 表达式 / deliver 投递目标）
 *   3. 启用 / 暂停（开关）
 *   4. 立即触发（带模型选择器）
 *   5. 删除
 *   6. 查看执行历史（侧滑抽屉，输出/错误/耗时/触发方式）
 *   7. 清空执行历史
 *
 * 后端命令（hermes::cron_local::*）：
 *   cron_local_list / create / pause / resume / trigger / delete /
 *   cron_local_get_runs / cron_local_clear_runs
 *
 * 跨端：macOS / Windows 完全一致，调度在 tokio runtime 内运行，
 * 应用未运行时任务不会执行（轻量级自管方案）。
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Clock,
  RefreshCw,
  Plus,
  Play,
  Trash2,
  AlertTriangle,
  CheckCircle2,
  PauseCircle,
  X,
  History,
  FileText,
  Loader2,
  Sparkles,
  ChevronRight,
} from 'lucide-react';
import {
  cronLocalList,
  cronLocalCreate,
  cronLocalPause,
  cronLocalResume,
  cronLocalTrigger,
  cronLocalDelete,
  cronLocalGetRuns,
  cronLocalClearRuns,
  cronLocalSetToken,
  readDeviceToken,
  type CronLocalJob,
  type CreateCronLocalJobInput,
  type CronRun,
} from '@/infrastructure/api/tupai';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { createLogger } from '@/shared/utils/logger';
import './TasksScene.scss';

const log = createLogger('TasksScene');

const NEW_JOB_ID = '__new__';
const RUN_REFRESH_INTERVAL_MS = 3000;
const JOB_REFRESH_INTERVAL_MS = 8000;

const CRON_PRESETS: Array<{ labelKey: string; expr: string }> = [
  { labelKey: 'tasksScene.cronPresets.everyMinute', expr: '* * * * *' },
  { labelKey: 'tasksScene.cronPresets.every5Minutes', expr: '*/5 * * * *' },
  { labelKey: 'tasksScene.cronPresets.hourly', expr: '0 * * * *' },
  { labelKey: 'tasksScene.cronPresets.daily8', expr: '0 8 * * *' },
  { labelKey: 'tasksScene.cronPresets.daily0', expr: '0 0 * * *' },
  { labelKey: 'tasksScene.cronPresets.weeklyMon', expr: '0 9 * * 1' },
  { labelKey: 'tasksScene.cronPresets.monthly1st', expr: '0 0 1 * *' },
];

interface JobDraft {
  name: string;
  prompt: string;
  schedule: string;
  deliver: string;
}

interface DraftErrors {
  name: boolean;
  prompt: boolean;
  schedule: boolean;
}

function createEmptyDraft(): JobDraft {
  return { name: '', prompt: '', schedule: '0 8 * * *', deliver: '' };
}

function formatTime(iso: string | null, localeStr: string): string {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString(localeStr === 'zh' ? 'zh-CN' : 'en-US', {
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

function formatFullTime(iso: string | null, localeStr: string): string {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString(localeStr === 'zh' ? 'zh-CN' : 'en-US', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
  } catch {
    return iso;
  }
}

function formatDuration(ms: number | null, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (ms == null) return '-';
  if (ms < 1000) return `${ms}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  const rest = (s - m * 60).toFixed(0);
  return t('tasksScene.durationFormat', { m, s: rest });
}

const TasksScene: React.FC = () => {
  const { t: ti18n, currentLanguage } = useI18n('common');
  const locale = currentLanguage === 'zh-CN' ? 'zh' : 'en';

  const [jobs, setJobs] = useState<CronLocalJob[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<JobDraft>(() => createEmptyDraft());
  const [errors, setErrors] = useState<DraftErrors>({ name: false, prompt: false, schedule: false });
  const [hint, setHint] = useState<{ type: 'success' | 'error'; msg: string } | null>(null);
  const [saving, setSaving] = useState(false);

  // 执行历史抽屉
  const [historyJob, setHistoryJob] = useState<CronLocalJob | null>(null);
  const [historyRuns, setHistoryRuns] = useState<CronRun[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyExpanded, setHistoryExpanded] = useState<string | null>(null);
  const [historyPolling, setHistoryPolling] = useState(false);
  const historyTimerRef = useRef<number | null>(null);

  const t = useMemo(() => ({
    title: ti18n('tasksScene.title'),
    subtitle: ti18n('tasksScene.subtitle'),
    newJob: ti18n('tasksScene.newJob'),
    refresh: ti18n('actions.refresh'),
    name: ti18n('tasksScene.name'),
    namePlaceholder: ti18n('tasksScene.namePlaceholder'),
    prompt: ti18n('tasksScene.prompt'),
    promptPlaceholder: ti18n('tasksScene.promptPlaceholder'),
    schedule: ti18n('tasksScene.schedule'),
    scheduleHint: ti18n('tasksScene.scheduleHint'),
    deliver: ti18n('tasksScene.deliver'),
    deliverPlaceholder: ti18n('tasksScene.deliverPlaceholder'),
    create: ti18n('actions.create'),
    cancel: ti18n('actions.cancel'),
    trigger: ti18n('tasksScene.trigger'),
    delete: ti18n('actions.delete'),
    enabled: ti18n('tasksScene.enabled'),
    paused: ti18n('tasksScene.paused'),
    running: ti18n('tasksScene.running'),
    nextRun: ti18n('tasksScene.nextRun'),
    lastRun: ti18n('tasksScene.lastRun'),
    empty: ti18n('tasksScene.empty'),
    emptyHint: ti18n('tasksScene.emptyHint'),
    loading: ti18n('status.loading') + '…',
    confirmDelete: ti18n('tasksScene.confirmDelete'),
    created: ti18n('tasksScene.created'),
    deleted: ti18n('tasksScene.deleted'),
    triggered: ti18n('tasksScene.triggered'),
    toggled: ti18n('tasksScene.toggled'),
    loadFailed: ti18n('tasksScene.loadFailed'),
    saveFailed: ti18n('tasksScene.saveFailed'),
    triggerFailed: ti18n('tasksScene.triggerFailed'),
    history: ti18n('tasksScene.history'),
    runHistoryHint: ti18n('tasksScene.runHistoryHint'),
    noRuns: ti18n('tasksScene.noRuns'),
    clearHistory: ti18n('tasksScene.clearHistory'),
    confirmClear: ti18n('tasksScene.confirmClear'),
    cleared: ti18n('tasksScene.cleared'),
    totalRuns: ti18n('tasksScene.totalRuns'),
    successRuns: ti18n('tasksScene.successRuns'),
    failedRuns: ti18n('tasksScene.failedRuns'),
    triggerManual: ti18n('tasksScene.triggerManual'),
    triggerSchedule: ti18n('tasksScene.triggerSchedule'),
    output: ti18n('tasksScene.output'),
    error: ti18n('tasksScene.error'),
    duration: ti18n('tasksScene.duration'),
  }), [ti18n]);

  const showHint = useCallback((type: 'success' | 'error', msg: string) => {
    setHint({ type, msg });
    window.setTimeout(() => setHint(null), 2800);
  }, []);

  // 加载任务列表
  const loadJobs = useCallback(async () => {
    setLoading(true);
    try {
      const list = await cronLocalList();
      setJobs(Array.isArray(list) ? list : []);
    } catch (e) {
      log.error('Failed to load cron jobs', e);
      showHint('error', `${t.loadFailed}: ${e instanceof Error ? e.message : String(e)}`);
      setJobs([]);
    } finally {
      setLoading(false);
    }
  }, [showHint, t.loadFailed]);

  // 把设备 token 透传给后端定时任务调度器（经 MCP 调 LLM 的鉴权）。
  // 后端仅存进程内存、非持久化，故进入面板 / 窗口聚焦 / 可见性
  // 变化时都刷新一次，保证后端拿到最新的 device_token。
  const pushDeviceToken = useCallback(async () => {
    try {
      await cronLocalSetToken(readDeviceToken());
    } catch (e) {
      log.error('Failed to push device token to cron scheduler', e);
    }
  }, []);

  useEffect(() => {
    void loadJobs();
    void pushDeviceToken();
    const onFocus = () => void pushDeviceToken();
    const onVisible = () => { if (!document.hidden) void pushDeviceToken(); };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [loadJobs, pushDeviceToken]);

  // 任务列表定时刷新（捕捉后台调度器变更 next_run_at / last_run_at）
  useEffect(() => {
    const id = window.setInterval(() => {
      if (document.hidden) return;
      void loadJobs();
    }, JOB_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [loadJobs]);

  const sortedJobs = useMemo(() => {
    return [...jobs].sort((a, b) => {
      if (a.enabled !== b.enabled) return a.enabled ? -1 : 1;
      return (b.nextRunAt || '').localeCompare(a.nextRunAt || '');
    });
  }, [jobs]);

  // ===== 任务操作 =====

  const handleCreate = useCallback(async () => {
    const nextErrors: DraftErrors = {
      name: !draft.name.trim(),
      prompt: !draft.prompt.trim(),
      schedule: !draft.schedule.trim(),
    };
    setErrors(nextErrors);
    if (nextErrors.name || nextErrors.prompt || nextErrors.schedule) return;

    setSaving(true);
    const input: CreateCronLocalJobInput = {
      name: draft.name.trim() || null,
      prompt: draft.prompt.trim(),
      schedule: draft.schedule.trim(),
      deliver: draft.deliver.trim() || null,
      token: readDeviceToken(),
    };
    try {
      await cronLocalCreate(input);
      setExpandedId(null);
      setDraft(createEmptyDraft());
      showHint('success', t.created);
      await loadJobs();
    } catch (e) {
      log.error('Failed to create cron job', e);
      showHint('error', `${t.saveFailed}: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setSaving(false);
    }
  }, [draft, loadJobs, showHint, t.created, t.saveFailed]);

  const handleToggle = useCallback(async (job: CronLocalJob) => {
    setBusyId(job.id);
    try {
      if (job.enabled) {
        await cronLocalPause(job.id);
      } else {
        await cronLocalResume(job.id);
      }
      showHint('success', t.toggled);
      await loadJobs();
    } catch (e) {
      log.error(`Failed to toggle cron job ${job.id}`, e);
      showHint('error', e instanceof Error ? e.message : String(e));
    } finally {
      setBusyId(null);
    }
  }, [loadJobs, showHint, t.toggled]);

  const handleTrigger = useCallback(async (job: CronLocalJob) => {
    setBusyId(job.id);
    try {
      await cronLocalTrigger({ id: job.id, token: readDeviceToken() });
      showHint('success', t.triggered);
      await loadJobs();
      // 如果历史抽屉是打开的, 立即刷新一次
      if (historyJob?.id === job.id) {
        try {
          const runs = await cronLocalGetRuns(job.id, 200);
          setHistoryRuns(Array.isArray(runs) ? runs : []);
        } catch { /* 静默 */ }
      }
    } catch (e) {
      log.error(`Failed to trigger cron job ${job.id}`, e);
      const msg = e instanceof Error ? e.message : String(e);
      showHint('error', `${t.triggerFailed}: ${msg}`);
    } finally {
      setBusyId(null);
    }
  }, [historyJob, loadJobs, showHint, t.triggerFailed, t.triggered]);

  const handleDelete = useCallback(async (job: CronLocalJob) => {
    if (!window.confirm(t.confirmDelete)) return;
    setBusyId(job.id);
    try {
      await cronLocalDelete(job.id);
      // 如果抽屉开着, 关闭它
      if (historyJob?.id === job.id) {
        setHistoryJob(null);
        setHistoryRuns([]);
      }
      showHint('success', t.deleted);
      await loadJobs();
    } catch (e) {
      log.error(`Failed to delete cron job ${job.id}`, e);
      showHint('error', e instanceof Error ? e.message : String(e));
    } finally {
      setBusyId(null);
    }
  }, [historyJob, loadJobs, showHint, t.confirmDelete, t.deleted]);

  // ===== 执行历史抽屉 =====

  const openHistory = useCallback(async (job: CronLocalJob) => {
    setHistoryJob(job);
    setHistoryRuns([]);
    setHistoryExpanded(null);
    setHistoryLoading(true);
    try {
      const runs = await cronLocalGetRuns(job.id, 200);
      setHistoryRuns(Array.isArray(runs) ? runs : []);
    } catch (e) {
      log.error(`Failed to load runs for ${job.id}`, e);
      showHint('error', e instanceof Error ? e.message : String(e));
    } finally {
      setHistoryLoading(false);
    }
  }, [showHint]);

  const closeHistory = useCallback(() => {
    setHistoryJob(null);
    setHistoryRuns([]);
    setHistoryExpanded(null);
    setHistoryPolling(false);
    if (historyTimerRef.current != null) {
      window.clearInterval(historyTimerRef.current);
      historyTimerRef.current = null;
    }
  }, []);

  // 抽屉打开时启动轮询, 3s 一次刷新（手动 trigger 后能立即看到新记录）
  useEffect(() => {
    if (!historyJob) {
      setHistoryPolling(false);
      if (historyTimerRef.current != null) {
        window.clearInterval(historyTimerRef.current);
        historyTimerRef.current = null;
      }
      return;
    }
    setHistoryPolling(true);
    const id = window.setInterval(async () => {
      if (document.hidden) return;
      try {
        const runs = await cronLocalGetRuns(historyJob.id, 200);
        setHistoryRuns(Array.isArray(runs) ? runs : []);
        // 顺便刷新 job 列表, 更新 last_run_at / next_run_at
        void loadJobs();
      } catch { /* 静默重试 */ }
    }, RUN_REFRESH_INTERVAL_MS);
    historyTimerRef.current = id;
    return () => {
      window.clearInterval(id);
      if (historyTimerRef.current === id) historyTimerRef.current = null;
    };
  }, [historyJob, loadJobs]);

  const handleClearRuns = useCallback(async () => {
    if (!historyJob) return;
    if (!window.confirm(t.confirmClear)) return;
    try {
      await cronLocalClearRuns(historyJob.id);
      setHistoryRuns([]);
      showHint('success', t.cleared);
    } catch (e) {
      log.error(`Failed to clear runs for ${historyJob.id}`, e);
      showHint('error', e instanceof Error ? e.message : String(e));
    }
  }, [historyJob, showHint, t.cleared, t.confirmClear]);

  // ===== 渲染 =====

  const handleStartNew = useCallback(() => {
    setExpandedId(NEW_JOB_ID);
    setDraft(createEmptyDraft());
    setErrors({ name: false, prompt: false, schedule: false });
  }, []);

  const handleCancelNew = useCallback(() => {
    setExpandedId(null);
    setDraft(createEmptyDraft());
    setErrors({ name: false, prompt: false, schedule: false });
  }, []);

  return (
    <div className="tasks-scene">
      {/* 标题栏 */}
      <div className="tasks-scene__header">
        <div className="tasks-scene__title-wrap">
          <Clock size={16} />
          <h2 className="tasks-scene__title">{t.title}</h2>
          <span className="tasks-scene__subtitle">{t.subtitle}</span>
        </div>
        <div className="tasks-scene__header-actions">
          <button
            type="button"
            className="tasks-scene__btn tasks-scene__btn--ghost"
            onClick={() => { void loadJobs(); void pushDeviceToken(); }}
            disabled={loading}
          >
            <RefreshCw size={13} className={loading ? 'tasks-scene__spin' : ''} />
            <span>{t.refresh}</span>
          </button>
          <button
            type="button"
            className="tasks-scene__btn tasks-scene__btn--primary"
            onClick={handleStartNew}
            disabled={expandedId === NEW_JOB_ID}
          >
            <Plus size={13} />
            <span>{t.newJob}</span>
          </button>
        </div>
      </div>

      {/* 内联提示 */}
      {hint && (
        <div className={`tasks-scene__hint tasks-scene__hint--${hint.type}`}>
          {hint.type === 'success'
            ? <CheckCircle2 size={13} />
            : <AlertTriangle size={13} />}
          <span>{hint.msg}</span>
        </div>
      )}

      {/* 新建表单 */}
      {expandedId === NEW_JOB_ID && (
        <section className="tasks-scene__editor">
          <div className="tasks-scene__editor-head">
            <span className="tasks-scene__editor-title">{t.newJob}</span>
            <button
              type="button"
              className="tasks-scene__icon-btn"
              onClick={handleCancelNew}
              aria-label={t.cancel}
            >
              <X size={14} />
            </button>
          </div>
          <div className="tasks-scene__form">
            <div className="tasks-scene__field">
              <label className="tasks-scene__label">{t.name}</label>
              <input
                className={`tasks-scene__input${errors.name ? ' is-error' : ''}`}
                value={draft.name}
                onChange={e => {
                  setErrors(prev => ({ ...prev, name: false }));
                  setDraft(d => ({ ...d, name: e.target.value }));
                }}
                placeholder={t.namePlaceholder}
              />
            </div>

            <div className="tasks-scene__field">
              <label className="tasks-scene__label">{t.schedule}</label>
              <input
                className={`tasks-scene__input tasks-scene__input--mono${errors.schedule ? ' is-error' : ''}`}
                value={draft.schedule}
                onChange={e => {
                  setErrors(prev => ({ ...prev, schedule: false }));
                  setDraft(d => ({ ...d, schedule: e.target.value }));
                }}
                placeholder="0 8 * * *"
              />
              <div className="tasks-scene__presets">
                {CRON_PRESETS.map(p => (
                  <button
                    key={p.expr}
                    type="button"
                    className="tasks-scene__preset-chip"
                    onClick={() => setDraft(d => ({ ...d, schedule: p.expr }))}
                    title={p.expr}
                  >
                    {ti18n(p.labelKey)}
                  </button>
                ))}
              </div>
              <span className="tasks-scene__field-hint">{t.scheduleHint}</span>
            </div>

            <div className="tasks-scene__field">
              <label className="tasks-scene__label">{t.deliver}</label>
              <input
                className="tasks-scene__input"
                value={draft.deliver}
                onChange={e => setDraft(d => ({ ...d, deliver: e.target.value }))}
                placeholder={t.deliverPlaceholder}
              />
            </div>

            <div className="tasks-scene__field tasks-scene__field--prompt">
              <label className="tasks-scene__label">{t.prompt}</label>
              <textarea
                className={`tasks-scene__textarea${errors.prompt ? ' is-error' : ''}`}
                value={draft.prompt}
                onChange={e => {
                  setErrors(prev => ({ ...prev, prompt: false }));
                  setDraft(d => ({ ...d, prompt: e.target.value }));
                }}
                placeholder={t.promptPlaceholder}
                rows={4}
              />
            </div>

            <div className="tasks-scene__form-actions">
              <button
                type="button"
                className="tasks-scene__btn tasks-scene__btn--ghost"
                onClick={handleCancelNew}
                disabled={saving}
              >
                {t.cancel}
              </button>
              <button
                type="button"
                className="tasks-scene__btn tasks-scene__btn--primary"
                onClick={() => void handleCreate()}
                disabled={saving}
              >
                {saving ? '…' : t.create}
              </button>
            </div>
          </div>
        </section>
      )}

      {/* 任务列表 */}
      <div className="tasks-scene__list">
        {loading && jobs.length === 0 ? (
          <div className="tasks-scene__empty">
            <RefreshCw size={16} className="tasks-scene__spin" />
            <span>{t.loading}</span>
          </div>
        ) : sortedJobs.length === 0 && expandedId !== NEW_JOB_ID ? (
          <div className="tasks-scene__empty">
            <Clock size={20} />
            <span className="tasks-scene__empty-title">{t.empty}</span>
            <span className="tasks-scene__empty-hint">{t.emptyHint}</span>
          </div>
        ) : (
          sortedJobs.map(job => {
            const isBusy = busyId === job.id;
            const isRunning = job.state === 'running';
            const hasError = !!job.lastError && !isRunning;
            return (
              <div
                key={job.id}
                className={`tasks-scene__item${job.enabled ? '' : ' is-paused'}${isRunning ? ' is-running' : ''}${historyJob?.id === job.id ? ' is-active' : ''}`}
              >
                <div
                  className="tasks-scene__item-main"
                  onClick={() => void openHistory(job)}
                  role="button"
                  tabIndex={0}
                >
                  <div className="tasks-scene__item-top">
                    <span className="tasks-scene__item-name" title={job.name || job.id}>
                      {job.name || job.id}
                    </span>
                    <span className={`tasks-scene__state${isRunning ? ' is-run' : job.enabled ? ' is-on' : ''}`}>
                      {isRunning
                        ? <><Loader2 size={11} className="tasks-scene__spin" />{t.running}</>
                        : job.enabled
                          ? <><CheckCircle2 size={11} />{t.enabled}</>
                          : <><PauseCircle size={11} />{t.paused}</>}
                    </span>
                  </div>
                  <div className="tasks-scene__item-prompt" title={job.prompt}>
                    {job.prompt}
                  </div>
                  <div className="tasks-scene__item-meta">
                    <span className="tasks-scene__chip tasks-scene__chip--sched" title={job.schedule?.expr || ''}>
                      <Clock size={11} />
                      {job.scheduleDisplay || job.schedule?.display || job.schedule?.expr || '-'}
                    </span>
                    {job.nextRunAt && job.enabled && (
                      <span className="tasks-scene__chip">
                        {t.nextRun}: {formatTime(job.nextRunAt, locale)}
                      </span>
                    )}
                    {job.lastRunAt && (
                      <span className="tasks-scene__chip tasks-scene__chip--dim">
                        {t.lastRun}: {formatTime(job.lastRunAt, locale)}
                      </span>
                    )}
                    {job.totalRuns > 0 && (
                      <span className="tasks-scene__chip tasks-scene__chip--dim" title={`${t.totalRuns}: ${job.totalRuns}`}>
                        {t.totalRuns} {job.totalRuns}
                        {job.successfulRuns > 0 && <span className="tasks-scene__chip-ok"> · {t.successRuns} {job.successfulRuns}</span>}
                        {job.failedRuns > 0 && <span className="tasks-scene__chip-err"> · {t.failedRuns} {job.failedRuns}</span>}
                      </span>
                    )}
                    {job.deliver && (
                      <span className="tasks-scene__chip tasks-scene__chip--dim" title={job.deliver}>
                        → {job.deliver}
                      </span>
                    )}
                    <span className="tasks-scene__chip tasks-scene__chip--dim tasks-scene__chip--link">
                      <History size={11} />
                      {t.history}
                      <ChevronRight size={11} />
                    </span>
                  </div>
                  {hasError && (
                    <div className="tasks-scene__item-error" title={job.lastError || ''}>
                      <AlertTriangle size={11} />
                      <span>{job.lastError}</span>
                    </div>
                  )}
                </div>
                <div className="tasks-scene__item-actions">
                  <button
                    type="button"
                    className="tasks-scene__icon-btn"
                    onClick={e => { e.stopPropagation(); void handleToggle(job); }}
                    disabled={isBusy}
                    title={job.enabled ? t.paused : t.enabled}
                  >
                    {job.enabled ? <PauseCircle size={14} /> : <CheckCircle2 size={14} />}
                  </button>
                  <button
                    type="button"
                    className="tasks-scene__icon-btn"
                    onClick={e => { e.stopPropagation(); void handleTrigger(job); }}
                    disabled={isBusy}
                    title={t.trigger}
                  >
                    {isRunning ? <Loader2 size={14} className="tasks-scene__spin" /> : <Play size={14} />}
                  </button>
                  <button
                    type="button"
                    className="tasks-scene__icon-btn tasks-scene__icon-btn--danger"
                    onClick={e => { e.stopPropagation(); void handleDelete(job); }}
                    disabled={isBusy}
                    title={t.delete}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            );
          })
        )}
      </div>

      {/* 执行历史抽屉 */}
      {historyJob && (
        <>
          <div className="tasks-scene__drawer-mask" onClick={closeHistory} />
          <aside className="tasks-scene__drawer" role="dialog" aria-label={t.history}>
            <header className="tasks-scene__drawer-head">
              <div className="tasks-scene__drawer-title-wrap">
                <History size={14} />
                <h3 className="tasks-scene__drawer-title">{historyJob.name || historyJob.id}</h3>
                {historyPolling && (
                  <span className="tasks-scene__drawer-pulse" title="live" />
                )}
              </div>
              <div className="tasks-scene__drawer-actions">
                <button
                  type="button"
                  className="tasks-scene__icon-btn"
                  onClick={() => void handleClearRuns()}
                  disabled={historyRuns.length === 0}
                  title={t.clearHistory}
                >
                  <Trash2 size={13} />
                </button>
                <button
                  type="button"
                  className="tasks-scene__icon-btn"
                  onClick={closeHistory}
                  aria-label={t.cancel}
                >
                  <X size={14} />
                </button>
              </div>
            </header>

            <div className="tasks-scene__drawer-sub">
              <span className="tasks-scene__chip tasks-scene__chip--sched">
                <Clock size={11} />
                {historyJob.schedule?.expr || '-'}
              </span>
              <span className="tasks-scene__drawer-hint">
                {t.runHistoryHint} · {historyRuns.length}
              </span>
            </div>

            <div className="tasks-scene__drawer-body">
              {historyLoading && historyRuns.length === 0 ? (
                <div className="tasks-scene__empty tasks-scene__empty--inline">
                  <RefreshCw size={14} className="tasks-scene__spin" />
                  <span>{t.loading}</span>
                </div>
              ) : historyRuns.length === 0 ? (
                <div className="tasks-scene__empty tasks-scene__empty--inline">
                  <Sparkles size={14} />
                  <span className="tasks-scene__empty-title">{t.noRuns}</span>
                </div>
              ) : (
                <ul className="tasks-scene__run-list">
                  {historyRuns.slice().reverse().map(run => {
                    const isOpen = historyExpanded === run.id;
                    return (
                      <li
                        key={run.id}
                        className={`tasks-scene__run${isOpen ? ' is-open' : ''}`}
                      >
                        <button
                          type="button"
                          className="tasks-scene__run-head"
                          onClick={() => setHistoryExpanded(isOpen ? null : run.id)}
                        >
                          <span className={`tasks-scene__run-state tasks-scene__run-state--${run.state}`}>
                            {run.state === 'completed' ? <CheckCircle2 size={11} />
                              : run.state === 'error' ? <AlertTriangle size={11} />
                              : <Loader2 size={11} className="tasks-scene__spin" />}
                          </span>
                          <span className="tasks-scene__run-time">
                            {formatFullTime(run.startedAt, locale)}
                          </span>
                          <span className="tasks-scene__run-trigger">
                            {run.trigger === 'manual' ? t.triggerManual : t.triggerSchedule}
                          </span>
                          <span className="tasks-scene__run-dur">
                            {formatDuration(run.durationMs, ti18n)}
                          </span>
                          <ChevronRight size={12} className={`tasks-scene__run-chevron${isOpen ? ' is-open' : ''}`} />
                        </button>
                        {isOpen && (
                          <div className="tasks-scene__run-body">
                            {run.output != null && (
                              <div className="tasks-scene__run-section">
                                <div className="tasks-scene__run-section-label">
                                  <FileText size={11} />
                                  <span>{t.output}</span>
                                </div>
                                <pre className="tasks-scene__run-output">{run.output}</pre>
                              </div>
                            )}
                            {run.error && (
                              <div className="tasks-scene__run-section tasks-scene__run-section--err">
                                <div className="tasks-scene__run-section-label">
                                  <AlertTriangle size={11} />
                                  <span>{t.error}</span>
                                </div>
                                <pre className="tasks-scene__run-output">{run.error}</pre>
                              </div>
                            )}
                            {run.output == null && !run.error && run.state === 'running' && (
                              <div className="tasks-scene__run-section tasks-scene__run-section--pending">
                                <Loader2 size={11} className="tasks-scene__spin" />
                                <span>{t.running}…</span>
                              </div>
                            )}
                            {run.output == null && !run.error && run.state === 'completed' && (
                              <div className="tasks-scene__run-section tasks-scene__run-section--pending">
                                <span>(empty output)</span>
                              </div>
                            )}
                          </div>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </aside>
        </>
      )}
    </div>
  );
};

export default TasksScene;
