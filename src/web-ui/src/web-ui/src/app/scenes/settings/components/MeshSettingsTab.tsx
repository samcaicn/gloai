/**
 * MeshSettingsTab — 安全 P2P mesh 组网配置 + 操作面板（设置入口独立菜单）。
 *
 * 【自动化铁律】mesh 身份（tenant_id / device_fingerprint）由后端从既有设备注册
 * 状态自动派生（tenant.json + hardware_id 经 SHA-256），前端零身份输入。
 * 前端只负责：
 *   1. join_code（创建时自动生成随机短码，可改）
 *   2. 技能暴露（从 navSkillsStore 自动探测，默认全选，可勾选/取消）
 *   3. ticket（加入他人 mesh 时粘贴）
 *
 * 面板含配置 + 操作（P0 全放一处）：
 *   状态 / 技能暴露 / 创建 mesh / 加入 mesh / 对端列表 / 提交需求（仅协调者）
 */

import { useCallback, useEffect, useMemo, useState, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input } from '@/component-library';
import {
  ConfigPageContent,
  ConfigPageHeader,
  ConfigPageLayout,
  ConfigPageRow,
  ConfigPageSection,
} from '@/infrastructure/config/components/common';
import {
  meshCreate,
  meshJoin,
  meshLeave,
  meshStatus,
  meshSubmitRequirement,
  meshListPeers,
  type MeshStatus,
  type MeshPeer,
} from '@/infrastructure/api/tupai';
import { tenantGet } from '@/infrastructure/api/tupai';
import { notificationService } from '@/shared/notification-system';
import { confirmDanger } from '@/component-library/components/ConfirmDialog/confirmService';
import { useNavSkillsStore } from '@/app/components/NavPanel/sections/skills/navSkillsStore';
import { createLogger } from '@/shared/utils/logger';
import './MeshSettingsTab.scss';

const log = createLogger('meshSettings');

/** 生成 8 位随机 join_code（base36，创建 mesh 时默认填入，用户可改）。 */
function randomJoinCode(): string {
  return Math.random().toString(36).slice(2, 10);
}

function readErrorMessage(err: unknown): string {
  if (err && typeof err === 'object' && 'message' in err) {
    const m = (err as { message?: unknown }).message;
    if (typeof m === 'string' && m) return m;
  }
  if (typeof err === 'string' && err) return err;
  return 'Operation failed';
}

