package x.github.module.document

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for the factory methods that don't need a real provider. Provider-backed behavior is
 * covered by the instrumented tests in `src/androidTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [36])
class DocumentFileFactoryTest {

    private lateinit var temp: TempDirectory
    private lateinit var context: Context

    @Before
    fun setUp() {
        temp = TempDirectory()
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        temp.delete()
    }

    @Test
    fun fromUri_fileScheme_returnsRawDocumentFile() {
        val directory = temp.newFolder("dir")

        val document = assertIs<RawDocumentFile>(DocumentFile.fromUri(context, Uri.fromFile(directory)))

        assertEquals("dir", document.getName())
    }

    @Test
    fun fromUri_unsupportedScheme_returnsNull() {
        assertNull(DocumentFile.fromUri(context, Uri.parse("https://example.com/file.txt")))
    }

    @Test
    fun fromUri_singleDocumentUri_returnsSingleDocumentFile() {
        // Regression: fromUri used to call fromTreeUri first, which threw for non-tree Uris.
        val uri = DocumentsContract.buildDocumentUri(MISSING_AUTHORITY, "doc")

        assertIs<SingleDocumentFile>(DocumentFile.fromUri(context, uri))
    }

    @Test
    fun fromTreeUri_nonTreeUri_returnsNull() {
        val uri = DocumentsContract.buildDocumentUri(MISSING_AUTHORITY, "doc")

        assertNull(DocumentFile.fromTreeUri(context, uri))
    }

    @Test
    fun fromSingleUri_unreachableProvider_doesNotThrow() {
        val uri = DocumentsContract.buildDocumentUri(MISSING_AUTHORITY, "doc")
        val document = DocumentFile.fromSingleUri(context, uri)

        assertFalse(document.exists())
        assertEquals("", document.getName())
        assertFalse(document.isFile())
        assertFalse(document.isDirectory())
        assertNull(document.listFiles())
    }

    @Test
    fun fromTreeUri_unreachableProvider_doesNotThrow() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(MISSING_AUTHORITY, "root")

        val document = assertIs<TreeDocumentFile>(DocumentFile.fromTreeUri(context, treeUri))

        assertFalse(document.exists())
        assertEquals(0, document.getChildCount())
    }

    @Test
    fun isValid_fileUri_checksFileSystem() {
        val file = temp.newFile("a.txt")
        assertTrue(DocumentFile.isValid(context, Uri.fromFile(file)))
        assertFalse(DocumentFile.isValid(context, Uri.fromFile(File(file.parentFile, "missing"))))
    }

    @Test
    fun isValid_unreachableProvider_returnsFalse() {
        val uri = DocumentsContract.buildDocumentUri(MISSING_AUTHORITY, "doc")
        assertFalse(DocumentFile.isValid(context, uri))
    }

    private companion object {
        const val MISSING_AUTHORITY = "x.github.module.document.test.missing"
    }
}
