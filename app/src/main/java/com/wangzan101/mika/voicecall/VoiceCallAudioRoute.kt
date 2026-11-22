package com.wangzan101.mika.voicecall

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 语音通话的出声设备。Speaker 外放;Earpiece/Wired/Bluetooth 都是「私密听音设备」(声音不外放)。 */
enum class RouteDevice { Speaker, Earpiece, Wired, Bluetooth }

/**
 * 免提路由决策,纯函数,全组合可测。
 *
 * 策略,三态 [speakerRequested]:
 * - `true`(用户要免提):扬声器在场就用,不在场退回私密最优。
 * - `false`(用户关免提):私密最优——有线 > 蓝牙 > 听筒,无私密设备才留扬声器(平板形态)。
 * - `null`(还没表态,自动):有线 > 蓝牙 > 扬声器——插着的耳机几乎总是用户刚接上的,直接接管;
 *   没人贴耳听助手说话,所以自动态跳过听筒。
 * 决策本身与 SDK 无关:[sdkInt] 只在落地层分流(API 31+ setCommunicationDevice,以下 speakerphone 开关),
 * 参数保留在签名里让调用点不必另写分流。
 */
fun chooseRoute(available: List<RouteDevice>, sdkInt: Int, speakerRequested: Boolean?): RouteDevice {
    val set = available.toSet()
    if (speakerRequested == true && RouteDevice.Speaker in set) return RouteDevice.Speaker
    when {
        RouteDevice.Wired in set -> return RouteDevice.Wired
        RouteDevice.Bluetooth in set -> return RouteDevice.Bluetooth
    }
    return when {
        speakerRequested == false && RouteDevice.Earpiece in set -> RouteDevice.Earpiece
        RouteDevice.Speaker in set -> RouteDevice.Speaker
        RouteDevice.Earpiece in set -> RouteDevice.Earpiece
        else -> RouteDevice.Speaker
    }
}

/**
 * 通话免提路由:把 [chooseRoute] 的决策落到系统音频上。
 *
 * - API 31+:setCommunicationDevice 精确选中;挂断 clearCommunicationDevice 恢复默认。
 * - API 29–30:speakerphone 开关(私密设备的选择由系统按连接状态自动完成);挂断恢复通话前的开关状态。
 * - 选中态始终读系统实际通信设备([route] 只反映系统读数,不记账自己的意图)。
 * - AudioDeviceCallback 热插拔:设备增减时重新决策并应用。
 *
 * 装配顺序:先让通话音频实现进入 MODE_IN_COMMUNICATION([VoiceCallAudio.requestCallAudio]),再 [begin];
 * 挂断先 [end] 恢复路由,再 abandonCallAudio 退模式。本类不碰音频模式与焦点(那是 VoiceCallAudio 的地盘)。
 */
// isSpeakerphoneOn/isWiredHeadsetOn/isBluetoothScoOn 已弃用,但它们是 API 29–30 的唯一落地路径,31+ 不走这里
@Suppress("DEPRECATION")
class VoiceCallAudioRoute(context: Context) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val _route = MutableStateFlow(RouteDevice.Speaker)
    /** 当前生效的出声设备,读系统实际状态,不是「我们希望的状态」。 */
    val route: StateFlow<RouteDevice> = _route

    private val lock = Any()
    private var active = false
    private var speakerRequested: Boolean? = null // 没表态:自动——插着的耳机接管,否则外放(见 chooseRoute)
    private var legacySpeakerBefore = false

    private val hotPlug = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    /** 通话开始:接管路由并注册热插拔监听。可重复调用(幂等);[speaker] 只由界面上的免提开关给。 */
    fun begin(speaker: Boolean? = null) {
        synchronized(lock) {
            if (!active) {
                active = true
                legacySpeakerBefore = manager.isSpeakerphoneOn
                manager.registerAudioDeviceCallback(hotPlug, null)
            }
            speakerRequested = speaker
        }
        refresh()
    }

    /** 免提开关:请求外放或回到私密设备。 */
    fun setSpeakerRequested(requested: Boolean) {
        synchronized(lock) { speakerRequested = requested }
        refresh()
    }

    /** 挂断:恢复通话前的路由并摘掉热插拔监听。可重复调用(幂等)。 */
    fun end() {
        synchronized(lock) {
            if (!active) return
            active = false
            manager.unregisterAudioDeviceCallback(hotPlug)
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    manager.clearCommunicationDevice()
                } else {
                    manager.isSpeakerphoneOn = legacySpeakerBefore
                }
            }
        }
    }

    /** 热插拔或外部改变路由后:重新决策、应用、并把系统实际选中态刷进 [route]。 */
    private fun refresh() {
        val decision = synchronized(lock) {
            if (!active) return
            val available = availableDevices()
            available to chooseRoute(available, Build.VERSION.SDK_INT, speakerRequested)
        }
        applyLocked(desired = decision.second, available = decision.first)
    }

    private fun applyLocked(desired: RouteDevice, available: List<RouteDevice>) {
        if (Build.VERSION.SDK_INT >= 31) {
            val current = manager.communicationDevice
            if (routeOf(current) != desired) {
                val target = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { routeOf(it) == desired }
                if (target == null || !manager.setCommunicationDevice(target)) {
                    // 目标设备刚被拔掉/系统拒选:留在系统默认上,选中态照实读
                }
            }
        } else {
            val wantSpeaker = desired == RouteDevice.Speaker
            if (manager.isSpeakerphoneOn != wantSpeaker) {
                runCatching { manager.isSpeakerphoneOn = wantSpeaker }
            }
        }
        _route.value = readSystemRoute(available)
    }

    /** 系统当前实际通信设备;读不到通信设备时按可用集合推默认(听筒在场默认听筒,否则扬声器)。 */
    private fun readSystemRoute(available: List<RouteDevice>): RouteDevice {
        if (Build.VERSION.SDK_INT >= 31) {
            routeOf(manager.communicationDevice)?.let { return it }
            return if (RouteDevice.Earpiece in available) RouteDevice.Earpiece else RouteDevice.Speaker
        }
        return when {
            manager.isSpeakerphoneOn -> RouteDevice.Speaker
            manager.isWiredHeadsetOn -> RouteDevice.Wired
            manager.isBluetoothScoOn -> RouteDevice.Bluetooth
            RouteDevice.Earpiece in available -> RouteDevice.Earpiece
            else -> RouteDevice.Speaker
        }
    }

    private fun availableDevices(): List<RouteDevice> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { routeOf(it) }.distinct()

    private companion object {
        fun routeOf(device: AudioDeviceInfo?): RouteDevice? = when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteDevice.Speaker
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteDevice.Earpiece
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> RouteDevice.Wired
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> RouteDevice.Bluetooth
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            -> RouteDevice.Wired
            else -> null
        }
    }
}
