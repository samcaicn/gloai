package com.wangzan101.mika.memory

import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryStep
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class TaskMemoryFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val now = 1_761_200_000_000L
    private fun file(k: javax.crypto.SecretKey = key) = TaskMemoryFile(tmp.root, AesGcmKeyCipher { k }) { now }
    private val bin get() = File(tmp.root, "task_memory/task_memory.bin")
    private fun memDir() = File(tmp.root, "task_memory")

    private val items = listOf(
        TaskMemory("a", "帮妈妈买药", "已下单", listOf("com.taobao"), listOf("example.com"), 1L, listOf(TaskMemoryStep(2L, "打开", "药房首页"))),
        TaskMemory("b", "订酒店", "没办成", emptyList(), emptyList(), 3L, emptyList()),
    )

    private fun brokenFiles() = memDir().listFiles { f -> f.name.startsWith("task_memory.bin.broken-") }.orEmpty().toList()

    @Test fun roundTrip() {
        assertEquals(emptyList<TaskMemory>(), file().load()) // 没有文件 = 空,不算坏
        file().save(items)
        assertEquals(items, file().load())
    }

    @Test fun fileHasNoPlaintext() {
        file().save(items)
        val bytes = bin.readBytes()
        val needle = "买药".toByteArray(Charsets.UTF_8)
        assertFalse(bytes.indices.any { i -> i + needle.size <= bytes.size && needle.indices.all { bytes[i + it] == needle[it] } })
    }

    @Test fun undecryptableMovedAsideNotOverwritten() {
        file().save(items)
        val original = bin.readBytes()
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val f = file(other)
        assertEquals(emptyList<TaskMemory>(), f.load())
        val broken = brokenFiles().single()
        val stamp = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).format(now)
        assertEquals("task_memory.bin.broken-$stamp", broken.name)
        assertArrayEquals(original, broken.readBytes())
        assertFalse(bin.exists())
        // 之后的写入不碰另存的文件
        f.save(items.take(1))
        assertArrayEquals(original, broken.readBytes())
        assertEquals(items.take(1), file(other).load())
    }

    @Test fun transientKeystoreErrorRethrownFileKept() {
        file().save(items)
        val original = bin.readBytes()
        val busy = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = throw java.security.KeyStoreException("busy")
            override fun decrypt(data: ByteArray) = throw java.security.KeyStoreException("busy")
        }
        val f = TaskMemoryFile(tmp.root, busy) { now }
        assertTrue(runCatching { f.load() }.exceptionOrNull() is java.security.KeyStoreException)
        assertTrue(brokenFiles().isEmpty())
        assertArrayEquals(original, bin.readBytes())
        assertEquals(items, file().load())
    }

    @Test fun wipeRemovesFiles() {
        file().save(items)
        file(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()).load() // 产生一份另存
        file().save(items)
        val f = file()
        f.wipe()
        assertTrue(memDir().list().orEmpty().isEmpty())
        assertEquals(emptyList<TaskMemory>(), file().load())
    }
}
