package x.github.module.document

import java.io.File
import java.nio.file.Files

/** A temporary directory for one test; replaces JUnit's TemporaryFolder rule. */
class TempDirectory {

    val root: File = Files.createTempDirectory("documentfile-test").toFile()

    fun newFolder(name: String): File = File(root, name).apply { mkdirs() }

    fun newFile(name: String): File = File(root, name).apply { createNewFile() }

    fun delete() {
        root.deleteRecursively()
    }
}
