// Seedance 视频生成 — LLM Prompt 驱动
// 纯 prompt 指导型技能，SKILL.md 作为 system prompt 注入 LLM，
// 由 Agent 通过对话引导用户完成 Seedance 2.0 视频生成.
// 动作: generate / guide / help
async function handler(params, complete) {
  const { action } = params
  cap.llm.setComplete(complete)

  const result = {
    ok: true,
    action: action || 'guide',
    message: 'Seedance 技能已就绪，请描述你想生成的视频内容。',
  }
  return result
}
