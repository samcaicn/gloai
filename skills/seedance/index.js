// Seedance 视频生成 — LLM Prompt 技能
// 纯 prompt 指导型技能，SKILL.md 作为 system prompt 注入 LLM，
// 由 Agent 通过对话引导用户完成 Seedance 2.0 视频生成。
// 不需要 JS 执行逻辑。

async function execute(params, complete) {
  // 纯 LLM 驱动，无 JS 执行逻辑
  const action = params?.action || 'guide';
  return {
    ok: true,
    action,
    message: `Seedance 技能已就绪，请描述你想生成的视频内容。`,
  };
}

module.exports = { execute };
