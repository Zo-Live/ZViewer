package dev.zolive.zviewer.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException

internal data class SettingsBackup(val settings: ReaderSettings, val library: String?) {
    fun write(resolver: ContentResolver, uri: Uri) {
        val values = JSONObject()
        for ((key, value) in settingsValues(settings)) {
            values.put(key, if (value is Set<*>) JSONArray(value) else value ?: JSONObject.NULL)
        }
        val json = JSONObject()
            .put("format", "zviewer-settings")
            .put("version", 1)
            .put("library_uri", library ?: JSONObject.NULL)
            .put("settings", values)
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException()
        stream.bufferedWriter().use { it.write(json.toString(2)) }
    }

    companion object {
        fun read(resolver: ContentResolver, uri: Uri): SettingsBackup {
            val stream = resolver.openInputStream(uri) ?: throw IOException()
            val source = stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                buildString {
                    while (true) {
                        val count = reader.read(buffer)
                        if (count == -1) break
                        require(length + count <= 64 * 1024)
                        append(buffer, 0, count)
                    }
                }
            }
            val tokens = JSONTokener(source)
            val json = tokens.nextValue() as JSONObject
            require(tokens.nextClean() == '\u0000')
            require(json.get("format") == "zviewer-settings" && json.get("version") == 1)
            val library = json.get("library_uri").let { if (it === JSONObject.NULL) null else it as String }
            val values = json.getJSONObject("settings")
            val settings = readSettings { key, default ->
                val value = values.get(key)
                when (default) {
                    is Boolean -> value as Boolean
                    is Int -> value as Int
                    is Long -> when (value) {
                        is Int -> value.toLong()
                        else -> value as Long
                    }
                    is Set<*> -> (value as JSONArray).let { array ->
                        (0 until array.length()).mapTo(mutableSetOf()) { array.get(it) as String }
                    }
                    null -> if (value === JSONObject.NULL) null else value as String
                    else -> value as String
                }
            }
            require(settings.theme in setOf("system", "light", "dark"))
            require(settings.accent in 0L..0xFFFFFFFFL)
            return SettingsBackup(settings, library)
        }
    }
}

internal enum class SettingsDirectory(val needsWrite: Boolean) {
    Library(false), Log(true);

    fun change(context: Context, current: String?, imported: String?): SettingsDirectoryChange? {
        if (current == imported) return null
        if (imported == null) {
            // 日志的空值代表应用专属目录，书库的空值代表尚未选择。
            return if (this == Log) SettingsDirectoryChange(this, current, null) else null
        }
        val tree = treeUri(imported) ?: return null
        if (current != null && tree == treeUri(current)) return null
        if (!directoryExists(context, tree)) return null
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        val needsGrant = grant?.isReadPermission != true || (needsWrite && !grant.isWritePermission)
        return SettingsDirectoryChange(this, current, tree, needsGrant)
    }

    fun takePermission(resolver: ContentResolver, uri: Uri, hideGalleryImages: Boolean) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, if (needsWrite) read or write else read)
        if (this == Library && hideGalleryImages) {
            runCatching { resolver.takePersistableUriPermission(uri, write) }
        }
    }
}

internal data class SettingsDirectoryChange(
    val directory: SettingsDirectory,
    val current: String?,
    val target: Uri?,
    val needsGrant: Boolean = false,
)

internal data class SettingsImport(
    val backup: SettingsBackup,
    val changes: List<SettingsDirectoryChange>,
    val awaitingDirectory: Boolean = false,
)

private fun treeUri(value: String): Uri? {
    val uri = Uri.parse(value)
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty() ||
        uri.pathSegments.size != 2 || !DocumentsContract.isTreeUri(uri)
    ) return null
    return uri.documentTree()
}

private fun directoryExists(context: Context, tree: Uri): Boolean {
    val resolver = context.contentResolver
    val documentId = DocumentsContract.getTreeDocumentId(tree)
    val type = DocumentsContract.Document.COLUMN_MIME_TYPE
    val candidateTrees = listOf(tree) + resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.uri.authority == tree.authority }
        .map { it.uri }
    for (candidate in candidateTrees.distinct()) {
        val document = DocumentsContract.buildDocumentUriUsingTree(candidate, documentId)
        val exists = runCatching {
            resolver.query(document, arrayOf(type), null, null, null)?.use {
                it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            } == true
        }.getOrDefault(false)
        if (exists) return true
    }
    // 外部存储提供程序的文档 ID 含卷与相对路径，可在授权之前检查目录是否存在。
    if (tree.authority != "com.android.externalstorage.documents" || ':' !in documentId) return false
    val volume = documentId.substringBefore(':')
    val path = documentId.substringAfter(':')
    val root = if (volume == "primary") {
        Environment.getExternalStorageDirectory()
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(StorageManager::class.java).storageVolumes
            .firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.directory
    } else null
    if (root == null || path.split('/').any { it == ".." || it == "." }) return false
    return File(root, path).isDirectory
}
