// IM 平台分类
export const CHINA_IM_PLATFORMS = ['dingtalk', 'feishu', 'wework'] as const;
export const GLOBAL_IM_PLATFORMS = ['telegram', 'discord', 'whatsapp'] as const;
export const ALL_IM_PLATFORMS = [...CHINA_IM_PLATFORMS, ...GLOBAL_IM_PLATFORMS] as const;

/**
 * 获取所有可见的 IM 平台（始终显示所有平台）
 */
export const getVisibleIMPlatforms = (_language: 'zh' | 'en'): readonly string[] => {
  // 始终显示所有平台
  return ALL_IM_PLATFORMS;
};
