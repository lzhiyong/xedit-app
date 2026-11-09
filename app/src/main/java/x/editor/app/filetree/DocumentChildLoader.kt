/*
 * Copyright © 2026 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package x.editor.app.filetree

import java.io.IOException

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import x.github.module.treeview.ChildLoadCallback
import x.github.module.treeview.ChildLoader
import x.github.module.treeview.TreeNode

/** Listing a folder under /sdcard was refused: the storage permission is missing. */
class StorageAccessDeniedException : SecurityException("no access to the external storage")

/**
 * Lists a directory of the file tree the first time it is expanded: through
 * java.io.File below "external storage", through the documents provider below a
 * picked folder. Directories come first, each group sorted by name.
 *
 * @param scope the loads run in it, on [Dispatchers.IO]; cancelled with it
 * @param hasStorageAccess whether the app may read the external storage; checked
 *   before a raw directory is listed, because without the permission Android 11+
 *   still lists the folders but hides the files, which would look like a valid result
 */
class DocumentChildLoader(
    private val scope: CoroutineScope,
    private val hasStorageAccess: () -> Boolean,
) : ChildLoader<FileTreeEntry> {

    companion object {
        /** Directories first, each group by name; also where a new node is inserted. */
        val ORDER: Comparator<FileTreeEntry> = compareBy<FileTreeEntry> { !it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }

    override fun loadChildren(node: TreeNode<FileTreeEntry>, callback: ChildLoadCallback<FileTreeEntry>) {
        val parent = node.value
        if (parent.isRawFile && !hasStorageAccess()) {
            callback.onFailed(StorageAccessDeniedException())
            return
        }

        scope.launch(Dispatchers.IO) {
            val children = try {
                val files = parent.document.listFiles()
                    ?: throw IOException("cannot list ${parent.name}")
                files.map { child ->
                    FileTreeEntry.of(child, FileTreeEntry.Kind.CHILD)
                }.sortedWith(ORDER).map { entry ->
                    // directories are listed when they are expanded
                    TreeNode(entry, childrenLoaded = !entry.isDirectory)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // IOException, SecurityException (permission gone), IllegalArgumentException, ...
                callback.onFailed(e)
                return@launch
            }
            callback.onLoaded(children)
        }
    }
}
