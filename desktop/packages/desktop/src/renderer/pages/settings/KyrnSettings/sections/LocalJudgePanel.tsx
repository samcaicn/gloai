import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Input, Modal, Progress, Select } from '@arco-design/web-react';
import { useTranslation } from 'react-i18next';
import { kyrnBridge, unwrap } from '@/common/kyrn/bridge';
import { LAYA_GGUF_DEFAULT_ID, LAYA_GGUF_PRESETS } from '@/common/kyrn/layaGguf';
import { LAYA_ONNX_DEFAULT_ID, LAYA_ONNX_DOWNLOAD_MB, LAYA_ONNX_PRESETS } from '@/common/kyrn/layaOnnx';
import {
  LOCAL_JUDGE_DOWNLOAD_MB,
  type LocalJudgeAction,
  type LocalJudgeRuntime,
  type LocalJudgeState,
} from '@/common/kyrn/localJudge';
import { formatNumber } from '@/renderer/services/i18n/format';
import { formatNameList } from '@/renderer/services/i18n/list';
import { openExternalUrl } from '@/renderer/utils/platform';
import MuErrorMessage from '../fields/MuErrorMessage';
import { toMuError, type MuError } from '../fields/muError';
import { StatusDot, type DotState } from '../providers/parts';
import styles from './sections.module.css';

const UV_URL = 'https://docs.astral.sh/uv/getting-started/installation/';

/** One build of the model as the panel offers it: same shape whichever runtime is running. */
type Build = { id: string; labelKey: string; noteKey: string; downloadMb: number };

const buildsOf = (runtime: LocalJudgeRuntime | undefined): readonly Build[] =>
  runtime === 'gguf' ? LAYA_GGUF_PRESETS : LAYA_ONNX_PRESETS;

const defaultBuildOf = (runtime: LocalJudgeRuntime | undefined): string =>
  runtime === 'gguf' ? LAYA_GGUF_DEFAULT_ID : LAYA_ONNX_DEFAULT_ID;

/**
 * Laya on this machine: whether it is installed and running, and what can be done about it. Applies at once, outside
 * the save bar: it is a program, not a setting.
 *
 * On an Apple Silicon Mac (coreml) one click installs it after the person agrees to the download. Elsewhere the app
 * runs it itself, and two runtimes are offered where both work: ONNX (the app's own runtime) and GGUF (llama.cpp, for
 * a machine whose GPU ONNX has no provider for). Either way the model downloads itself in the background, falling
 * back across mirrors automatically, and the panel only shows progress — no source to pick, no commands to run.
 *
 * `guide`: in the first-run guide, which sets Laya up; stopping it is for the settings.
 */
