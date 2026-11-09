package x.github.module.document

import android.provider.DocumentsContract.Document
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plain JVM tests for the MIME type and flag rules; no Android runtime needed. */
class DocumentFlagsTest {

    @Test
    fun documentWithoutMimeType_isNeitherReadableNorWritable() {
        assertFalse(DocumentFlags.isReadable(""))
        assertFalse(DocumentFlags.isWritable("", ALL_FLAGS))
    }

    @Test
    fun documentWithMimeType_isReadable() {
        assertTrue(DocumentFlags.isReadable("text/plain"))
        assertTrue(DocumentFlags.isReadable(Document.MIME_TYPE_DIR))
    }

    @Test
    fun deletableDocument_isWritable() {
        assertTrue(DocumentFlags.isWritable("text/plain", Document.FLAG_SUPPORTS_DELETE))
        assertTrue(DocumentFlags.isWritable(Document.MIME_TYPE_DIR, Document.FLAG_SUPPORTS_DELETE))
    }

    @Test
    fun directory_isWritableOnlyIfItSupportsCreate() {
        assertTrue(DocumentFlags.isWritable(Document.MIME_TYPE_DIR, Document.FLAG_DIR_SUPPORTS_CREATE))
        assertFalse(DocumentFlags.isWritable(Document.MIME_TYPE_DIR, Document.FLAG_SUPPORTS_WRITE))
        assertFalse(DocumentFlags.isWritable(Document.MIME_TYPE_DIR, 0))
    }

    @Test
    fun file_isWritableOnlyIfItSupportsWrite() {
        assertTrue(DocumentFlags.isWritable("text/plain", Document.FLAG_SUPPORTS_WRITE))
        assertFalse(DocumentFlags.isWritable("text/plain", Document.FLAG_DIR_SUPPORTS_CREATE))
        assertFalse(DocumentFlags.isWritable("text/plain", 0))
    }

    @Test
    fun virtualFlag_isDetected() {
        assertTrue(DocumentFlags.isVirtual(Document.FLAG_VIRTUAL_DOCUMENT))
        assertTrue(DocumentFlags.isVirtual(Document.FLAG_VIRTUAL_DOCUMENT or Document.FLAG_SUPPORTS_WRITE))
        assertFalse(DocumentFlags.isVirtual(Document.FLAG_SUPPORTS_WRITE))
    }

    private companion object {
        const val ALL_FLAGS = Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_WRITE or
            Document.FLAG_DIR_SUPPORTS_CREATE
    }
}
