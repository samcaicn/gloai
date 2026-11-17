@file:Suppress("UNCHECKED_CAST", "USELESS_CAST", "INAPPLICABLE_JVM_NAME", "UNUSED_ANONYMOUS_PARAMETER", "NAME_SHADOWING", "UNNECESSARY_NOT_NULL_ASSERTION")
package uts.sdk.modules.LGPlayer
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.MediaPlayer.OnErrorListener
import android.media.PlaybackParams
import android.os.PowerManager
import io.dcloud.uniapp.*
import io.dcloud.uniapp.extapi.*
import io.dcloud.uts.*
import io.dcloud.uts.Map
import io.dcloud.uts.Set
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import io.dcloud.uts.UTSAndroid
interface PlayOptions {
    var volume: Number?
    var rate: Number?
    var loop: Boolean?
}
val ALL_IDS = _uA(
    "script",
    "music",
    "realvoice",
    "audioscript",
    "video",
    "keyword",
    "peng",
    "overlay"
)
val players = Map<String, MediaPlayer>()
val doneCbs = Map<String, () -> Unit>()
val errCbs = Map<String, (what: Number, extra: Number) -> Unit>()
var backgroundEnabled = false
open class PlayCompletionListener : OnCompletionListener {
    open var id: String = ""
    constructor(id: String){
        this.id = id
    }
    override fun onCompletion(mp: MediaPlayer?): Unit {
        val cb = doneCbs.get(this.id)
        if (cb != null) {
            try {
                cb()
            }
             catch (e: Throwable) {
                console.log("LG-Player 完成回调失败: " + e)
            }
        }
    }
}
open class PlayErrorListener : OnErrorListener {
    open var id: String = ""
    constructor(id: String){
        this.id = id
    }
    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        reportError(this.id, what, extra, "")
        return true
    }
}
fun reportError(id: String, what: Number, extra: Number, msg: String): Unit {
    var line = "LG-Player 播放错误: id=" + id + " what=" + what + " extra=" + extra
    if (msg != "") {
        line = line + " err=" + msg
    }
    console.log(line)
    val cb = errCbs.get(id)
    if (cb != null) {
        try {
            cb(what, extra)
        }
         catch (e: Throwable) {
            console.log("LG-Player 错误回调失败: " + e)
        }
    }
}
fun createPlayer(id: String): Unit {
    release(id)
    val mp = MediaPlayer()
    mp.setOnErrorListener(PlayErrorListener(id))
    players.set(id, mp)
}
fun play(id: String, url: String, options: PlayOptions? = null): Unit {
    if (!players.has(id)) {
        createPlayer(id)
    }
    val mp = players.get(id)
    if (mp == null) {
        return
    }
    var volume: Number = 1.0
    var loop = false
    if (options != null) {
        if (options.volume != null) {
            volume = options.volume!!
        }
        if (options.loop != null) {
            loop = options.loop!!
        }
    }
    try {
        mp.reset()
        mp.setDataSource(url)
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mp.setVolume(volume.toFloat(), volume.toFloat())
        mp.setLooping(loop)
        if (backgroundEnabled) {
            try {
                mp.setWakeMode(UTSAndroid.getAppContext(), PowerManager.PARTIAL_WAKE_LOCK)
            }
             catch (e: Throwable) {
                console.log("LG-Player setWakeMode 失败: " + e)
            }
        }
        mp.prepare()
        if (options != null && options.rate != null) {
            try {
                val pp = PlaybackParams()
                pp.setSpeed(options.rate!!.toFloat())
                mp.setPlaybackParams(pp)
            }
             catch (e: Throwable) {
                console.log("LG-Player rate 设置失败: " + e)
            }
        }
        mp.start()
    }
     catch (e: Throwable) {
        reportError(id, -1, 0, "" + e)
    }
}
fun isPlayingSafe(mp: MediaPlayer): Boolean {
    try {
        return mp.isPlaying()
    }
     catch (e: Throwable) {
        return false
    }
}
fun pause(id: String): Unit {
    val mp = players.get(id)
    if (mp != null && isPlayingSafe(mp)) {
        mp.pause()
    }
}
fun resume(id: String): Unit {
    val mp = players.get(id)
    if (mp != null && !isPlayingSafe(mp)) {
        try {
            mp.start()
        }
         catch (e: Throwable) {}
    }
}
fun stop(id: String): Unit {
    val mp = players.get(id)
    if (mp != null) {
        try {
            mp.stop()
        }
         catch (e: Throwable) {}
    }
}
fun setVolume(id: String, volume: Number): Unit {
    val mp = players.get(id)
    if (mp != null) {
        mp.setVolume(volume.toFloat(), volume.toFloat())
    }
}
fun seek(id: String, ms: Number): Unit {
    val mp = players.get(id)
    if (mp != null) {
        mp.seekTo(ms.toInt())
    }
}
fun isPlaying(id: String): Boolean {
    val mp = players.get(id)
    return mp != null && isPlayingSafe(mp)
}
fun getDuration(id: String): Number {
    val mp = players.get(id)
    if (mp == null) {
        return 0
    }
    try {
        return mp.getDuration()
    }
     catch (e: Throwable) {
        return 0
    }
}
fun getCurrentPosition(id: String): Number {
    val mp = players.get(id)
    if (mp == null) {
        return 0
    }
    try {
        return mp.getCurrentPosition()
    }
     catch (e: Throwable) {
        return 0
    }
}
fun requestAudioFocus(): Boolean {
    return true
}
fun enableBackground(enable: Boolean): Unit {
    backgroundEnabled = enable
    if (!enable) {
        console.log("LG-Player 后台保活关闭：wake lock 随实例 release 释放")
        return
    }
    run {
        var i: Number = 0
        while(i < ALL_IDS.length){
            val mp = players.get(ALL_IDS[i])
            if (mp == null) {
                i++
                continue
            }
            try {
                mp.setWakeMode(UTSAndroid.getAppContext(), PowerManager.PARTIAL_WAKE_LOCK)
            }
             catch (e: Throwable) {
                console.log("LG-Player setWakeMode 失败: " + e)
            }
            i++
        }
    }
}
fun release(id: String): Unit {
    doneCbs.`delete`(id)
    errCbs.`delete`(id)
    val mp = players.get(id)
    if (mp == null) {
        return
    }
    try {
        mp.stop()
    }
     catch (e: Throwable) {}
    mp.release()
    players.`delete`(id)
}
fun releaseAll(): Unit {
    run {
        var i: Number = 0
        while(i < ALL_IDS.length){
            release(ALL_IDS[i])
            i++
        }
    }
}
fun setOnCompletion(id: String, cb: (() -> Unit)?): Unit {
    if (cb == null) {
        doneCbs.`delete`(id)
        return
    }
    if (!players.has(id)) {
        createPlayer(id)
    }
    val mp = players.get(id)
    if (mp != null) {
        mp.setOnCompletionListener(PlayCompletionListener(id))
    }
    doneCbs.set(id, cb)
}
fun setOnError(id: String, cb: ((what: Number, extra: Number) -> Unit)?): Unit {
    if (cb == null) {
        errCbs.`delete`(id)
        return
    }
    if (!players.has(id)) {
        createPlayer(id)
    }
    errCbs.set(id, cb)
}
fun createPlayerByJs(id: String): Unit {
    return createPlayer(id)
}
fun playByJs(id: String, url: String, options: PlayOptions? = null): Unit {
    return play(id, url, options)
}
fun pauseByJs(id: String): Unit {
    return pause(id)
}
fun resumeByJs(id: String): Unit {
    return resume(id)
}
fun stopByJs(id: String): Unit {
    return stop(id)
}
fun setVolumeByJs(id: String, volume: Number): Unit {
    return setVolume(id, volume)
}
fun seekByJs(id: String, ms: Number): Unit {
    return seek(id, ms)
}
fun isPlayingByJs(id: String): Boolean {
    return isPlaying(id)
}
fun getDurationByJs(id: String): Number {
    return getDuration(id)
}
fun getCurrentPositionByJs(id: String): Number {
    return getCurrentPosition(id)
}
fun requestAudioFocusByJs(): Boolean {
    return requestAudioFocus()
}
fun enableBackgroundByJs(enable: Boolean): Unit {
    return enableBackground(enable)
}
fun releaseByJs(id: String): Unit {
    return release(id)
}
fun releaseAllByJs(): Unit {
    return releaseAll()
}
fun setOnCompletionByJs(id: String, cb: UTSCallback?): Unit {
    return setOnCompletion(id, if (cb == null) {
        null
    } else {
        if (cb.fnJS != null) {
            cb.fnJS
        } else {
            cb.fnJS = fun(){
                cb()
            }
            cb.fnJS
        }
         as () -> Unit
    }
    )
}
fun setOnErrorByJs(id: String, cb: UTSCallback?): Unit {
    return setOnError(id, if (cb == null) {
        null
    } else {
        if (cb.fnJS != null) {
            cb.fnJS
        } else {
            cb.fnJS = fun(what: Number, extra: Number){
                cb(what, extra)
            }
            cb.fnJS
        }
         as (what: Number, extra: Number) -> Unit
    }
    )
}
