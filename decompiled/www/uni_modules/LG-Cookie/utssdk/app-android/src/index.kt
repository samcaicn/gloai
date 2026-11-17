@file:Suppress("UNCHECKED_CAST", "USELESS_CAST", "INAPPLICABLE_JVM_NAME", "UNUSED_ANONYMOUS_PARAMETER", "NAME_SHADOWING", "UNNECESSARY_NOT_NULL_ASSERTION")
package uts.sdk.modules.LGCookie
import android.webkit.CookieManager
import io.dcloud.uniapp.*
import io.dcloud.uniapp.extapi.*
import io.dcloud.uts.*
import io.dcloud.uts.Map
import io.dcloud.uts.Set
import io.dcloud.uts.UTSAndroid
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
fun cm(): CookieManager {
    return CookieManager.getInstance()
}
fun setAcceptCookie(accept: Boolean): Unit {
    try {
        cm().setAcceptCookie(accept)
    }
     catch (e: Throwable) {
        console.log("LG-Cookie setAcceptCookie 失败: " + e)
    }
}
fun setCookie(url: String, cookie: String): Unit {
    try {
        cm().setCookie(url, cookie)
    }
     catch (e: Throwable) {
        console.log("LG-Cookie setCookie 失败: " + e)
    }
}
fun getCookie(url: String): String {
    try {
        val v = cm().getCookie(url)
        return if (v == null) {
            ""
        } else {
            v
        }
    }
     catch (e: Throwable) {
        console.log("LG-Cookie getCookie 失败: " + e)
        return ""
    }
}
fun removeAllCookie(): Unit {
    try {
        cm().removeAllCookie()
    }
     catch (e: Throwable) {
        console.log("LG-Cookie removeAllCookie 失败: " + e)
    }
}
fun flush(): Unit {
    try {
        cm().flush()
    }
     catch (e: Throwable) {
        console.log("LG-Cookie flush 失败: " + e)
    }
}
fun injectCookies(domains: UTSArray<String>, cookieString: String, append: String): Unit {
    try {
        val manager = cm()
        manager.setAcceptCookie(true)
        manager.removeAllCookie()
        if (cookieString == null || cookieString.length === 0) {
            manager.flush()
            return
        }
        val arr = cookieString.split(";")
        run {
            var i: Number = 0
            while(i < arr.length){
                val item = arr[i].trim()
                if (item.length === 0) {
                    i++
                    continue
                }
                val value = if (append != null && append.length > 0) {
                    item + append
                } else {
                    item
                }
                run {
                    var j: Number = 0
                    while(j < domains.length){
                        try {
                            manager.setCookie(domains[j], value)
                        }
                         catch (e: Throwable) {
                            console.log("LG-Cookie setCookie 单条失败: " + e)
                        }
                        j++
                    }
                }
                i++
            }
        }
        manager.flush()
    }
     catch (e: Throwable) {
        console.log("LG-Cookie injectCookies 失败: " + e)
    }
}
fun setAcceptCookieByJs(accept: Boolean): Unit {
    return setAcceptCookie(accept)
}
fun setCookieByJs(url: String, cookie: String): Unit {
    return setCookie(url, cookie)
}
fun getCookieByJs(url: String): String {
    return getCookie(url)
}
fun removeAllCookieByJs(): Unit {
    return removeAllCookie()
}
fun flushByJs(): Unit {
    return flush()
}
fun injectCookiesByJs(domains: UTSArray<String>, cookieString: String, append: String): Unit {
    return injectCookies(domains, cookieString, append)
}
