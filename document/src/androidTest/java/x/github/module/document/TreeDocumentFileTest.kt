package x.github.module.document

import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TreeDocumentFileTest {

    private val provider = FakeProviderFixture()

    @BeforeTest
    fun setUp() = provider.setUp()

    @AfterTest
    fun tearDown() = provider.tearDown()

    private fun root(): DocumentFile =
        assertNotNull(DocumentFile.fromTreeUri(provider.context, provider.treeUri))

    // ---- factories ----

    @Test
    fun fromTreeUri_directory_returnsTreeDocumentFile() {
        val root = assertIs<TreeDocumentFile>(root())

        assertTrue(root.isDirectory())
        assertEquals("fake-docs", root.getName())
        assertNull(root.getParent())
    }

    @Test
    fun fromTreeUri_documentInsideTreeThatIsAFile_returnsSingleDocumentFile() {
        provider.file("a.txt", "hello")

        val document = assertIs<SingleDocumentFile>(
            DocumentFile.fromTreeUri(provider.context, provider.treeDocumentUri("a.txt"))
        )

        assertTrue(document.isFile())
        assertEquals("a.txt", document.getName())
    }

    @Test
    fun fromUri_dispatchesOnUriShape() {
        provider.file("a.txt", "hello")

        assertIs<TreeDocumentFile>(DocumentFile.fromUri(provider.context, provider.treeUri))
        assertIs<SingleDocumentFile>(DocumentFile.fromUri(provider.context, provider.documentUri("a.txt")))
    }

    // ---- listing ----

    @Test
    fun listFiles_costsOneQuery_includingChildMetadata() {
        provider.file("a.txt", "hello")
        provider.file("b.bin", "12345678")
        provider.file("sub/c.txt", "nested")
        val root = root()
        provider.resetQueryCount()

        val children = assertNotNull(root.listFiles())
        children.forEach {
            it.getName()
            it.getType()
            it.length()
            it.lastModified()
            it.isDirectory()
            it.isVirtual()
        }

        assertEquals(3, children.size)
        assertEquals(1, provider.queryCount)
    }

    @Test
    fun listFiles_buildsTypedChildrenWithMetadata() {
        provider.file("a.txt", "hello")
        provider.directory("sub")

        val children = assertNotNull(root().listFiles()).associateBy { it.getName() }

        val file = assertIs<SingleDocumentFile>(children["a.txt"])
        assertTrue(file.isFile())
        assertEquals("text/plain", file.getType())
        assertEquals(5L, file.length())

        val directory = assertIs<TreeDocumentFile>(children["sub"])
        assertTrue(directory.isDirectory())
        assertEquals(Document.MIME_TYPE_DIR, directory.getType())
    }

    @Test
    fun listFiles_childrenKnowTheirParent_andNestedListingWorks() {
        provider.file("sub/c.txt", "nested")
        val root = root()

        val sub = assertNotNull(root.listFiles()).single()
        val nested = assertNotNull(sub.listFiles()).single()

        assertSame(root, sub.getParent())
        assertSame(sub, nested.getParent())
        assertEquals("c.txt", nested.getName())
    }

    @Test
    fun listFiles_emptyDirectory_returnsEmptyList() {
        assertTrue(assertNotNull(root().listFiles()).isEmpty())
    }

    @Test
    fun getChildCount_isLazyAndCached() {
        provider.file("a.txt", "1")
        provider.file("b.txt", "2")
        provider.file("c.txt", "3")
        val root = root()
        provider.resetQueryCount()

        assertEquals(3, root.getChildCount())
        assertEquals(3, root.getChildCount())
        assertEquals(1, provider.queryCount)
    }

    @Test
    fun listingSubdirectories_doesNotCountTheirChildren() {
        // Regression: every child directory used to enumerate its own children on construction.
        repeat(5) { provider.file("dir$it/file.txt", "x") }
        val root = root()
        provider.resetQueryCount()

        root.listFiles()

        assertEquals(1, provider.queryCount)
    }

    @Test
    fun find_returnsMatchOrNull_inOneQueryEach() {
        provider.file("a.txt", "hello")
        provider.file("b.txt", "world")
        val root = root()
        provider.resetQueryCount()

        val found = assertNotNull(root.find("b.txt"))

        assertEquals(5L, found.length())
        assertNull(root.find("missing.txt"))
        assertEquals(2, provider.queryCount)
    }

    // ---- creating ----

    @Test
    fun createFile_and_createDirectory() {
        val root = root()

        val file = assertIs<SingleDocumentFile>(root.createFile("text/plain", "new.txt"))
        val directory = root.createDirectory("dir")

        assertTrue(file.exists())
        assertEquals("new.txt", file.getName())
        assertSame(root, file.getParent())
        assertIs<TreeDocumentFile>(directory)
        assertTrue(provider.rootDir.resolve("dir").isDirectory)
    }

    @Test
    fun createFile_invalidatesCachedChildCount() {
        val root = root()
        assertEquals(0, root.getChildCount())

        root.createFile("text/plain", "new.txt")

        assertEquals(1, root.getChildCount())
    }

    @Test
    fun createFile_conflict_returnsNullWithoutThrowing() {
        provider.file("taken.txt", "x")
        assertNull(root().createFile("text/plain", "taken.txt"))
    }

    // ---- renaming and deleting ----

    @Test
    fun renameTo_returnsNewInstance_andLeavesOriginalUnchanged() {
        provider.file("a.txt", "hello")
        val original = assertNotNull(root().find("a.txt"))

        val renamed = assertNotNull(original.renameTo("b.txt"))

        assertEquals("b.txt", renamed.getName())
        assertTrue(renamed.exists())
        assertEquals("a.txt", original.getName())
        assertFalse(original.exists())
    }

    @Test
    fun renameTo_directory_returnsTreeDocumentFile() {
        provider.directory("old")

        val renamed = assertIs<TreeDocumentFile>(assertNotNull(root().find("old")).renameTo("new"))

        assertEquals("new", renamed.getName())
    }

    @Test
    fun delete_removesDocument_andExistsBecomesFalse() {
        provider.file("sub/c.txt", "x")
        val sub = assertNotNull(root().find("sub"))

        assertTrue(sub.delete())
        assertFalse(sub.exists())
        assertFalse(provider.rootDir.resolve("sub").exists())
    }

    @Test
    fun operationsOnDeletedDocument_doNotThrow() {
        provider.directory("gone")
        val gone = assertNotNull(root().find("gone"))
        provider.rootDir.resolve("gone").delete()

        assertFalse(gone.exists())
        assertNull(gone.listFiles())
        assertEquals(0, gone.getChildCount())
        assertNull(gone.createFile("text/plain", "x.txt"))
        assertNull(gone.renameTo("other"))
        assertFalse(gone.delete())
    }

    // ---- caching ----

    @Test
    fun metadataIsASnapshot_untilRefresh() {
        val file = provider.file("a.txt", "hi")
        val document = assertNotNull(root().find("a.txt"))
        assertEquals(2L, document.length())

        file.writeText("hello world")
        assertEquals(2L, document.length())

        document.refresh()
        assertEquals(11L, document.length())
    }

    @Test
    fun refresh_resetsCachedChildCount() {
        val root = root()
        assertEquals(0, root.getChildCount())

        provider.file("a.txt", "x")
        assertEquals(0, root.getChildCount())

        root.refresh()
        assertEquals(1, root.getChildCount())
    }

    // ---- permissions and identity ----

    @Test
    fun ownProviderDocuments_areReadableAndWritable() {
        provider.file("a.txt", "x")
        val root = root()

        assertTrue(root.canRead())
        assertTrue(root.canWrite())
        assertTrue(assertNotNull(root.find("a.txt")).canWrite())
    }

    @Test
    fun equality_isBasedOnUri() {
        provider.file("a.txt", "x")
        val first = assertNotNull(root().find("a.txt"))
        val second = assertNotNull(root().listFiles()).single()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
