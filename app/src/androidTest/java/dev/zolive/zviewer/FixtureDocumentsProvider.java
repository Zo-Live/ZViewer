package dev.zolive.zviewer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class FixtureDocumentsProvider extends DocumentsProvider {
    private File base;
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS
    };

    @Override public boolean onCreate() { return true; }

    private synchronized File base() throws FileNotFoundException {
        if (base == null) {
            base = new File(getContext().getFilesDir(), "fixtures");
            base.mkdirs();
            try {
                copyAssets("formats");
                copyAssets("书库");
            } catch (IOException error) {
                throw new FileNotFoundException(error.toString());
            }
        }
        return base;
    }

    private void copyAssets(String path) throws IOException {
        String[] children = getContext().getAssets().list(path);
        if (children != null && children.length > 0) {
            for (String child : children) copyAssets(path + "/" + child);
        } else {
            File destination = new File(base, path);
            destination.getParentFile().mkdirs();
            try (InputStream input = getContext().getAssets().open(path);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
    }

    private File file(String id) throws FileNotFoundException {
        File directory = base();
        File file = id.equals("root") ? directory : new File(directory, id.substring(5));
        try {
            if (!file.getCanonicalPath().equals(directory.getCanonicalPath()) &&
                !file.getCanonicalPath().startsWith(directory.getCanonicalPath() + "/"))
                throw new FileNotFoundException("非法路径");
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        return file;
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        File file = file(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(file.getName()); break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add(file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream"); break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row.add(file.lastModified()); break;
                case DocumentsContract.Document.COLUMN_SIZE: row.add(file.length()); break;
                case DocumentsContract.Document.COLUMN_FLAGS: row.add(0); break;
                default: row.add(null);
            }
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            switch (column) {
                case DocumentsContract.Root.COLUMN_ROOT_ID:
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID: row.add("root"); break;
                case DocumentsContract.Root.COLUMN_TITLE: row.add("ZViewer 测试文件"); break;
                case DocumentsContract.Root.COLUMN_FLAGS: row.add(DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD); break;
                default: row.add(null);
            }
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] children = file(parentDocumentId).listFiles();
        if (children != null) for (File child : children) addDocument(cursor, parentDocumentId + "/" + child.getName());
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + "/");
    }
}
