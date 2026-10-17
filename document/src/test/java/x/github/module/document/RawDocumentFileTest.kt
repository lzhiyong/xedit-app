package x.github.module.document

import android.content.Context
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [36])
class RawDocumentFileTest {

    private lateinit var temp: TempDirectory
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var rootDocument: DocumentFile

    @Before
    fun setUp() {
        temp = TempDirectory()
        context = RuntimeEnvironment.getApplication()
        root = temp.newFolder("root")
        rootDocument = DocumentFile.fromFile(context, root)
    }

    @After
    fun tearDown() {
        temp.delete()
    }

    // ---- listing ----

    @Test
    fun listFiles_returnsAllChildren() {
        // Regression: listFiles() used to discard the list it built and always return null.
        File(root, "a.txt").writeText("hello")
        File(root, "dir").mkdir()

        val children = assertNotNull(rootDocument.listFiles())

        assertEquals(listOf("a.txt", "dir"), children.map { it.getName() }.sorted())
        children.forEach { assertSame(rootDocument, it.getParent()) }
    }

    @Test
    fun listFiles_emptyDirectory_returnsEmptyList() {
        assertTrue(assertNotNull(rootDocument.listFiles()).isEmpty())
    }

    @Test
    fun listFiles_onFile_returnsNull() {
        val file = File(root, "a.txt").apply { writeText("x") }
        assertNull(DocumentFile.fromFile(context, file).listFiles())
    }

    @Test
    fun listFiles_onMissingDirectory_returnsNull() {
        assertNull(DocumentFile.fromFile(context, File(root, "missing")).listFiles())
    }

    @Test
    fun getChildCount_countsChildren() {
        File(root, "a").writeText("x")
        File(root, "b").writeText("y")
        assertEquals(2, rootDocument.getChildCount())
        assertEquals(0, DocumentFile.fromFile(context, File(root, "a")).getChildCount())
    }

    @Test
    fun find_returnsMatchingChildOrNull() {
        File(root, "a.txt").writeText("x")
        assertEquals("a.txt", rootDocument.find("a.txt")?.getName())
        assertNull(rootDocument.find("b.txt"))
    }

    // ---- creating ----

    @Test
    fun createFile_appendsExtensionForMimeType() {
        val expected = nameWithExtension("notes", "text/plain")

        val created = assertNotNull(rootDocument.createFile("text/plain", "notes"))

        assertEquals(expected, created.getName())
        assertTrue(File(root, expected).isFile)
        assertSame(rootDocument, created.getParent())
    }

    @Test
    fun createFile_doesNotDuplicateExistingExtension() {
        val name = nameWithExtension("notes", "text/plain")

        val created = rootDocument.createFile("text/plain", name)

        assertEquals(name, created?.getName())
    }

    @Test
    fun createFile_unknownMimeType_createsFileWithoutExtension() {
        // Regression: a MIME type without a registered extension used to make createFile fail.
        val created = rootDocument.createFile(UNKNOWN_MIME_TYPE, "blob")

        assertEquals("blob", created?.getName())
        assertTrue(File(root, "blob").isFile)
    }

    @Test
    fun createFile_existingName_returnsNull() {
        assertNotNull(rootDocument.createFile(UNKNOWN_MIME_TYPE, "blob"))
        assertNull(rootDocument.createFile(UNKNOWN_MIME_TYPE, "blob"))
    }

    @Test
    fun createFile_rejectsNamesThatEscapeTheDirectory() {
        assertNull(rootDocument.createFile(UNKNOWN_MIME_TYPE, "../escape"))
        assertNull(rootDocument.createFile(UNKNOWN_MIME_TYPE, ".."))
        assertNull(rootDocument.createFile(UNKNOWN_MIME_TYPE, ""))
        assertFalse(File(root.parentFile, "escape").exists())
    }

