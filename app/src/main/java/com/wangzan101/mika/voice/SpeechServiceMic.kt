package com.wangzan101.mika.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings

/** 一个识别服务所在的应用有没有录音权限。它和 Mika 是两个应用,各有各的权限。 */
enum class ServiceMic {
    /** 申请了,也给了。 */
    Granted,
    /** 申请了,没给:它录不了音。 */
    Missing,
    /** 没申请录音权限(可能靠别的办法拿音频):不下结论。 */
    NotRequested,
    /** 查不到(这个应用看不见、查询出错)。 */
    Unknown,
}

/** 缺录音权限的那个识别服务:[packageName] 给「去设置」用,[label] 是系统里它的名字,给人看。 */
data class SpeechServiceTarget(val packageName: String, val label: String)

/**
 * 识别服务说「没有权限」时,分清缺权限的是谁。Mika 的录音权限给了,识别服务(另一个应用)自己的没给,它照样录不了音——
 * 这时让用户去 Mika 的权限页是指错了地方。
 *
 * 只在三样都确定时才说是识别服务缺权限:Mika 自己有录音权限;这次用的是哪个服务认得出来;那个应用申请了录音权限却没拿到。
 * 有一样不确定就不下这个结论,照原来的说法。只查询,不替别的应用申请或授予权限。
 */
object SpeechServiceMic {
    /** 系统设置里「默认的识别服务」的键:没有公开的常量,系统允许应用读它。 */
    private const val DEFAULT_SERVICE = "voice_recognition_service"
    private const val LABEL_MAX = 40

    /** 从一个应用申请的权限和各自的标记里读出录音权限的情况。 */
    fun of(requested: Array<String>?, flags: IntArray?): ServiceMic {
        val i = requested?.indexOf(Manifest.permission.RECORD_AUDIO) ?: -1
        if (i < 0) return ServiceMic.NotRequested
        val flag = flags?.getOrNull(i) ?: return ServiceMic.Unknown
        return if (flag and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) ServiceMic.Granted else ServiceMic.Missing
    }

    /**
     * 这次识别用的是哪个应用的服务。点名的就是它([namedPackage]);没点名的是系统设的默认识别服务([defaultPackage],
     * 没设、读不出来为 null);本机识别([onDevice])用的是系统内部指定的服务,从外面看不出是哪个。认不出来为 null,不拿登记的第一个去猜。
     */
    fun used(namedPackage: String?, onDevice: Boolean, defaultPackage: () -> String?): String? =
        if (onDevice) null else namedPackage ?: defaultPackage()

    /** 认定是识别服务缺录音权限时给出它;有一样不确定为 null(见类注释)。 */
    fun missing(selfGranted: Boolean, servicePackage: String?, state: (String) -> ServiceMic, label: (String) -> String): SpeechServiceTarget? {
        if (!selfGranted || servicePackage.isNullOrBlank()) return null
        if (state(servicePackage) != ServiceMic.Missing) return null
        return SpeechServiceTarget(servicePackage, label(servicePackage))
    }

    /** [missing] 接上系统:[namedPackage]、[onDevice] 是这一次识别实际用的入口。 */
    fun missing(context: Context, namedPackage: String?, onDevice: Boolean): SpeechServiceTarget? = missing(
        selfGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        servicePackage = used(namedPackage, onDevice) { defaultService(context)?.packageName },
        state = { state(context, it) },
        label = { label(context, it) },
    )

    /** 系统设的默认识别服务;没设、读不出来为 null。 */
    fun defaultService(context: Context): ComponentName? = try {
        Settings.Secure.getString(context.contentResolver, DEFAULT_SERVICE)?.takeIf { it.isNotBlank() }?.let(ComponentName::unflattenFromString)
    } catch (_: Exception) {
        null
    }

    fun state(context: Context, packageName: String): ServiceMic = try {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        of(info.requestedPermissions, info.requestedPermissionsFlags)
    } catch (_: Exception) {
        ServiceMic.Unknown
    }

    /** 系统里这个应用的名字(别的应用自己起的,只当普通文字显示,截短);取不到用包名。 */
    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        clean(pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()).ifEmpty { packageName }
    } catch (_: Exception) {
        packageName
    }

    internal fun clean(label: String): String = label.replace(Regex("\\s+"), " ").trim().take(LABEL_MAX)
}
