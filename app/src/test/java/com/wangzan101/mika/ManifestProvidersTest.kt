package com.wangzan101.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 清单里会让进程一启动就崩的配置,在电脑上先拦住(真机才会暴露的那一类)。 */
class ManifestProvidersTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun fileProvidersGrantUriPermissionsAndStayPrivate() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found from ${File(".").absolutePath}", manifest.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val providers = doc.getElementsByTagName("provider")
        var fileProviders = 0
        for (i in 0 until providers.length) {
            val p = providers.item(i) as Element
            val metas = p.getElementsByTagName("meta-data")
            val isFileProvider = (0 until metas.length).any {
                (metas.item(it) as Element).getAttributeNS(androidNs, "name") == "android.support.FILE_PROVIDER_PATHS"
            }
            if (!isFileProvider) continue
            fileProviders++
            val name = p.getAttributeNS(androidNs, "name")
            // FileProvider.attachInfo 在 grantUriPermissions 不为 true 时抛 SecurityException,进程起不来
            assertEquals("$name grantUriPermissions", "true", p.getAttributeNS(androidNs, "grantUriPermissions"))
            assertEquals("$name exported", "false", p.getAttributeNS(androidNs, "exported"))
        }
        assertTrue("expected at least one FileProvider", fileProviders > 0)
    }

    @Test
    fun helperHandoffProviderIsOnlyReachableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val providers = doc.getElementsByTagName("provider")
        val p = (0 until providers.length).map { providers.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".shell.HelperHandoffProvider" }
        // shell 要能调到它;普通 App 没有这个权限,连 provider 都拿不到
        assertEquals("true", p.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", p.getAttributeNS(androidNs, "permission"))
        assertEquals("\${applicationId}.helper", p.getAttributeNS(androidNs, "authorities"))
    }

    /** 电脑连接的服务对外开放只为让 adb shell 起它:普通 App 没有这个权限,起不了、也开关不了 USB 方式。 */
    @Test
    fun computerAccessServiceIsOnlyStartableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val s = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".mcp.McpService" }
        assertEquals("true", s.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", s.getAttributeNS(androidNs, "permission"))
        assertEquals("specialUse", s.getAttributeNS(androidNs, "foregroundServiceType"))
        // 没有 intent-filter:只能按组件名显式起
        assertEquals(0, s.getElementsByTagName("intent-filter").length)
    }

    /** 清单里登记的组件都按全名找得到类(搬了代码的入口留着同名的薄壳)。 */
    @Test
    fun everyComponentClassExists() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val names = listOf("application", "activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.size > 5)
        for (n in names) {
            val full = if (n.startsWith(".")) "com.wangzan101.mika$n" else n
            assertTrue("$full is not a class", runCatching { Class.forName(full, false, javaClass.classLoader) }.isSuccess)
        }
    }

    /**
     * 测试 APK 清单里的页面、服务运行在测试包自己的进程里,那里没有 Kotlin 标准库(和 App 重复的依赖不打进测试 APK):
     * 每个都要有同名的 Java 源文件。类名对不上、或写成 Kotlin,真机上一打开就崩,读屏只读到一块空屏。
     */
    @Test
    fun testApkComponentsAreJavaClassesWithMatchingNames() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/androidTest/AndroidManifest.xml"))
        val names = listOf("activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.isNotEmpty())
        for (n in names) {
            val path = "src/androidTest/java/${n.replace('.', '/')}"
            assertTrue("$n has no Java source at $path.java", File("$path.java").isFile)
        }
    }

    /**
     * 拍照附件走系统相机:声明用到相机、但不是必需(没有相机的设备照常安装)。这条声明不能删:
     * 有的系统按 APK 特征给应用分类,没有它时 Mika 可能被当成游戏、限制刷新率。
     */
    @Test
    fun cameraIsDeclaredAsOptionalFeature() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val features = doc.getElementsByTagName("uses-feature")
        val camera = (0 until features.length).map { features.item(it) as Element }
            .singleOrNull { it.getAttributeNS(androidNs, "name") == "android.hardware.camera" }
        assertTrue("uses-feature android.hardware.camera is missing", camera != null)
        assertEquals("false", camera!!.getAttributeNS(androidNs, "required"))
    }

    /**
     * 定位权限隐含「需要定位硬件」;网页定位不是必需功能,声明成非必需,没有 GPS 的设备也能装。
     */
    @Test
    fun locationIsDeclaredAsAnOptionalFeature() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName("uses-feature")
        val features = (0 until nodes.length).map { nodes.item(it) as Element }
            .associateBy { it.getAttributeNS(androidNs, "name") }
        for (name in listOf("android.hardware.location", "android.hardware.location.gps")) {
            val feature = features[name]
            assertTrue("uses-feature $name is missing", feature != null)
            assertEquals(name, "false", feature!!.getAttributeNS(androidNs, "required"))
        }
    }

    /**
     * 兼容性自检的测试页对外开放只为让内置助手(shell)开它:调用方必须持有 INTERACT_ACROSS_USERS_FULL,普通 App 打不开;
     * 没有 intent-filter,只能按组件名开;单独一个任务、不进最近任务。助手认的类名和清单一致。
     */
    @Test
    fun selfCheckTargetIsOnlyStartableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val a = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".selfcheck.SelfCheckTargetActivity" }
        assertEquals("true", a.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", a.getAttributeNS(androidNs, "permission"))
        assertEquals(0, a.getElementsByTagName("intent-filter").length)
        assertEquals(com.wangzan101.mika.cap.phone.shell.SystemIntents.OWN_PAGE_CLASS, "com.wangzan101.mika" + a.getAttributeNS(androidNs, "name"))
        assertEquals("true", a.getAttributeNS(androidNs, "excludeFromRecents"))
        assertTrue(a.hasAttributeNS(androidNs, "taskAffinity"))
        assertEquals("", a.getAttributeNS(androidNs, "taskAffinity"))
    }

    @Test fun capsuleTileIsBoundOnlyByAndroidQuickSettings() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val tile = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".capsule.CapsuleTileService" }
        assertEquals("false", tile.getAttributeNS(androidNs, "enabled"))
        assertEquals("true", tile.getAttributeNS(androidNs, "exported"))
        assertEquals("@string/capsule_tile_label", tile.getAttributeNS(androidNs, "label"))
        assertEquals("@drawable/ic_notification", tile.getAttributeNS(androidNs, "icon"))
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.getAttributeNS(androidNs, "permission"))
        val actions = tile.getElementsByTagName("action")
        assertEquals(1, actions.length)
        assertEquals("android.service.quicksettings.action.QS_TILE",
            (actions.item(0) as Element).getAttributeNS(androidNs, "name"))
    }

    @Test fun capsuleTileRuntimeKeepsTheSameSystemSafeIcon() {
        val service = File("src/main/java/com/wangzan101/mika/capsule/CapsuleTileService.kt").readText()
        assertTrue(service.contains("Icon.createWithResource(this, R.drawable.ic_notification)"))
        assertTrue(!service.contains("Icon.createWithResource(this, R.mipmap.ic_launcher)"))
    }

    @Test fun capsuleTileLongPressOpensMikaInsteadOfAppDetails() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".ui.MainActivity" }
        val filters = main.getElementsByTagName("intent-filter")
        val preferences = (0 until filters.length).map { filters.item(it) as Element }.single { filter ->
            val actions = filter.getElementsByTagName("action")
            (0 until actions.length).any {
                (actions.item(it) as Element).getAttributeNS(androidNs, "name") ==
                    "android.service.quicksettings.action.QS_TILE_PREFERENCES"
            }
        }
        val categories = preferences.getElementsByTagName("category")
        assertTrue((0 until categories.length).any {
            (categories.item(it) as Element).getAttributeNS(androidNs, "name") == "android.intent.category.DEFAULT"
        })
    }

    @Test fun mainActivityAcceptsSingleAndMultipleSharedFiles() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".ui.MainActivity" }
        val filters = main.getElementsByTagName("intent-filter")
        for (actionName in listOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE")) {
            val matching = (0 until filters.length).map { filters.item(it) as Element }.filter { filter ->
                val actions = filter.getElementsByTagName("action")
                (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(androidNs, "name") == actionName }
            }
            assertEquals("Expected one intent-filter for $actionName", 1, matching.size)
            val filter = matching.single()
            val categories = filter.getElementsByTagName("category")
            assertTrue((0 until categories.length).any {
                (categories.item(it) as Element).getAttributeNS(androidNs, "name") == "android.intent.category.DEFAULT"
            })
            val data = filter.getElementsByTagName("data")
            assertTrue((0 until data.length).any {
                (data.item(it) as Element).getAttributeNS(androidNs, "mimeType") == "*/*"
            })
        }
    }

    /** 内置助手进程按这个全名启动。 */
    @Test
    fun helperEntryClassExists() {
        val c = Class.forName(com.wangzan101.mika.core.device.shell.HelperHandoff.HELPER_CLASS, false, javaClass.classLoader)
        assertTrue(c.methods.any { it.name == "main" && java.lang.reflect.Modifier.isStatic(it.modifiers) })
    }
}
