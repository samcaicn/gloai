package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.frontdesk.DeskPersona
import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.settings.FeatureFlags

/**
 * 前台此刻用的人格,和后台任务取法一样(见 RunPrepStages):设置里选的在用的那一套,对所有对话一样;
 * 人格功能关着、或没选时是默认的称呼、没有人格层。聊天每一轮、通话每次接通时取一次。
 */
fun deskPersona(app: MikaApp): DeskPersona {
    val persona = if (FeatureFlags.get(app).persona()) app.personas.active() else null
    val assistant = PersonaPrompt.nickname(persona?.nickname)
    return DeskPersona(assistant, persona?.let { PersonaPrompt.layer(it.name, it.prompt, it.style, assistant) })
}
