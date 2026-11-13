package dev.zolive.zviewer.data

import java.io.Closeable
import java.io.File

internal class ReadingCache(
    root: File,
    private val limit: Long = 1536L * 1024 * 1024,
    private val target: Long = 1024L * 1024 * 1024,
) {
    val books = File(root, "books").apply { mkdirs() }
    val covers = File(root, "covers").apply { mkdirs() }
    private val users = mutableMapOf<String, Int>()

    init { require(target in 0 until limit) }

    @Synchronized fun acquire(key: String): Closeable {
        users[key] = (users[key] ?: 0) + 1
        File(books, key).setLastModified(System.currentTimeMillis())
        return object : Closeable {
            private var closed = false
            override fun close() = synchronized(this@ReadingCache) {
                if (!closed) {
                    closed = true
                    val remaining = users.getValue(key) - 1
                    if (remaining == 0) users.remove(key) else users[key] = remaining
                }
            }
        }
    }

    private data class Entry(val file: File, val key: String, val bytes: Long)

    private fun bytes(file: File): Long = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun entries(): List<Entry> =
        books.listFiles().orEmpty().map { Entry(it, it.name, bytes(it)) } +
            covers.walkTopDown().filter { it.isFile }.map {
                Entry(it, it.name.substringBefore('.').substringBefore('-'), it.length())
            }.toList()

    @Synchronized fun size(): Long = bytes(books) + bytes(covers)

    @Synchronized fun trim(protectedKey: String? = null): Long {
        val entries = entries()
        var total = entries.sumOf { it.bytes }
        if (total < limit) return 0
        val before = total
        for (entry in entries.sortedBy { it.file.lastModified() }) {
            if (total <= target) break
            if (entry.key == protectedKey || entry.key in users) continue
            entry.file.deleteRecursively()
            total -= entry.bytes - bytes(entry.file)
        }
        return before - total
    }

    @Synchronized fun clear() {
        for (entry in entries()) {
            if (entry.key !in users) entry.file.deleteRecursively()
        }
        books.mkdirs()
        covers.mkdirs()
    }
}
