package x.github.module.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleDocumentFileTest {

    private val provider = FakeProviderFixture()

    @BeforeTest
    fun setUp() = provider.setUp()

    @AfterTest
    fun tearDown() = provider.tearDown()

    @Test
    fun construction_performsNoQuery_andMetadataIsReadOnce() {
        provider.file("a.txt", "hello")

        val document = DocumentFile.fromSingleUri(provider.context, provider.documentUri("a.txt"))
        assertEquals(0, provider.queryCount)

        assertEquals("a.txt", document.getName())
        assertEquals("text/plain", document.getType())
        assertEquals(5L, document.length())
        assertEquals(1, provider.queryCount)
    }

    @Test
    fun isDirectory_followsMimeType() {
        // Regression: isDirectory() used to be hard-coded to false.
        provider.directory("sub")

        val document = DocumentFile.fromSingleUri(provider.context, provider.documentUri("sub"))

        assertTrue(document.isDirectory())
        assertFalse(document.isFile())
    }

    @Test
    fun missingDocument_doesNotThrow() {
        val document = DocumentFile.fromSingleUri(provider.context, provider.documentUri("missing.txt"))

        assertFalse(document.exists())
        assertEquals("", document.getName())
        assertFalse(document.isFile())
        assertFalse(document.isDirectory())
        assertEquals(0L, document.length())
        assertFalse(document.canRead())
        assertFalse(document.delete())
    }

    @Test
    fun childrenOperations_areNotSupported() {
        provider.file("a.txt", "x")
        val document = DocumentFile.fromSingleUri(provider.context, provider.documentUri("a.txt"))

        assertNull(document.listFiles())
        assertEquals(0, document.getChildCount())
        assertNull(document.find("anything"))
        assertFailsWith<UnsupportedOperationException> { document.createFile("text/plain", "x") }
        assertFailsWith<UnsupportedOperationException> { document.createDirectory("x") }
    }

    @Test
    fun renameTo_returnsNewInstance() {
        provider.file("a.txt", "x")
        val original = DocumentFile.fromSingleUri(provider.context, provider.documentUri("a.txt"))

        val renamed = assertIs<SingleDocumentFile>(original.renameTo("b.txt"))

        assertEquals("b.txt", renamed.getName())
        assertFalse(original.exists())
    }

    @Test
    fun delete_removesDocument() {
        val file = provider.file("a.txt", "x")
        val document = DocumentFile.fromSingleUri(provider.context, provider.documentUri("a.txt"))

        assertTrue(document.delete())
        assertFalse(file.exists())
    }
}
