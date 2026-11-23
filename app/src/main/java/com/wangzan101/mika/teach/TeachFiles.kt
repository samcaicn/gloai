package com.wangzan101.mika.teach

import com.wangzan101.mika.core.learn.teach.TeachCodec
import com.wangzan101.mika.core.learn.teach.TeachSession
import com.wangzan101.mika.core.learn.teach.TeachSkill
import com.wangzan101.mika.core.learn.teach.TeachTrace
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 一份示范轨迹，只留合并后的步骤。放弃、拒绝、删除或收下后整个目录删掉。 */
class TeachFiles(dir: File) {
    private val root = File(dir, "teach")
    private val file = File(root, "session.json")

    @Synchronized fun load(): TeachSession? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            delete()
            return null
        }
        val session = try {
            TeachCodec.decode(text)
        } catch (e: Exception) {
            delete()
            return null
        }
        if (!TeachSkill.ID.matches(session.id)) {
            delete()
            return null
        }
        val redacted = TeachTrace.redactStored(session)
        if (redacted != session) runCatching { save(redacted) }
        return redacted
    }

    @Synchronized fun save(session: TeachSession) {
        if (!TeachSkill.ID.matches(session.id)) return
        root.mkdirs()
        val tmp = File(root, "session.json.tmp")
        tmp.writeText(TeachCodec.encode(session))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun delete() {
        root.deleteRecursively()
    }
}
