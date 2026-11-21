package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.gate.gateTargetOf
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.assembly.allTools
import com.wangzan101.mika.core.cap.audio.AudioPort
import com.wangzan101.mika.core.cap.image.ImagePort
import com.wangzan101.mika.core.cap.image.SavedImage
import com.wangzan101.mika.core.cap.image.StoredImage
import com.wangzan101.mika.core.cap.transcribe.TranscribePort
import com.wangzan101.mika.core.cap.transcribe.Transcript
import com.wangzan101.mika.core.model.GeneratedImage
import com.wangzan101.mika.core.model.ImageCapabilities
import com.wangzan101.mika.core.model.ImageReference
import com.wangzan101.mika.core.model.ImageRequest
import com.wangzan101.mika.core.model.ImageResponse
import com.wangzan101.mika.core.model.SavedAudio
import com.wangzan101.mika.core.model.StoredAudio
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.core.testing.SANDBOX_TOOLS
import com.wangzan101.mika.core.testing.WEB_TOOLS

/** 由工具声明的风险推出的分类,和改成声明之前按名字列的表一样;手机、网页两套屏幕工具各有一份同样的动词。 */
class ToolRiskTest {
    private fun both(vararg verbs: String) = verbs.flatMap { listOf("phone_$it", "web_$it") }.toSet()

    /** 原来要过锁屏/暂停关卡的工具表。 */
    private val oldWriting = both("tap", "scroll", "swipe", "type_text", "clear_text", "back", "press_key", "diagnose") + setOf(
        "phone_launch_app", "phone_share_text", "phone_share_audio", "phone_share_image", "phone_gesture", "phone_sms_login", "hand_to_user",
        "phone_set_alarm", "phone_set_timer", "phone_show_alarms", "phone_add_calendar_event", "phone_set_wheel",
        "web_navigate", "web_tabs", "web_upload", "web_interact", "web_site_mode", "phone_open_link", "web_keep_open", "phone_app_after_task",
    )
    private val oldSandbox = setOf("run_command", "run_bundled_script", "read_file", "write_file", "search_files", "share_file", "view_image", "list_folder_files")
    private val oldWeb = setOf(
        "web_navigate", "web_read_page", "web_evaluate_js", "web_run_site_command", "web_tabs", "web_downloads", "web_upload",
        "web_site_mode", "web_script", "web_interact", "web_capture", "web_wait_for", "web_keep_open",
    ) + both("observe", "list_elements", "screenshot", "describe", "find_visually", "tap", "scroll", "swipe",
        "type_text", "clear_text", "back", "press_key", "diagnose").filter { it.startsWith("web_") }
    private val names = allTools().map { it.name }

    @Test fun writingToolsDerivedFromRisk() {
        assertEquals(oldWriting, names.filter { isWritingTool(it, JsonObject(emptyMap())) }.toSet())
    }

    @Test fun groupsMatchOldNameSets() {
        assertEquals(oldSandbox, names.filter(::isSandboxTool).toSet())
        assertEquals(oldSandbox, SANDBOX_TOOLS)
        assertEquals(oldWeb, WEB_TOOLS)
    }

    private object NoTranscribePort : TranscribePort {
        override suspend fun transcribe(path: String, language: String?) = Transcript("")
    }

    private object NoAudioPort : AudioPort {
        override val modelName = "test-voice"
        override val capabilities = TtsCapabilities(maxChars = 100, formats = setOf("wav"))
        override suspend fun synthesize(request: TtsRequest) = TtsResponse(ByteArray(0), "audio/wav")
        override suspend fun store(response: TtsResponse, nameHint: String?, text: String) = StoredAudio("/workspace/out/audio/x.wav", 0, "audio/wav")
        override suspend fun saveToMusic(path: String, name: String?) = SavedAudio("Music/Mika/x.wav", "content://x", 0)
        override suspend fun speak(request: TtsRequest) {}
    }

    private object NoImagePort : ImagePort {
        override val modelName = "test-image"
        override val capabilities = ImageCapabilities(supportsEdit = true, maxReferences = 1, maxCount = 1)
        override suspend fun generate(request: ImageRequest) = ImageResponse(emptyList())
        override suspend fun readReference(path: String) = ImageReference(ByteArray(0), "image/png")
        override suspend fun store(image: GeneratedImage, nameHint: String?, prompt: String) = StoredImage("/workspace/out/images/x.png", 0, 0, 0)
        override suspend fun saveToGallery(path: String, name: String?) = SavedImage("Pictures/Mika/x.png", "content://x", 0)
    }