export default function LocalJudgePanel({ guide = false }: { guide?: boolean } = {}) {
  const { t, i18n } = useTranslation();
  const [modal, modalHolder] = Modal.useModal();
  const [state, setState] = useState<LocalJudgeState>();
  const [problem, setProblem] = useState<MuError>();
  // The `custom` GGUF build names a file the person already has; the environment's own naming still wins.
  const [customFile, setCustomFile] = useState<string>('');
  const live = useRef(true);
  const read = useCallback(async (): Promise<void> => {
    try {
      const next = unwrap(await kyrnBridge.localJudgeState.invoke());
      if (live.current) setState(next);
    } catch (error) {
      if (live.current) setProblem(toMuError(error));
    }
  }, []);
  useEffect(() => {
    live.current = true;
    void read();
    return () => {
      live.current = false;
    };
  }, [read]);

  const task = state?.task;
  const busy = task?.phase === 'running';
  // The script moves on by itself: its state is read while it runs.
  useEffect(() => {
    if (!busy) return;
    const timer = setInterval(() => void read(), 1000);
    return () => clearInterval(timer);
  }, [busy, read]);

  /** The download starts by itself in the background: no source to pick, the app falls back across mirrors itself. */
  const autoStarted = useRef(false);
  useEffect(() => {
    if (!state || autoStarted.current) return;
    const own = state.runtime === 'onnx' || state.runtime === 'gguf';
    const custom = state.runtime === 'gguf' && state.model?.preset === 'gguf-custom';
    if (own && !custom && state.support === 'ok' && !state.installed && !state.running && !state.task) {
      autoStarted.current = true;
      void run('setup', false, { preset: state.model?.preset });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state]);

  const run = async (
    action: LocalJudgeAction,
    consent = false,
    source?: { id?: string; base?: string; preset?: string; file?: string }
  ): Promise<void> => {
    setProblem(undefined);
    try {
      const next = unwrap(await kyrnBridge.localJudgeRun.invoke({ action, consent, source }));
      if (live.current) setState(next);
    } catch (error) {
      if (live.current) setProblem(toMuError(error));
    }
  };
  /** Picks the runtime or the build of the model: remembered by the app, and in effect from the next start on. */
  const choose = async (runtime: LocalJudgeRuntime, preset?: string, file?: string): Promise<void> => {
    setProblem(undefined);
    try {
      const next = unwrap(await kyrnBridge.localJudgeRuntime.invoke({ runtime, preset, file }));
      if (live.current) setState(next);
    } catch (error) {
      if (live.current) setProblem(toMuError(error));
    }
  };
  const install = (): void => {
    modal.confirm?.({
      title: t('mu.judges.laya.confirmTitle'),
      content: t('mu.judges.laya.confirmText', { size: formatNumber(LOCAL_JUDGE_DOWNLOAD_MB, i18n.language) }),
      okText: t('mu.judges.laya.confirmOk'),
      cancelText: t('common.cancel'),
      onOk: () => void run('setup', true),
    });
  };
  const download = (): void =>
    void run('setup', false, { preset: state?.model?.preset, file: customFile || undefined });

  if (!state)
    return problem ? (
      <div className={styles.choiceHint} role='alert'>
        <MuErrorMessage error={problem} />
      </div>
    ) : null;

  const onnx = state.runtime === 'onnx';
  const gguf = state.runtime === 'gguf';
  const own = onnx || gguf; // the app runs the model itself, so it knows the folder and the files
  const model = own ? state.model : undefined;
  const builds = buildsOf(state.runtime);
  const build = builds.find((candidate) => candidate.id === model?.preset) ?? builds[0]!;
  const runtimes = state.runtimes ?? (state.runtime ? [state.runtime] : []);
  const dot: DotState = busy ? 'busy' : state.running ? 'ready' : state.installed ? 'off' : 'attention';
  const status = busy
    ? t(onnx && task.action === 'start' ? 'mu.judges.laya.onnx.starting' : `mu.judges.laya.working.${task.action}`)
    : state.running
      ? t('mu.judges.laya.running')
      : state.installed
        ? t(onnx ? 'mu.judges.laya.onnx.stopped' : gguf ? 'mu.judges.laya.gguf.stopped' : 'mu.judges.laya.stopped')
        : state.support === 'platform'
          ? t('mu.judges.laya.platform')
          : state.support === 'runtime'
            ? t('mu.judges.laya.noServer')
            : state.support === 'uv'
              ? t('mu.judges.laya.needsUv')
              : onnx
                ? t('mu.judges.laya.onnx.missing', { size: formatNumber(LAYA_ONNX_DOWNLOAD_MB, i18n.language) })
                : gguf
                  ? t('mu.judges.laya.gguf.missing')
                  : t('mu.judges.laya.missing');
  const failed = task?.phase === 'failed';
  const failure = !failed
    ? ''
    : task.problem
      ? t(`mu.judges.laya.onnx.problem.${task.problem}`, { port: new URL(state.url).port })
      : t(`mu.judges.laya.failed.${task.action}`);
  const getModel = own && state.support === 'ok' && !state.installed && !state.running;
  const customBuild = gguf && build.id === 'gguf-custom';
  const saveCustomFile = (): void => {
    const file = customFile.trim();
    if (file) void choose('gguf', 'gguf-custom', file);
  };

  return (
    <div className={styles.localJudge} data-testid='mu-laya'>
      {modalHolder}
      <div className={styles.localJudgeStatus} data-testid='mu-laya-status'>
        <StatusDot state={dot} label={status} />
        <span>{status}</span>
      </div>
      {runtimes.length > 1 ? (
        <div className={styles.localJudgeSource}>
          <span className={styles.localJudgeSourceLabel}>{t('mu.judges.laya.runtime')}</span>
          <Select
            size='small'
            value={state.runtime}
            disabled={busy}
            onChange={(value) =>
              void choose(String(value) as LocalJudgeRuntime, defaultBuildOf(String(value) as LocalJudgeRuntime))
            }
            style={{ minWidth: 200 }}
            data-testid='mu-laya-runtime'
          >
            {runtimes.map((candidate) => (
              <Select.Option key={candidate} value={candidate}>
                {t(`mu.judges.laya.runtime${candidate.charAt(0).toUpperCase()}${candidate.slice(1)}`)}
              </Select.Option>
            ))}
          </Select>
        </div>
      ) : null}
      {own && builds.length > 1 ? (
        <div className={styles.localJudgeSource}>
          <span className={styles.localJudgeSourceLabel}>{t('mu.judges.laya.build')}</span>
          <Select
            size='small'
            value={build.id}
            disabled={busy}
            onChange={(value) => void choose(state.runtime ?? 'onnx', String(value))}
            style={{ minWidth: 240 }}
            data-testid='mu-laya-build'
          >
            {builds.map((candidate) => (
              <Select.Option key={candidate.id} value={candidate.id}>
                {t(candidate.labelKey)}
              </Select.Option>
            ))}
          </Select>
          <span className={styles.localJudgeBuildNote}>
            {t(build.noteKey, { size: formatNumber(build.downloadMb, i18n.language) })}
          </span>
        </div>
      ) : null}
      {getModel && model ? (
        <div className={styles.localJudgeModel} data-testid='mu-laya-model'>
          <div>{t('mu.judges.laya.onnx.folder', { folder: model.folder })}</div>
          {model.missing.length && model.missing.length < 5 ? (
            <div>{t('mu.judges.laya.onnx.missingFiles', { files: formatNameList(model.missing, i18n.language) })}</div>
          ) : null}
          {model.wrong.length ? (
            <div className={styles.localJudgeProblem}>
              {t('mu.judges.laya.onnx.wrongFiles', { files: formatNameList(model.wrong, i18n.language) })}
            </div>
          ) : null}
          {gguf ? (
            <div className={styles.localJudgeSource}>
              <span className={styles.localJudgeSourceLabel}>{t('mu.judges.laya.gguf.server')}</span>
              <code dir='ltr'>llama-server</code>
            </div>
          ) : null}
          {customBuild ? (
            <div className={styles.localJudgeSource}>
              <span className={styles.localJudgeSourceLabel}>{t('mu.judges.laya.gguf.customFile')}</span>
              <Input
                size='small'
                value={customFile}
                disabled={busy}
                placeholder={t('mu.judges.laya.gguf.customFilePlaceholder')}
                onChange={(value) => setCustomFile(value)}
                onBlur={saveCustomFile}
                onPressEnter={saveCustomFile}
                style={{ minWidth: 300 }}
                data-testid='mu-laya-gguf-file'
              />
            </div>
          ) : null}
          {!customBuild ? (
            <div className={styles.localJudgeSource}>
              <span className={styles.localJudgeSourceLabel}>{t('mu.judges.laya.source')}</span>
              <span>{t('mu.judges.laya.sourceAuto')}</span>
            </div>
          ) : null}
          {task?.action === 'setup' && task.progress ? (
            <div className={styles.localJudgeProgress} data-testid='mu-laya-progress'>
              <Progress
                percent={Math.round(((task.progress.index + task.progress.percent) / task.progress.total) * 100)}
                size='small'
              />
              <span className={styles.localJudgeProgressText}>
                {t('mu.judges.laya.onnx.downloading', {
                  file: task.progress.file,
                  index: task.progress.index + 1,
                  total: task.progress.total,
                })}
              </span>
            </div>
          ) : null}
        </div>
      ) : null}
      <div className={styles.localJudgeActions}>
        {!state.installed && state.support === 'ok' && !own ? (
          <Button
            size='small'
            type='primary'
            loading={busy}
            disabled={busy}
            data-testid='mu-laya-install'
            onClick={install}
          >
            {t('mu.judges.laya.install')}
          </Button>
        ) : null}
        {getModel ? (
          <>
            <Button
              size='small'
              type='primary'
              loading={busy}
              disabled={busy || (customBuild && !customFile.trim())}
              data-testid='mu-laya-download'
              onClick={download}
            >
              {t('mu.judges.laya.onnx.download')}
            </Button>
            <Button size='small' data-testid='mu-laya-locate' onClick={() => void run('locate')}>
              {t('mu.judges.laya.onnx.openFolder')}
            </Button>
          </>
        ) : null}
        {!state.installed && state.support === 'uv' ? (
          <Button size='small' data-testid='mu-laya-uv' onClick={() => void openExternalUrl(UV_URL)}>
            {t('mu.judges.laya.uvLink')}
          </Button>
        ) : null}
        {state.installed && !state.running ? (
          <Button
            size='small'
            type='primary'
            loading={busy}
            disabled={busy}
            data-testid='mu-laya-start'
            onClick={() => void run('start')}
          >
            {t('mu.judges.laya.start')}
          </Button>
        ) : null}
        {!guide && (state.running || (own && busy && (task.action === 'start' || task.action === 'setup'))) ? (
          <Button
            size='small'
            loading={busy && task.action === 'stop'}
            disabled={busy && task.action === 'stop'}
            data-testid='mu-laya-stop'
            onClick={() => void run('stop')}
          >
            {t('mu.judges.laya.stop')}
          </Button>
        ) : null}
      </div>
      {failed ? (
        <div className={styles.localJudgeProblem} role='alert' data-testid='mu-laya-failed'>
          {failure}
        </div>
      ) : null}
      {problem ? (
        <div className={styles.localJudgeProblem} role='alert'>
          <MuErrorMessage error={problem} />
        </div>
      ) : null}
    </div>
  );
}
