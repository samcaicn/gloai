// Seedance 广告创意视频生成 — LLM Prompt 技能
// 纯 prompt 指导型技能，SKILL.md 作为 system prompt 注入 LLM，
// 由 Agent 引导用户完成爆款视频结构分析 + 商品图复刻生成.
// 动作: analyze / rewrite / preview / generate
async function handler(params, complete) {
  const { action } = params
  cap.llm.setComplete(complete)

  return {
    ok: true,
    action: action || 'analyze',
    message: 'Seedance 广告创意技能已就绪，请提供参考视频和商品图。',
  }
}
