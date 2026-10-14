import type { Env, Tier } from '../types';

/**
 * 三档积分套餐包（唯一档位定义源）。
 *
 * 价格固定 ¥19.9 / ¥59.9 / ¥99.9；到账积分 = 价格(元) × CREDITS_PER_YUAN，
 * 比例未定——上线前只改 wrangler.toml 的 CREDITS_PER_YUAN 一个值即可。
 */
const TIER_DEFS: Array<Omit<Tier, 'credits' | 'priceCents'> & { priceYuan: number }> = [
  {
    id: 'basic',
    name: '基础积分包',
    tagline: '适合首次体验完整任务',
    priceYuan: 19.9,
    perks: ['全行业专家团调用', '营销海报 / 文案生成', '项目成果打包导出', '积分长期有效不清零'],
  },
  {
    id: 'plus',
    name: '进阶积分包',
    tagline: '适合日常持续推广与创作',
    priceYuan: 59.9,
    featured: true,
    perks: ['全行业专家团调用', '营销海报 / 文案生成', '项目成果打包导出', '积分长期有效不清零'],
  },
  {
    id: 'pro',
    name: '至尊积分包',
    tagline: '高频创作团队最划算',
    priceYuan: 99.9,
    perks: ['全部进阶权益', '积分单价最低', '多任务并行执行', '优先客服支持'],
  },
];

/** 每元积分比例：读取失败或非法时退回 50（与 wrangler.toml 默认一致）。 */
export function creditsPerYuan(env: Env): number {
  const n = Number.parseInt(env.CREDITS_PER_YUAN ?? '', 10);
  return Number.isFinite(n) && n > 0 ? n : 50;
}

/** 档位列表（积分按当前比例实时计算，/api/pay/tiers 用）。 */
export function listTiers(env: Env): Tier[] {
  const ratio = creditsPerYuan(env);
  return TIER_DEFS.map((def) => ({
    id: def.id,
    name: def.name,
    tagline: def.tagline,
    priceCents: Math.round(def.priceYuan * 100),
    credits: Math.round(def.priceYuan * ratio),
    perks: def.perks,
    featured: def.featured,
  }));
}

export function findTier(env: Env, id: string): Tier | null {
  return listTiers(env).find((t) => t.id === id) ?? null;
}
