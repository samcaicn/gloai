import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { invoke } from '@tauri-apps/api/core';
import type { SkillLevel, SkillMarketItem } from '@/infrastructure/config/types';
import { useWorkspaceManagerSync } from '@/infrastructure/hooks/useWorkspaceManagerSync';
import { useNotification } from '@/shared/notification-system';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('SkillsScene:useSkillMarket');

const DEFAULT_PAGE_SIZE = 10;
const MAX_TOTAL_SKILLS = 500;

interface UseSkillMarketOptions {
  searchQuery: string;
  installedSkillNames: Set<string>;
  onInstalledChanged?: () => Promise<void> | void;
  pageSize?: number;
}

// 后端 get_market_skills 返回的 MarketSkillInfo 结构。
// 注意：后端只有 get_market_skills / install_skill / inspect_market_skill，
// 不存在 list_skill_market / search_skill_market / download_skill_market 命令。
// 此 hook 直接调用后端存在的命令，确保技能列表加载和下载安装都能正常工作。
interface MarketSkillInfo {
  name: string;
  description: string;
  source: string;
  identifier: string;
  trust_level: string;
  repo: string;
  path: string;
  category?: string;
  tags: string[];
  installed: boolean;
  installed_source?: string;
}

// 将后端 MarketSkillInfo 映射为前端 SkillMarketItem。
function toMarketItem(s: MarketSkillInfo): SkillMarketItem {
  return {
    id: s.identifier || s.name,
    name: s.name,
    description: s.description,
    source: s.source,
    installs: 0,
    url: s.repo || '',
    installId: s.identifier || s.name,
  };
}

