import { invoke } from './invoke';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('multiMarketSkill');

export interface MarketSearchResult {
  id: string;
  name: string;
  description: string;
  source: string;
  sourceLabel: string;
  version: string;
  tags: string[];
  author: string;
  downloadCommand: string;
  downloadType: string;
  installed: boolean;
}

export interface DownloadResult {
  success: boolean;
  skillId: string;
  localPath: string | null;
  stdout: string;
  stderr: string;
}

export interface DownloadedSkillInfo {
  id: string;
  name: string;
  source: string;
  sourceLabel: string;
  localPath: string;
  downloadedAt: string;
  fileSize: number;
}

export type MarketSource = 'LinkFox' | 'SkillsSh' | 'ClawHub' | 'SkillStore' | 'Noique' | 'SkillBank' | 'FindSkillCom';

export const MARKET_SOURCE_LABELS: Record<MarketSource, string> = {
  LinkFox: 'LinkFox Skills',
  SkillsSh: 'Nexscope / Skills.sh',
  ClawHub: 'ClawHub',
  SkillStore: 'SkillStore',
  Noique: 'Noique / cross-border-ecommerce-skills',
  SkillBank: 'SkillBank.app',
  FindSkillCom: 'FindSkill.com',
};

export const MARKET_SOURCES: MarketSource[] = [
  'LinkFox', 'SkillsSh', 'ClawHub', 'SkillStore',
  'Noique', 'SkillBank', 'FindSkillCom',
];

export async function searchMultiMarket(query: string, sources?: MarketSource[]): Promise<MarketSearchResult[]> {
  try {
    const results = await invoke<MarketSearchResult[]>('search_multi_market', {
      query,
      sources: sources ?? null,
    });
    // 过滤掉无 downloadCommand 的结果，确保所有返回的技能都能成功下载和执行
    return (results ?? []).filter(r => r.downloadCommand && r.downloadCommand.trim().length > 0);
  } catch (err) {
    log.warn('searchMultiMarket failed', { error: err });
    return [];
  }
}

export async function downloadMarketSkill(
  source: string,
  skillId: string,
  downloadCommand: string,
): Promise<DownloadResult> {
  return invoke<DownloadResult>('download_market_skill', {
    source,
    skillId,
    downloadCommand,
  });
}

export async function listDownloadedMarketSkills(): Promise<DownloadedSkillInfo[]> {
  try {
    return await invoke<DownloadedSkillInfo[]>('list_downloaded_market_skills') ?? [];
  } catch (err) {
    log.warn('listDownloadedMarketSkills failed', { error: err });
    return [];
  }
}

export async function deleteDownloadedMarketSkill(skillId: string): Promise<boolean> {
  try {
    return await invoke<boolean>('delete_downloaded_market_skill', { skillId });
  } catch (err) {
    log.warn('deleteDownloadedMarketSkill failed', { error: err });
    return false;
  }
}

log.debug('multiMarketSkill API loaded', { sourceCount: MARKET_SOURCES.length });
