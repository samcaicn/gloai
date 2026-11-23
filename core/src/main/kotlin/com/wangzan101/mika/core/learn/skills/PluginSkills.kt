package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.learn.knowledge.GuideInfo

/** 插件技能的 kind;名字是 `<plugin-id>/<skill-name>`,只读,随插件启用/停用出现或消失。 */
const val PLUGIN_KIND = "plugin"

/** 已启用插件提供的只读技能;实现由 app 层提供,按当前注册表现读。 */
interface PluginSkills {
    /** 只返回已启用插件的技能;kind = [PLUGIN_KIND],name = `<pluginId>/<skillName>`。 */
    fun entries(): List<GuideInfo>

    /** 读某个文件的正文(SKILL.md 或附件,去掉头部);读不到返回 null。 */
    fun read(info: GuideInfo, file: String): String?
}
