package dev.zolive.zviewer.data

import kotlinx.coroutines.sync.Mutex
import java.io.Closeable
import java.io.File

internal class ReadingCache(
    root: File,
    persistentRoot: File,
    private val limit: Long = 1536L * 1024 * 1024,
    private val target: Long = 1024L * 1024 * 1024,
) {
    val books = File(root, "books").apply { mkdirs() }
    val covers = File(persistentRoot, "covers").apply { mkdirs() }
    val temporaryCovers = File(root, "covers").apply { mkdirs() }
    private val maintenanceLock = Any()
    private val accessLock = Any()
    private val fileLocks = Array(64) { Mutex() }
    private val users = mutableMapOf<String, Int>()
    private val invalidated = mutableSetOf<String>()
    private val pendingClear = mutableMapOf<String, Boolean>()
    @Volatile private var migrated = false

    init { require(target in 0 until limit) }

    fun fileLock(key: String): Mutex = fileLocks[(key.hashCode() and Int.MAX_VALUE) % fileLocks.size]

    fun acquire(key: String): Closeable {
        if (!migrated) synchronized(maintenanceLock) { migrateCovers() }
        synchronized(accessLock) {
            users[key] = (users[key] ?: 0) + 1
            File(books, key).setLastModified(System.currentTimeMillis())
        }
        return object : Closeable {
            private var closed = false
            override fun close() = synchronized(accessLock) {
                if (!closed) {
                    closed = true
                    val remaining = users.getValue(key) - 1
                    if (remaining == 0) {
                        users.remove(key)
                        val includeCovers = pendingClear.remove(key)
                        if (key in invalidated || includeCovers != null) {
                            deleteBookFiles(key, includeCovers == true || key in invalidated)
                        }
                    } else users[key] = remaining
                }
            }
        }
    }

    private data class Entry(val file: File, val key: String, val modified: Long, val completedCover: Boolean = false)

    private fun coverKey(file: File) = file.name.substringBefore('.').substringBefore('-')

    // 只迁移已完成的图片；源媒体副本与中断写入仍留在系统缓存目录中等待回收。
    private fun migrateCovers() {
        if (migrated) return
        var complete = true
        temporaryCovers.walkTopDown().filter { it.isFile && it.extension == "jpg" }.forEach { source ->
            val destination = File(covers, source.relativeTo(temporaryCovers).path)
            destination.parentFile!!.mkdirs()
            if (destination.isFile) {
                if (!source.delete()) complete = false
            } else if (!source.renameTo(destination)) complete = false
        }
        migrated = complete
    }

    private fun bytes(file: File): Long = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun entries(): List<Entry> {
        migrateCovers()
        return books.listFiles().orEmpty().map { Entry(it, it.name, it.lastModified()) } +
            listOf(covers, temporaryCovers).flatMap { directory ->
                directory.walkTopDown().filter { it.isFile }.map {
                    Entry(it, coverKey(it), it.lastModified(), it.extension == "jpg")
                }.toList()
            }
    }

    fun size(): Long = synchronized(maintenanceLock) { entries().sumOf { bytes(it.file) } }

    fun reconcile(
        previousKeys: Set<String>,
        currentKeys: Set<String>,
        upgradedKeys: Map<String, String> = emptyMap(),
        keepOnlyCurrent: Boolean = false,
    ) = synchronized(maintenanceLock) {
        // 旧版未保存目录成员签名；原有大小和修改时间未变时沿用封面，建立新版签名基线。
        if (upgradedKeys.isNotEmpty()) for (entry in entries()) {
            val newKey = upgradedKeys[entry.key] ?: continue
            if (!entry.completedCover) continue
            val directory = if (entry.file.parentFile?.name == "frames") File(covers, "frames") else covers
            val destination = File(directory, newKey + entry.file.name.removePrefix(entry.key))
            if (destination.isFile) continue
            val temporary = File(directory, "${destination.name}.tmp")
            try {
                directory.mkdirs()
                entry.file.copyTo(temporary, overwrite = true)
                temporary.setLastModified(entry.file.lastModified())
                if (!temporary.renameTo(destination)) throw java.io.IOException("封面缓存迁移失败。")
            } finally { temporary.delete() }
        }
        val obsolete = previousKeys - currentKeys
        synchronized(accessLock) {
            invalidated.removeAll(currentKeys)
            invalidated.addAll(obsolete)
            if (keepOnlyCurrent) invalidated.addAll(users.keys - currentKeys)
        }
        if (!keepOnlyCurrent && obsolete.isEmpty()) return@synchronized
        for (entry in entries()) {
            if (if (keepOnlyCurrent) entry.key in currentKeys else entry.key !in obsolete) continue
            synchronized(accessLock) {
                if (entry.key !in users) entry.file.deleteRecursively()
                else invalidated.add(entry.key)
            }
        }
    }

    private fun deleteBookFiles(key: String, includeCovers: Boolean) {
        File(books, key).deleteRecursively()
        for (directory in listOf(covers, File(covers, "frames"), temporaryCovers, File(temporaryCovers, "frames"))) {
            directory.listFiles { file -> file.isFile && coverKey(file) == key &&
                (includeCovers || file.extension != "jpg") }.orEmpty().forEach { it.delete() }
        }
    }

    fun trim(protectedKey: String? = null, limit: Long = this.limit, target: Long = this.target): Long = synchronized(maintenanceLock) {
        require(target in 0 until limit)
        val entries = entries().map { it to bytes(it.file) }
        var total = entries.sumOf { it.second }
        if (total < limit) return@synchronized 0L
        val before = total
        for ((entry, size) in entries.sortedBy { it.first.modified }) {
            if (total <= target) break
            if (entry.completedCover || entry.key == protectedKey) continue
            val remaining = synchronized(accessLock) {
                if (entry.key in users || entry.file.lastModified() != entry.modified) null
                else if (entry.file.deleteRecursively()) 0L else bytes(entry.file)
            }
            if (remaining != null) total -= size - remaining
        }
        before - total
    }

    fun clear(includeCovers: Boolean = false, afterUse: Boolean = false) = synchronized(maintenanceLock) {
        if (afterUse) synchronized(accessLock) {
            for (key in users.keys) pendingClear[key] = includeCovers || pendingClear[key] == true
        }
        for (entry in entries()) {
            if (!includeCovers && entry.completedCover) continue
            synchronized(accessLock) {
                if (entry.key !in users) entry.file.deleteRecursively()
                else if (afterUse) pendingClear[entry.key] = includeCovers || pendingClear[entry.key] == true
            }
        }
        books.mkdirs()
        covers.mkdirs()
        temporaryCovers.mkdirs()
        Unit
    }
}
