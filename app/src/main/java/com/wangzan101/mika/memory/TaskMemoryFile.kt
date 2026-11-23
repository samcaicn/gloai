package com.wangzan101.mika.memory

import android.security.keystore.UserNotAuthenticatedException
import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStoreException
import java.security.ProviderException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 跨会话任务摘要加密成 `task_memory/task_memory.bin`(AES-GCM,密钥由调用方给,正式用 `KeystoreKeyCipher("mika-memory")`)。
 * 和记忆文件同一套做法:写临时文件后原子改名,解不开(密钥丢了)或内容坏了不覆盖、改名另存、从空开始。
 * 这是机器写的经验缓存,坏了静默从空开始,不弹提示。
 */
class TaskMemoryFile(
    dir: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long,
) : TaskMemoryPersistence {
    private val memDir = File(dir, "task_memory")
    private val file = File(memDir, FILE)
    private val tmp = File(memDir, "$FILE.tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TaskMemory.serializer())

    @Synchronized
    override fun load(): List<TaskMemory> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        return try {
            json.decodeFromString(serializer, cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // 密钥库一时不可用不等于文件坏了:抛出,下次再读,绝不另存
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) throw e
            moveAside()
            emptyList()
        }
    }

    @Synchronized
    override fun save(items: List<TaskMemory>) {
        memDir.mkdirs()
        val bytes = cipher.encrypt(json.encodeToString(serializer, items).toByteArray(Charsets.UTF_8))
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 「全部清除」:删任务摘要文件(含另存的坏文件)。密钥重建由调用方删别名后做。 */
    @Synchronized
    fun wipe() {
        memDir.listFiles()?.forEach { it.delete() }
    }

    private fun moveAside() {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(clock())
        var target = File(memDir, "$FILE.broken-$stamp")
        var n = 1
        while (target.exists()) target = File(memDir, "$FILE.broken-$stamp-${n++}")
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val FILE = "task_memory.bin"
    }
}
