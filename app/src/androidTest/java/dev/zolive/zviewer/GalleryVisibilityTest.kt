package dev.zolive.zviewer

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.GalleryVisibility
import dev.zolive.zviewer.data.PreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryVisibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PreferenceStore(context)
    private val gallery = GalleryVisibility(context)
    private val library = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root")

    @Test fun inaccessibleOldLibraryMarkerDoesNotFailCurrentUpdate() {
        val oldLibrary = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root/旧书库")
        withOwnedMarker(oldLibrary, "root/旧书库") { record ->
            assertTrue(gallery.update(library, emptySet(), emptySet()))
            assertEquals(setOf(record), store.galleryMarkers())
        }
    }

    @Test fun unavailableChildOutsideCurrentScanDoesNotFailCurrentUpdate() {
        val childId = "root/旧图片集"
        val child = DocumentsContract.buildDocumentUriUsingTree(library, childId)
        val root = DocumentsContract.buildDocumentUriUsingTree(library, "root")
        val provider = Uri.parse("content://${library.authority}")
        withOwnedMarker(library, childId) { record ->
            context.contentResolver.call(provider, "failChildren", childId, null)
            try {
                assertTrue(gallery.update(library, setOf(root), emptySet()))
                assertEquals(setOf(record), store.galleryMarkers())
                assertFalse(gallery.update(library, setOf(root, child), emptySet()))
            } finally {
                context.contentResolver.call(provider, "failChildren", null, null)
            }
        }
    }

    private fun withOwnedMarker(tree: Uri, folderId: String, test: (String) -> Unit) {
        val original = store.galleryMarkers()
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        val marker = DocumentsContract.buildDocumentUriUsingTree(tree, "$folderId/.nomedia")
        val record = "$folder\n$marker"
        assertTrue(store.saveGalleryMarkers(setOf(record)))
        try {
            test(record)
        } finally {
            store.saveGalleryMarkers(original)
        }
    }
}
