package dev.zolive.zviewer.data

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
    private val users = mutableMapOf<String, Int>()
    private val invalidated = mutableSetOf<String>()
    private var migrated = false

    init { require(target in 0 until limit) }

    @Synchronized fun acquire(key: String): Closeable {
        migrateCovers()
        users[key] = (users[key] ?: 0) + 1
        File(books, key).setLastModified(System.currentTimeMillis())
        return object : Closeable {
            private var closed = false
            override fun close() = synchronized(this@ReadingCache) {
                if (!closed) {
                    closed = true
                    val remaining = users.getValue(key) - 1
                    if (remaining == 0) {
                        users.remove(key)
                        if (key in invalidated) deleteUnused(setOf(key))
                    } else users[key] = remaining
                }
            }
        }
    }

    private data class Entry(val file: File, val key: String, val bytes: Long, val completedCover: Boolean = false)

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
        return books.listFiles().orEmpty().map { Entry(it, it.name, bytes(it)) } +
            listOf(covers, temporaryCovers).flatMap { directory ->
                directory.walkTopDown().filter { it.isFile }.map {
                    Entry(it, it.name.substringBefore('.').substringBefore('-'), it.length(), it.extension == "jpg")
                }.toList()
            }
    }

    @Synchronized fun size(): Long = entries().sumOf { it.bytes }

    @Synchronized fun reconcile(previousKeys: Set<String>, currentKeys: Set<String>, upgradedKeys: Map<String, String> = emptyMap()) {
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
        invalidated.removeAll(currentKeys)
        invalidated.addAll(previousKeys - currentKeys)
        deleteUnused(invalidated)
    }

    private fun deleteUnused(keys: Set<String>) {
        for (entry in entries()) {
            if (entry.key in keys && entry.key !in users) entry.file.deleteRecursively()
        }
    }

    @Synchronized fun trim(protectedKey: String? = null, limit: Long = this.limit, target: Long = this.target): Long {
        require(target in 0 until limit)
        val entries = entries()
        var total = entries.sumOf { it.bytes }
        if (total < limit) return 0
        val before = total
        for (entry in entries.sortedBy { it.file.lastModified() }) {
            if (total <= target) break
            if (entry.completedCover || entry.key == protectedKey || entry.key in users) continue
            entry.file.deleteRecursively()
            total -= entry.bytes - bytes(entry.file)
        }
        return before - total
    }

    @Synchronized fun clear(includeCovers: Boolean = false) {
        for (entry in entries()) {
            if (entry.key !in users && (includeCovers || !entry.completedCover)) entry.file.deleteRecursively()
        }
        books.mkdirs()
        covers.mkdirs()
        temporaryCovers.mkdirs()
    }
}