export function useSkillMarket({
  searchQuery,
  installedSkillNames,
  onInstalledChanged,
  pageSize = DEFAULT_PAGE_SIZE,
}: UseSkillMarketOptions) {
  const { t } = useTranslation('scenes/skills');
  const notification = useNotification();
  const { hasWorkspace, isRemoteWorkspace } = useWorkspaceManagerSync();

  const [marketSkills, setMarketSkills] = useState<SkillMarketItem[]>([]);
  const [marketLoading, setMarketLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [marketError, setMarketError] = useState<string | null>(null);
  const [downloadingPackage, setDownloadingPackage] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);

  // 使用后端 get_market_skills 命令拉取技能市场索引。
  // 该命令从 HERMES_SKILLS_INDEX_URL 拉取索引并交叉比对本地已安装技能。
  const fetchSkills = useCallback(async (_query: string | undefined, _limit: number) => {
    const list = await invoke<MarketSkillInfo[]>('get_market_skills');
    const items = (list || []).map(toMarketItem);
    // 客户端过滤搜索（后端不支持 query 参数）
    const normalized = _query?.trim().toLowerCase();
    if (normalized) {
      return items.filter(
        (s) =>
          s.name.toLowerCase().includes(normalized) ||
          s.description.toLowerCase().includes(normalized),
      );
    }
    return items;
  }, []);

  const loadFirstPage = useCallback(async (query?: string) => {
    setMarketLoading(true);
    setMarketError(null);
    setCurrentPage(0);
    try {
      const skillList = await fetchSkills(query, pageSize);
      setMarketSkills(skillList);
      setHasMore(skillList.length > pageSize);
    } catch (err) {
      log.error('Failed to load skill market', err);
      setMarketError(err instanceof Error ? err.message : String(err));
    } finally {
      setMarketLoading(false);
    }
  }, [fetchSkills, pageSize]);

  useEffect(() => {
    loadFirstPage(searchQuery || undefined);
  }, [loadFirstPage, searchQuery]);

  const refresh = useCallback(async () => {
    await loadFirstPage(searchQuery || undefined);
  }, [loadFirstPage, searchQuery]);

  const displayMarketSkills = useMemo(() => {
    const entries = marketSkills.map((skill, index) => ({
      skill,
      index,
      installed: installedSkillNames.has(skill.name),
    }));

    entries.sort((a, b) => {
      if (a.installed !== b.installed) {
        return a.installed ? -1 : 1;
      }
      const installDelta = (b.skill.installs ?? 0) - (a.skill.installs ?? 0);
      if (installDelta !== 0) {
        return installDelta;
      }
      return a.index - b.index;
    });

    return entries.map((entry) => entry.skill);
  }, [installedSkillNames, marketSkills]);

  const loadedPages = Math.ceil(displayMarketSkills.length / pageSize);
  const totalPages = hasMore ? loadedPages + 1 : Math.max(1, loadedPages);

  const paginatedSkills = useMemo(() => displayMarketSkills.slice(
    currentPage * pageSize,
    (currentPage + 1) * pageSize,
  ), [currentPage, displayMarketSkills, pageSize]);

  const goToPrevPage = useCallback(() => {
    setCurrentPage((page) => Math.max(0, page - 1));
  }, []);

  const goToNextPage = useCallback(async () => {
    const nextPage = currentPage + 1;
    const neededCount = Math.min((nextPage + 1) * pageSize, MAX_TOTAL_SKILLS);

    if (displayMarketSkills.length >= neededCount) {
      setCurrentPage(nextPage);
      // 所有技能已在首次加载时拉取，hasMore 直接按总数计算
      setHasMore(displayMarketSkills.length > (nextPage + 1) * pageSize);
      return;
    }

    if (!hasMore) {
      return;
    }

    setCurrentPage(nextPage);

    try {
      setLoadingMore(true);
      const skillList = await fetchSkills(searchQuery || undefined, neededCount);
      setMarketSkills(skillList);
      const hitCap = neededCount >= MAX_TOTAL_SKILLS;
      setHasMore(!hitCap && skillList.length > neededCount);
    } catch (err) {
      log.error('Failed to load more skills', err);
      setCurrentPage(currentPage);
    } finally {
      setLoadingMore(false);
    }
  }, [currentPage, displayMarketSkills.length, fetchSkills, hasMore, pageSize, searchQuery]);

  // 使用后端 install_skill 命令下载并安装技能。
  // install_skill 执行 `hermes skills install <identifier> --yes`，
  // 将技能文件（SKILL.md + 代码）真正下载到本地 skills 目录。
  // 安装后 onInstalledChanged 回调刷新已安装列表。
  const handleDownload = useCallback(async (skill: SkillMarketItem, _targetLevel: SkillLevel = 'project') => {
    const resolvedLevel: SkillLevel = isRemoteWorkspace ? 'user' : _targetLevel;
    if (resolvedLevel === 'project' && !hasWorkspace) {
      notification.warning(t('messages.noWorkspace'));
      return;
    }
    try {
      setDownloadingPackage(skill.installId);
      // install_skill 执行 hermes skills install <identifier> --yes
      // identifier 使用 skill.installId（对应 MarketSkillInfo.identifier）
      const result = await invoke<{ success: boolean; stdout: string; stderr: string }>(
        'install_skill',
        { identifier: skill.installId },
      );
      if (!result?.success) {
        throw new Error(result?.stderr || result?.stdout || 'install failed');
      }
      // 从 stdout 中解析安装的技能名（hermes skills install 输出格式）
      const installedName = skill.name;
      notification.success(t('messages.marketDownloadSuccess', { name: installedName }));
      await onInstalledChanged?.();
    } catch (err) {
      notification.error(
        t('messages.marketDownloadFailed', {
          error: err instanceof Error ? err.message : String(err),
        }),
      );
    } finally {
      setDownloadingPackage(null);
    }
  }, [hasWorkspace, isRemoteWorkspace, notification, onInstalledChanged, t]);

  return {
    marketSkills: paginatedSkills,
    marketLoading,
    loadingMore,
    marketError,
    downloadingPackage,
    hasMore,
    currentPage,
    totalPages,
    refresh,
    goToPrevPage,
    goToNextPage,
    handleDownload,
    hasWorkspace,
    isRemoteWorkspace,
    totalLoaded: displayMarketSkills.length,
  };
}
