package x.github.module.document

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.rules.ExternalResource

/** Gives each test an empty [FakeDocumentsProvider] root and a reset query counter. */
class FakeProviderRule : ExternalResource() {

    lateinit var context: Context
        private set

    /** The directory on disk that backs the provider's root document. */
    lateinit var rootDir: File
        private set

    /** The tree Uri for the provider's root, as `ACTION_OPEN_DOCUMENT_TREE` would return it. */
    lateinit var treeUri: Uri
        private set

    val authority: String
        get() = FakeDocumentsProvider.authority(context)

    override fun before() {
        context = ApplicationProvider.getApplicationContext()
        rootDir = FakeDocumentsProvider.rootDir(context).apply {
            deleteRecursively()
            mkdirs()
        }
        treeUri = DocumentsContract.buildTreeDocumentUri(authority, FakeDocumentsProvider.ROOT_ID)
        resetQueryCount()
    }

    override fun after() {
        rootDir.deleteRecursively()
    }

    fun resetQueryCount() = FakeDocumentsProvider.queryCount.set(0)

    val queryCount: Int
        get() = FakeDocumentsProvider.queryCount.get()

    /** A document Uri inside the tree, for a path relative to the root. */
    fun treeDocumentUri(relativePath: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, "${FakeDocumentsProvider.ROOT_ID}/$relativePath")

    /** A plain document Uri (no tree), for a path relative to the root. */
    fun documentUri(relativePath: String): Uri =
        DocumentsContract.buildDocumentUri(authority, "${FakeDocumentsProvider.ROOT_ID}/$relativePath")

    fun file(relativePath: String, content: String? = null): File =
        File(rootDir, relativePath).apply {
            parentFile?.mkdirs()
            if (content != null) writeText(content)
        }

    fun directory(relativePath: String): File = File(rootDir, relativePath).apply { mkdirs() }
}
