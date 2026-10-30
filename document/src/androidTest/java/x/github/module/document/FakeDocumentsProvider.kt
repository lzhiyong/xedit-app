package x.github.module.document

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal [DocumentsProvider] backed by a directory in the test app's cache.
 *
 * Document IDs are `root` for the root directory and `root/<relative path>` for everything
 * below it, so [isChildDocument] is a prefix check. [queryCount] counts every document and
 * children query, which lets tests assert how many provider round trips an operation costs.
 */
class FakeDocumentsProvider : DocumentsProvider() {

    private val root: File
        get() = rootDir(requireNotNull(context))

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            .add(Root.COLUMN_TITLE, "Fake documents")
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        queryCount.incrementAndGet()
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(cursor, documentId, fileFor(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queryCount.incrementAndGet()
        val children = fileFor(parentDocumentId).listFiles()
            ?: throw FileNotFoundException("Not a readable directory: $parentDocumentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        children.sortedBy { it.name }.forEach { addRow(cursor, idFor(it), it) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val target = File(fileFor(parentDocumentId), displayName)
        val created = try {
            if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        } catch (e: IOException) {
            false
        }
        if (!created) throw FileNotFoundException("Cannot create $target")
        return idFor(target)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = fileFor(documentId)
        val target = File(source.parentFile, displayName)
        if (target.exists() || !source.renameTo(target)) {
            throw FileNotFoundException("Cannot rename $source to $displayName")
        }
        return idFor(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (!file.exists() || !file.deleteRecursively()) {
            throw FileNotFoundException("Cannot delete $documentId")
        }
    }

    private fun fileFor(documentId: String): File = when {
        documentId == ROOT_ID -> root
        documentId.startsWith("$ROOT_ID/") -> File(root, documentId.removePrefix("$ROOT_ID/"))
        else -> throw FileNotFoundException("Unknown document $documentId")
    }

    private fun idFor(file: File): String =
        if (file == root) ROOT_ID else "$ROOT_ID/${file.relativeTo(root).path}"

    private fun addRow(cursor: MatrixCursor, documentId: String, file: File) {
        if (!file.exists()) throw FileNotFoundException("Missing document $documentId")
        val isDirectory = file.isDirectory
        val flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
            if (isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (isDirectory) Document.MIME_TYPE_DIR else mimeTypeOf(file))
            .add(Document.COLUMN_SIZE, if (isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun mimeTypeOf(file: File): String = when (file.extension.lowercase()) {
        "txt" -> "text/plain"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    companion object {
        const val ROOT_ID = "root"

        /** Number of [queryDocument] and [queryChildDocuments] calls since the last reset. */
        val queryCount = AtomicInteger()

        fun authority(context: Context): String = "${context.packageName}.fakedocs"

        fun rootDir(context: Context): File = File(context.cacheDir, "fake-docs")

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