    @Test fun readCallsRefuseWhatTheOldListsRefused() = runTest {
        val refused = oldWriting + oldSandbox + WEB_SCRIPT_TOOL +
            setOf("phone_find", "phone_collect", "phone_dialog", "phone_run", "do_each", "phone_system_command", "phone_calendar_bulk_insert", "schedule_task", "spawn_agent", "settings_set", "root_run_command", "web_search",
                "mika_open_screen", "mika_open_file", "mika_notify", "mika_copy",
                "mika_open_in_browser", "mika_search_in_browser", "mika_login_site", "mika_confirm_login", "mika_favorites",
                "phone_call_state", "phone_call_dial", "phone_call_answer", "phone_call_hangup", "phone_call_takeover",
                "list_device_files", "copy_device_file", "search_device_files", "list_device_directory_hints", "update_device_directory_description",
                "audio_generate", "audio_save", "audio_transcribe", "speak",
                "image_generate", "image_edit", "image_save", "wechat_send_owner")
        assertEquals(refused, allTools().filter { !it.readOnly }.map { it.name }.toSet())
        // root 命令、通话工具都只在给了端口时登记:各给一个,所有工具都在
        val d = toolDispatcher(FakeScreen(), audio = NoAudioPort, audioSaveEnabled = true, audioSpeakTool = true, transcribe = NoTranscribePort,
            image = NoImagePort, imageSaveEnabled = true, root = object : com.wangzan101.mika.core.cap.root.RootPort {
            override suspend fun run(command: String, timeoutMs: Long) = com.wangzan101.mika.core.cap.root.RootRun.Unavailable
        }, deviceFiles = object : com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort {
            override suspend fun search(query: com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchQuery): com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchResult = error("read call reached device files")
            override suspend fun list(path: String?, cursor: String?, limit: Int): com.wangzan101.mika.core.cap.devicefiles.DeviceFilePage = error("read call reached device files")
            override suspend fun importFile(path: String, workspace: String): com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport = error("read call reached device files")
        }, phoneCalls = object : PhoneCallPort {
            override suspend fun state() = JsonObject(emptyMap())
            override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?) = JsonObject(emptyMap())
            override suspend fun answer(callId: String) = JsonObject(emptyMap())
            override suspend fun hangup(callId: String) = JsonObject(emptyMap())
            override suspend fun takeover() = JsonObject(emptyMap())
        }, wechatOwner = com.wangzan101.mika.core.wechat.WechatOwnerPort {
            com.wangzan101.mika.core.wechat.WechatOwnerResult(com.wangzan101.mika.core.wechat.WechatOwnerResult.Status.Unavailable)
        })
        for (name in names) {
            val r = d.executeRead(Part.ToolCall("c", name, "{}")).content
            assertEquals(name, name in refused, r["error"] == JsonPrimitive("not_a_read"))
        }
    }

    @Test fun externalToolsDeclareHowTheirTargetIsJudged() {
        for (t in allTools()) assertEquals(t.name, t.risk == Risk.External, t.gateTarget != null || t.approvalRisk != null)
        assertEquals(both("tap", "type_text", "press_key", "swipe", "scroll") + setOf("web_interact", "web_upload", "schedule_task",
            "mika_open_in_browser", "mika_search_in_browser", "mika_login_site"),
            allTools().filter { it.risk == Risk.External }.map { it.name }.toSet())
    }

    /**
     * 原来 needsCheck / classify 按名字分的几支,现在按声明的目标分;每支的判法没动,
     * 名字到目标的对应和原来一样,判定结果就一样(具体情形由 IrreversibleDetectorTest 覆盖)。
     */
    @Test fun gateTargetsMatchOldNameBranches() {
        val perVerb = mapOf("tap" to GateTarget.Tap, "type_text" to GateTarget.Enter, "press_key" to GateTarget.Key, "swipe" to GateTarget.Swipe, "scroll" to GateTarget.Swipe)
        val old = perVerb.flatMap { (v, g) -> listOf("phone_$v" to g, "web_$v" to g) }.toMap() +
            mapOf("web_upload" to GateTarget.FileControl, "web_interact" to GateTarget.Interact)
        for (t in allTools().map { it.name } + "do_steps" + "unknown") assertEquals(t, old[t], gateTargetOf(t))
    }

    @Test fun everyToolHasAUniqueKnownName() {
        assertEquals(names.size, names.toSet().size)
    }
}
