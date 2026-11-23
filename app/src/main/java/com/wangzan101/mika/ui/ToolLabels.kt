package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolIndex

/**
 * 工具在界面上的说法:工具声明里的 label(中文)按界面语言换成对应的资源;没有 label 的原样显示工具名。
 * 改名前的运行记录里的旧名按 [legacy] 认成现在的工具。
 */
class ToolLabels(private val tools: ToolIndex, private val legacy: LegacyNames = legacyNames()) {
    fun label(tool: String): String {
        // 没声明 label 的工具用工具名兜底查表(查不到仍显示工具名),声明了的照旧按声明文案查。
        val declared = (tools[tool] ?: legacy.resolve(tool)?.let { tools[it] })?.label ?: tool
        return TOOL_LABELS[declared]?.let { tr(it) } ?: declared
    }

    companion object {
        /** 工具声明里的 label → 界面文字;中文资源与 label 一字不差(ToolLabelsTest 核对每个工具都有)。 */
        val TOOL_LABELS: Map<String, Int> = mapOf(
            "短信验证码登录" to R.string.sms_login_tool,
            "查看已装的 App" to R.string.tool_list_apps,
            "打开 App" to R.string.tool_open_app,
            "分享文字到 App" to R.string.tool_share_text,
            "分享音频到 App" to R.string.tool_share_audio,
            "分享图片到 App" to R.string.tool_share_image,
            "生成语音" to R.string.tool_audio_generate,
            "保存音频" to R.string.tool_audio_save,
            "转写音频" to R.string.tool_audio_transcribe,
            "说话" to R.string.tool_speak,
            "设闹钟" to R.string.tool_set_alarm,
            "设计时器" to R.string.tool_set_timer,
            "查看闹钟" to R.string.tool_show_alarms,
            "添加日程" to R.string.tool_add_calendar_event,
            "批量添加日程" to R.string.tool_calendar_bulk_insert,
            "拨滚轮" to R.string.tool_set_wheel,
            "滚动查找" to R.string.tool_scroll_find,
            "等待界面条件" to R.string.tool_wait_condition,
            "等待界面事件" to R.string.tool_wait_event,
            "提取界面数据" to R.string.tool_extract,
            "检查弹窗" to R.string.tool_check_dialog,
            "多点手势" to R.string.tool_gesture,
            "系统命令" to R.string.tool_shell,
            "root 命令" to R.string.tool_root,
            "通话状态" to R.string.calls_tool_state,
            "拨打电话" to R.string.calls_tool_dial,
            "AI 接听" to R.string.calls_answer,
            "挂断电话" to R.string.calls_tool_hangup,
            "人工接管" to R.string.calls_takeover,
            "查找界面控件" to R.string.tool_find_element,
            "采集多屏内容" to R.string.tool_collect,
            "连续操作手机" to R.string.tool_phone_run,
            "查看设备文件" to R.string.tool_list_device_files,
            "搜索设备文件" to R.string.tool_search_device_files,
            "复制设备文件" to R.string.tool_copy_device_file,
            "处理文件" to R.string.tool_process_files,
            "运行自带脚本" to R.string.tool_run_script,
            "读文件" to R.string.tool_read_file,
            "看图" to R.string.tool_view_image,
            "写文件" to R.string.tool_write_file,
            "搜索文件" to R.string.tool_search_files,
            "导出文件" to R.string.tool_export_file,
            "查看授权文件夹" to R.string.tool_list_folder_files,
            "生成图片" to R.string.tool_image_generate,
            "修改图片" to R.string.tool_image_edit,
            "保存图片" to R.string.tool_image_save,
            "打开网页" to R.string.tool_open_page,
            "搜索网页" to R.string.web_search_action,
            "切换网页标签" to R.string.tool_switch_tab,
            "切换网页版本" to R.string.tool_switch_version,
            "保持网页开着" to R.string.tool_keep_page,
            "定下 App 做完后留着还是关掉" to R.string.tool_app_after_task,
            "读网页" to R.string.tool_read_page,
            "查看下载" to R.string.tool_downloads,
            "查看网页收到的数据" to R.string.tool_network,
            "等网页变化" to R.string.tool_wait_page,
            "读取网页数据" to R.string.tool_read_script,
            "运行站点命令" to R.string.tool_site_command,
            "上传文件" to R.string.tool_upload,
            "遇到障碍" to R.string.tool_obstacle,
            "查找 App/网站知识" to R.string.tool_find_knowledge,
            "查找技能" to R.string.tool_find_skill,
            "查看 App/网站知识" to R.string.tool_read_knowledge,
            "查看技能" to R.string.tool_read_skill,
            "保存技能" to R.string.tool_save_skill,
            "查找记忆" to R.string.tool_memory_search,
            "写入记忆" to R.string.tool_memory_save,
            "修改记忆" to R.string.tool_memory_edit,
            "查看设置" to R.string.tool_list_settings,
            "改设置" to R.string.tool_set_setting,
            "查看自身状态" to R.string.tool_mika_status,
            "打开 Mika 的页面" to R.string.tool_mika_open_screen,
            "打开文件" to R.string.tool_mika_open_file,
            "发通知" to R.string.tool_mika_notify,
            "复制文字" to R.string.tool_mika_copy,
            "在浏览器里打开" to R.string.tool_mika_open_in_browser,
            "在浏览器里搜索" to R.string.tool_mika_search_in_browser,
            "查看网站登录" to R.string.tool_mika_sites,
            "查看正在看的网页" to R.string.tool_mika_user_page,
            "请你登录网站" to R.string.tool_mika_login_site,
            "核对登录" to R.string.tool_mika_confirm_login,
            "常用网站" to R.string.tool_mika_favorites,
            "说明没办成的原因" to R.string.tool_explain_failure,
            "问你一个问题" to R.string.tool_ask_user,
            "请你接手" to R.string.tool_takeover,
            "请你同意" to R.string.tool_confirm,
            "等待" to R.string.tool_wait,
            "检查屏幕" to R.string.tool_diagnose,
            "返回" to R.string.tool_back,
            "按键" to R.string.tool_press_key,
            "看屏幕" to R.string.tool_observe,
            "读取界面元素" to R.string.tool_list_elements,
            "截图" to R.string.tool_screenshot,
            "滚动" to R.string.tool_scroll,
            "滑动" to R.string.tool_swipe,
            "点击" to R.string.tool_tap,
            "输入文字" to R.string.tool_type,
            "清空输入框" to R.string.tool_clear,
            "wechat_send_owner" to R.string.tool_wechat_owner,
        )
    }
}