    @Test
    fun createDirectory_createsOrReturnsExistingDirectory() {
        val first = assertNotNull(rootDocument.createDirectory("dir"))
        val second = rootDocument.createDirectory("dir")

        assertEquals(first, second)
        assertTrue(first.isDirectory())
        assertEquals(Document.MIME_TYPE_DIR, first.getType())
    }

    @Test
    fun createDirectory_existingFileWithSameName_returnsNull() {
        File(root, "taken").writeText("x")
        assertNull(rootDocument.createDirectory("taken"))
    }

    // ---- renaming ----

    @Test
    fun renameTo_returnsNewInstance_andLeavesOriginalUnchanged() {
        val original = assertNotNull(rootDocument.createFile(UNKNOWN_MIME_TYPE, "old"))

        val renamed = assertNotNull(original.renameTo("new"))

        assertEquals("new", renamed.getName())
        assertTrue(renamed.exists())
        assertSame(rootDocument, renamed.getParent())
        // The original still points at the old path, which no longer exists.
        assertEquals("old", original.getName())
        assertFalse(original.exists())
    }

    @Test
    fun renameTo_existingTarget_failsWithoutOverwriting() {
        val a = assertNotNull(rootDocument.createFile(UNKNOWN_MIME_TYPE, "a"))
        File(root, "b").writeText("keep me")

        assertNull(a.renameTo("b"))
        assertTrue(a.exists())
        assertEquals("keep me", File(root, "b").readText())
    }

    @Test
    fun renameTo_rejectsPathSeparators() {
        val a = assertNotNull(rootDocument.createFile(UNKNOWN_MIME_TYPE, "a"))
        assertNull(a.renameTo("../a"))
        assertTrue(a.exists())
    }

    // ---- deleting ----

    @Test
    fun delete_removesDirectoryRecursively() {
        File(root, "dir/nested").mkdirs()
        File(root, "dir/nested/file.txt").writeText("x")
        File(root, "top.txt").writeText("y")

        assertTrue(rootDocument.delete())
        assertFalse(root.exists())
    }

    @Test
    fun delete_doesNotFollowSymbolicLinks() {
        val outside = temp.newFolder("outside")
        val precious = File(outside, "precious.txt").apply { writeText("keep") }
        try {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            return // Symbolic links are not supported on this file system.
        } catch (e: IOException) {
            return
        }

        assertTrue(rootDocument.delete())
        assertFalse(root.exists())
        assertTrue(precious.exists())
    }

    // ---- metadata ----

    @Test
    fun getType_usesExtensionAndFallsBackToOctetStream() {
        val unknown = DocumentFile.fromFile(context, File(root, "data.unknownext").apply { writeText("x") })
        val noExtension = DocumentFile.fromFile(context, File(root, "README").apply { writeText("x") })

        assertEquals(Document.MIME_TYPE_DIR, rootDocument.getType())
        assertEquals("application/octet-stream", unknown.getType())
        assertEquals("application/octet-stream", noExtension.getType())
    }

    @Test
    fun metadata_reflectsFileSystem() {
        val file = File(root, "a.bin").apply { writeBytes(ByteArray(42)) }
        val document = DocumentFile.fromFile(context, file)

        assertTrue(document.isFile())
        assertFalse(document.isDirectory())
        assertFalse(document.isVirtual())
        assertEquals(42L, document.length())
        assertEquals(file.lastModified(), document.lastModified())
        assertTrue(document.canRead())
    }

    @Test
    fun equality_isBasedOnUri() {
        val again = DocumentFile.fromFile(context, root)
        assertEquals(rootDocument, again)
        assertEquals(rootDocument.hashCode(), again.hashCode())
        assertNotEquals(rootDocument, DocumentFile.fromFile(context, File(root, "other")))
    }

    /**
     * The name createFile() should produce: with the registered extension, or unchanged when
     * the test environment has no mapping for [mimeType].
     */
    private fun nameWithExtension(name: String, mimeType: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (extension == null) name else "$name.$extension"
    }

    private companion object {
        const val UNKNOWN_MIME_TYPE = "application/x-documentfile-test-unknown"
    }
}
