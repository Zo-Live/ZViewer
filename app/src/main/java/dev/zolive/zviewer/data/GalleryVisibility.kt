package dev.zolive.zviewer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import java.io.IOException

internal fun Uri.documentTree(): Uri =
    DocumentsContract.buildTreeDocumentUri(authority!!, DocumentsContract.getTreeDocumentId(this))

internal class GalleryVisibility(context: Context) {
    private val resolver = context.contentResolver
    private val store = PreferenceStore(context)

    fun update(library: Uri?, scannedFolders: Set<Uri>?, hiddenFolders: Set<Uri>?): Boolean {
        val markers = store.galleryMarkers().associate { record ->
            record.substringBefore('\n').toUri() to record.substringAfter('\n').toUri()
        }.toMutableMap()
        fun save() {
            if (!store.saveGalleryMarkers(markers.mapTo(mutableSetOf()) { (folder, marker) -> "$folder\n$marker" })) {
                throw IOException("无法保存相册隐藏标记")
            }
        }
        val readableTrees = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(mutableSetOf()) { it.uri }
        var success = true
        for ((folder, marker) in markers.toMap()) {
            val tree = folder.documentTree()
            val currentLibrary = tree == library
            if (hiddenFolders?.contains(folder) ?: currentLibrary) continue
            if (!currentLibrary && tree !in readableTrees) continue
            try {
                val existing = try {
                    findMarker(folder)
                } catch (error: Exception) {
                    // 已离开扫描范围的目录仍保留标记归属，等目录重新可用时清理。
                    if (currentLibrary && scannedFolders != null && folder !in scannedFolders) continue
                    throw error
                }
                if (existing == marker && !DocumentsContract.deleteDocument(resolver, marker)) {
                    throw IOException("无法删除 .nomedia")
                }
                markers.remove(folder)
                save()
            } catch (error: Exception) {
                if (currentLibrary) success = false
                DiagnosticLog.error("Library", "恢复系统相册扫描失败", error)
            }
        }
        for (folder in hiddenFolders.orEmpty()) {
            try {
                if (findMarker(folder) != null) continue
                val marker = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", ".nomedia")
                    ?: throw IOException("无法创建 .nomedia")
                try {
                    if (findMarker(folder) != marker) throw IOException("提供器未保留 .nomedia 文件名")
                    markers[folder] = marker
                    save()
                } catch (error: Exception) {
                    markers.remove(folder)
                    runCatching { DocumentsContract.deleteDocument(resolver, marker) }
                    throw error
                }
            } catch (error: Exception) {
                success = false
                DiagnosticLog.error("Library", "隐藏系统相册图片失败", error)
            }
        }
        return success
    }

    private fun findMarker(folder: Uri): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
        val entries = resolver.query(children, columns, null, null, null) ?: throw IOException("无法读取书库目录")
        return entries.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == ".nomedia") {
                    return DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))
                }
            }
            null
        }
    }
}