const MeshSettingsTab: FC = () => {
  const { t } = useTranslation('common');

  // ── 技能列表（自动探测）──
  const displaySkills = useNavSkillsStore((s) => s.displaySkills);
  const loadSkills = useNavSkillsStore((s) => s.loadSkills);

  // ── 状态 ──
  const [status, setStatus] = useState<MeshStatus | null>(null);
  const [peers, setPeers] = useState<MeshPeer[]>([]);
  const [tenantId, setTenantId] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  // ── 技能暴露（create / join 共用）──
  const [selectedSkills, setSelectedSkills] = useState<Set<string>>(new Set());
  // 标记用户是否已交互过技能勾选; 交互后不再因 allSkillIds 变化而自动全选
  const [skillsTouched, setSkillsTouched] = useState(false);

  // ── 创建 mesh ──
  const [joinCode, setJoinCode] = useState<string>(() => randomJoinCode());
  const [creating, setCreating] = useState(false);
  const [createdTicket, setCreatedTicket] = useState<string>('');

  // ── 加入 mesh ──
  const [joinTicket, setJoinTicket] = useState<string>('');
  const [joining, setJoining] = useState(false);

  // ── 提交需求（仅协调者）──
  const [requirementText, setRequirementText] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);

  const allSkillIds = useMemo(
    () =>
      displaySkills
        .map((s) => s.skill_id || s.id || '')
        .filter(Boolean),
    [displaySkills],
  );

  // 技能列表加载后，默认全选（自动化：首次进入即默认暴露全部可用技能）。
  // skillsTouched 后不再覆盖 — 防止用户清空全部技能后, allSkillIds 变化触发重新全选。
  useEffect(() => {
    if (allSkillIds.length === 0) return;
    if (skillsTouched) return; // 用户已交互, 不再自动全选
    setSelectedSkills(new Set(allSkillIds));
  }, [allSkillIds, skillsTouched]);

  const refreshStatus = useCallback(async () => {
    setLoading(true);
    setErrorMsg('');
    try {
      const [st, ps] = await Promise.all([meshStatus(), meshListPeers()]);
      // 非 Tauri 运行时（web 预览 / jsdom）invoke 静默返回 undefined，需归一化避免
      // setPeers(undefined) → 后续 peers.length 抛错。
      setStatus(st ?? null);
      setPeers(ps ?? []);
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
    } finally {
      setLoading(false);
    }
  }, []);

  /** 仅刷新对端列表（create/join 后用，不触发 loading 闪烁、不重拉 status）。 */
  const refreshPeers = useCallback(async () => {
    try {
      const ps = await meshListPeers();
      setPeers(ps ?? []);
    } catch (e) {
      log.warn('meshListPeers failed', { error: e });
    }
  }, []);

  // 挂载：拉 mesh 状态 + 对端；拉技能列表（空时）；拉 tenant_id（身份透明展示）。
  // 加 cancelled 守卫: 组件提前卸载时跳过 setState, 避免异步回调在已卸载组件上更新状态。
  useEffect(() => {
    let cancelled = false;
    void refreshStatus().then(() => { if (cancelled) return; });
    if (displaySkills.length === 0) {
      void loadSkills()
        .catch((e) => log.warn('loadSkills failed', { error: e }))
        .then(() => { if (cancelled) return; });
    }
    tenantGet()
      .then((info) => { if (!cancelled) setTenantId(info?.id || ''); })
      .catch((e) => log.warn('tenantGet failed', { error: e }));
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const isCoordinator = status?.role === 'coordinator';

  // ── 技能勾选 ──
  const toggleSkill = (id: string) => {
    setSkillsTouched(true); // 用户交互, 标记后不再自动全选
    setSelectedSkills((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const selectAllSkills = () => { setSkillsTouched(true); setSelectedSkills(new Set(allSkillIds)); };
  const clearAllSkills = () => { setSkillsTouched(true); setSelectedSkills(new Set()); };

  const selectedSkillArray = useMemo(() => Array.from(selectedSkills), [selectedSkills]);

  // ── 创建 mesh ──
  const handleCreate = async () => {
    if (!joinCode.trim()) {
      setErrorMsg(t('meshSettings.joinCodePlaceholder'));
      return;
    }
    setCreating(true);
    setErrorMsg('');
    try {
      const result = await meshCreate({
        joinCode: joinCode.trim(),
        availableSkills: selectedSkillArray,
      });
      // 非 Tauri 运行时（web 预览 / jsdom）invoke 静默返回 undefined；
      // 后端异常时也可能返回异常对象。守卫避免 result.status 解引用抛错。
      if (!result || !result.status) {
        setErrorMsg(t('meshSettings.createFailed'));
        return;
      }
      setStatus(result.status);
      setCreatedTicket(result.ticket ?? '');
      setPeers([]); // 新建 mesh 时本机为唯一节点，无对端。
      notificationService.success(t('meshSettings.createSuccess'));
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
    } finally {
      setCreating(false);
    }
  };

  // ── 加入 mesh ──
  const handleJoin = async () => {
    if (!joinTicket.trim()) {
      setErrorMsg(t('meshSettings.ticketPlaceholder'));
      return;
    }
    setJoining(true);
    setErrorMsg('');
    try {
      const st = await meshJoin({
        ticket: joinTicket.trim(),
        availableSkills: selectedSkillArray,
      });
      // 非 Tauri 运行时 invoke 静默返回 undefined；守卫避免 setStatus(undefined)
      // 导致“显示加入成功但无 mesh 激活”的自相矛盾状态。
      if (!st) {
        setErrorMsg(t('meshSettings.joinFailed'));
        return;
      }
      setStatus(st);
      notificationService.success(t('meshSettings.joinSuccess'));
      void refreshPeers(); // 加入后异步拉取已知对端（不重拉 status、不触发 loading）。
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
    } finally {
      setJoining(false);
    }
  };

  // ── 离开 mesh ──
  const handleLeave = async () => {
    const ok = await confirmDanger(
      t('meshSettings.leaveConfirmTitle'),
      t('meshSettings.leaveConfirmMessage'),
    );
    if (!ok) return;
    try {
      await meshLeave();
      setStatus(null);
      setPeers([]);
      setCreatedTicket('');
      notificationService.success(t('meshSettings.leaveSuccess'));
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
    }
  };

  // ── 提交需求（仅协调者）──
  const handleSubmitRequirement = async () => {
    if (!requirementText.trim()) return;
    setSubmitting(true);
    setErrorMsg('');
    try {
      await meshSubmitRequirement(requirementText.trim());
      notificationService.success(t('meshSettings.submitSuccess'));
      setRequirementText('');
    } catch (e) {
      setErrorMsg(readErrorMessage(e));
    } finally {
      setSubmitting(false);
    }
  };

  const copyTicket = async () => {
    if (!createdTicket) return;
    try {
      await navigator.clipboard.writeText(createdTicket);
      notificationService.info(t('meshSettings.copied'));
    } catch {
      // 剪贴板不可用时静默忽略
    }
  };

  const roleLabel = (role?: string): string => {
    if (role === 'coordinator') return t('meshSettings.roleCoordinator');
    if (role === 'executor') return t('meshSettings.roleExecutor');
    return role || '-';
  };

  return (
    <div className="tupai-mesh-settings">
      <ConfigPageLayout>
        <ConfigPageHeader
          title={t('meshSettings.title')}
          subtitle={t('meshSettings.subtitle')}
          extra={
            <div className="tupai-mesh-settings__header-actions">
              <Button variant="ghost" size="small" onClick={() => void refreshStatus()} disabled={loading}>
                {t('meshSettings.refresh')}
              </Button>
              {status && (
                <Button variant="secondary" size="small" onClick={() => void handleLeave()}>
                  {t('meshSettings.leave')}
                </Button>
              )}
            </div>
          }
        />
        <ConfigPageContent>
          {errorMsg && <div className="tupai-mesh-settings__error">{errorMsg}</div>}
          {loading && <div className="tupai-mesh-settings__hint">{t('meshSettings.loading')}</div>}

          {/* 1. 状态 */}
          <ConfigPageSection
            title={t('meshSettings.status')}
            description={t('meshSettings.identityAutoDerived')}
          >
            {!status ? (
              <div className="tupai-mesh-settings__hint">{t('meshSettings.noMeshActive')}</div>
            ) : (
              <>
                <ConfigPageRow label={t('meshSettings.role')}>
                  <span className="tupai-mesh-settings__value">{roleLabel(status.role)}</span>
                </ConfigPageRow>
                <ConfigPageRow label={t('meshSettings.endpointId')}>
                  <code className="tupai-mesh-settings__mono">{status.endpointId}</code>
                </ConfigPageRow>
                <ConfigPageRow label={t('meshSettings.peers')}>
                  <span className="tupai-mesh-settings__value">{status.peers}</span>
                </ConfigPageRow>
                <ConfigPageRow label={t('meshSettings.joinCode')}>
                  <code className="tupai-mesh-settings__mono">{status.joinCode}</code>
                </ConfigPageRow>
              </>
            )}
            <ConfigPageRow label={t('meshSettings.tenantId')} description={t('meshSettings.identity')}>
              <code className="tupai-mesh-settings__mono">
                {tenantId || t('meshSettings.noTenantId')}
              </code>
            </ConfigPageRow>
          </ConfigPageSection>

          {/* 2. 技能暴露（create / join 共用）*/}
          <ConfigPageSection
            title={t('meshSettings.sectionSkills')}
            description={t('meshSettings.sectionSkillsDesc')}
            extra={
              <div className="tupai-mesh-settings__header-actions">
                <Button variant="ghost" size="small" onClick={selectAllSkills}>
                  {t('meshSettings.selectAll')}
                </Button>
                <Button variant="ghost" size="small" onClick={clearAllSkills}>
                  {t('meshSettings.clearAll')}
                </Button>
              </div>
            }
          >
            {allSkillIds.length === 0 ? (
              <div className="tupai-mesh-settings__hint">{t('meshSettings.noSkills')}</div>
            ) : (
              <div className="tupai-mesh-settings__skill-list">
                {displaySkills.map((s) => {
                  const id = s.skill_id || s.id || '';
                  if (!id) return null;
                  const checked = selectedSkills.has(id);
                  return (
                    <label key={id} className="tupai-mesh-settings__skill-item">
                      <input type="checkbox" checked={checked} onChange={() => toggleSkill(id)} />
                      <span className="tupai-mesh-settings__skill-name">
                        {s.title || s.skill_name || s.name || id}
                      </span>
                      <code className="tupai-mesh-settings__skill-id">{id}</code>
                    </label>
                  );
                })}
              </div>
            )}
            <div className="tupai-mesh-settings__hint">
              {t('meshSettings.availableSkillsCount', { count: selectedSkillArray.length })}
            </div>
          </ConfigPageSection>

          {/* 3. 创建 mesh（协调者）*/}
          <ConfigPageSection
            title={t('meshSettings.sectionCreate')}
            description={t('meshSettings.sectionCreateDesc')}
          >
            <ConfigPageRow
              label={t('meshSettings.joinCodeLabel')}
              description={t('meshSettings.autoJoinCode')}
            >
              <div className="tupai-mesh-settings__inline">
                <Input
                  type="text"
                  value={joinCode}
                  onChange={(e) => setJoinCode(e.target.value)}
                  placeholder={t('meshSettings.joinCodePlaceholder')}
                  inputSize="medium"
                />
                <Button
                  variant="ghost"
                  size="small"
                  onClick={() => setJoinCode(randomJoinCode())}
                >
                  {t('meshSettings.regenerate')}
                </Button>
              </div>
            </ConfigPageRow>
            <ConfigPageRow label=" " multiline>
              <Button variant="primary" onClick={() => void handleCreate()} disabled={creating || !!status}>
                {creating ? t('meshSettings.creating') : t('meshSettings.create')}
              </Button>
            </ConfigPageRow>
            {createdTicket && (
              <ConfigPageRow
                label={t('meshSettings.ticket')}
                description={t('meshSettings.ticketHint')}
                multiline
              >
                <div className="tupai-mesh-settings__inline">
                  <Input
                    type="text"
                    readOnly
                    value={createdTicket}
                    inputSize="medium"
                  />
                  <Button variant="secondary" size="small" onClick={() => void copyTicket()}>
                    {t('meshSettings.copyTicket')}
                  </Button>
                </div>
              </ConfigPageRow>
            )}
          </ConfigPageSection>

          {/* 4. 加入 mesh（执行者）*/}
          <ConfigPageSection
            title={t('meshSettings.sectionJoin')}
            description={t('meshSettings.sectionJoinDesc')}
          >
            <ConfigPageRow
              label={t('meshSettings.ticketLabel')}
              description={t('meshSettings.ticketPlaceholder')}
              multiline
            >
              <Input
                type="text"
                value={joinTicket}
                onChange={(e) => setJoinTicket(e.target.value)}
                placeholder={t('meshSettings.ticketPlaceholder')}
                inputSize="medium"
              />
            </ConfigPageRow>
            <ConfigPageRow label=" " multiline>
              <Button variant="primary" onClick={() => void handleJoin()} disabled={joining || !!status}>
                {joining ? t('meshSettings.joining') : t('meshSettings.join')}
              </Button>
            </ConfigPageRow>
          </ConfigPageSection>

          {/* 5. 对端列表 */}
          <ConfigPageSection
            title={t('meshSettings.sectionPeers')}
            description={t('meshSettings.sectionPeersDesc')}
          >
            {peers.length === 0 ? (
              <div className="tupai-mesh-settings__hint">{t('meshSettings.noPeers')}</div>
            ) : (
              <div className="tupai-mesh-settings__peer-table">
                <div className="tupai-mesh-settings__peer-row tupai-mesh-settings__peer-row--head">
                  <span>{t('meshSettings.peerClientId')}</span>
                  <span>{t('meshSettings.peerTenant')}</span>
                  <span>{t('meshSettings.peerSkills')}</span>
                  <span>{t('meshSettings.peerLoad')}</span>
                </div>
                {peers.map((p, idx) => (
                  <div
                    key={p.clientId || `peer-${idx}`}
                    className="tupai-mesh-settings__peer-row"
                  >
                    <code className="tupai-mesh-settings__mono" title={p.clientId || ''}>
                      {p.clientId ? `${p.clientId.slice(0, 12)}…` : '-'}
                    </code>
                    <code className="tupai-mesh-settings__mono" title={p.tenantId || ''}>
                      {p.tenantId ? `${p.tenantId.slice(0, 12)}…` : '-'}
                    </code>
                    <span>{(p.availableSkills || []).length}</span>
                    <span>{p.currentLoad ?? 0}</span>
                  </div>
                ))}
              </div>
            )}
          </ConfigPageSection>

          {/* 6. 提交需求（仅协调者）*/}
          {isCoordinator && (
            <ConfigPageSection
              title={t('meshSettings.sectionSubmit')}
              description={t('meshSettings.sectionSubmitDesc')}
            >
              <ConfigPageRow label={t('meshSettings.submitRequirement')} multiline>
                <textarea
                  className="tupai-mesh-settings__textarea"
                  value={requirementText}
                  onChange={(e) => setRequirementText(e.target.value)}
                  placeholder={t('meshSettings.requirementPlaceholder')}
                  rows={4}
                />
              </ConfigPageRow>
              <ConfigPageRow label=" " multiline>
                <Button
                  variant="primary"
                  onClick={() => void handleSubmitRequirement()}
                  disabled={submitting || !requirementText.trim()}
                >
                  {submitting ? t('meshSettings.submitting') : t('meshSettings.submit')}
                </Button>
              </ConfigPageRow>
            </ConfigPageSection>
          )}
        </ConfigPageContent>
      </ConfigPageLayout>
    </div>
  );
};

export default MeshSettingsTab;
