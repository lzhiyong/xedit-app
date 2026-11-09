/*
 * Copyright © 2023 Github Lzhiyong
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

package x.editor.app.controller

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.Toast

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import java.io.File
import java.util.Locale

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import x.editor.app.R
import x.editor.app.databinding.ActivityEditorBinding
import x.editor.app.filetree.DocumentChildLoader
import x.editor.app.filetree.DocumentPaths
import x.editor.app.filetree.FileTreeAdapter
import x.editor.app.filetree.FileTreeDividerDecoration
import x.editor.app.filetree.FileTreeEntry
import x.editor.app.filetree.FileTreeMetrics
import x.editor.app.filetree.StorageAccessDeniedException
import x.editor.app.util.isPermissionDenied

import x.github.module.document.DocumentFile
import x.github.module.treeview.OnNodeClickListener
import x.github.module.treeview.OnNodeLongClickListener
import x.github.module.treeview.TreeLayoutManager
import x.github.module.treeview.TreeNode

/**
 * The file tree in the start drawer of EditorActivity, built on the treeview module.
 *
 * Its roots, top to bottom: "external storage" (/sdcard, always there, read with the
 * all-files permission), the folders picked with OpenDocumentTree, the files picked
 * with OpenMultipleDocuments (both SAF). Folders are listed lazily when expanded.
 * A text file is tapped to open it; other files are greyed out.
 *
 * A long press shows a menu:
 *  - a directory: new folder, new file, copy path, rename, delete; "refresh" too,
 *    right below copy path, for a SAF directory (a file manager may change it behind
 *    the app's back)
 *  - a file: copy path, rename, delete
 *  - a root picked through SAF: "remove" instead of rename and delete. It only takes
 *    the root off the list (and releases its permission); renaming it would break the
 *    permission, which is bound to its uri. "external storage" has neither.
 * Delete removes the file or folder from the disk, after a confirmation.
 *
 * The activity owns the persisted permissions: it takes them before a root is added
 * here and releases them in [onRemove].
 *
 * Main thread only.
 *
 * @param currentUri the uri of the document that is open, if any
 * @param hasStorageAccess whether /sdcard may be read and written (see EditorActivity)
 * @param onOpen a text file other than the open one was tapped, or was just created
 * @param onRemove a root was taken off the tree; the argument is its grant uri
 * @param beforeChange called before a document is renamed or deleted: lets a save of
 *   the open document finish first, so that it is not written under the old name
 * @param afterChange called once such a rename or delete is through, whether it
 *   succeeded or not (after [onRenamed] or [onDeleted]): until then the editor does not
 *   take the document's file for one changed or deleted by another app
 * @param hasUnsavedChangesIn whether the open document is the document, or inside the
 *   directory, and has unsaved changes: the delete confirmation warns about them
 * @param onRenamed a document was renamed: old uri, new document, new name, whether a
 *   directory
 * @param onDeleted a document was deleted from the disk: its uri, whether a directory
 * @param onStorageAccessRequired /sdcard was accessed without the permission; call
 *   [retryStorageAccess] once it is granted
 */
internal class FileTreeController(
    private val activity: AppCompatActivity,
    private val binding: ActivityEditorBinding,
    private val currentUri: () -> Uri?,
    private val hasStorageAccess: () -> Boolean,
    private val onOpen: (DocumentFile) -> Unit,
    private val onRemove: (Uri) -> Unit,
    private val beforeChange: suspend (uri: Uri, isDirectory: Boolean) -> Unit,
    private val afterChange: () -> Unit = {},
    private val hasUnsavedChangesIn: (uri: Uri, isDirectory: Boolean) -> Boolean,
    private val onRenamed: (oldUri: Uri, newDocument: DocumentFile, newName: String, isDirectory: Boolean) -> Unit,
    private val onDeleted: (uri: Uri, isDirectory: Boolean) -> Unit,
    private val onStorageAccessRequired: () -> Unit
) {

    private val metrics = FileTreeMetrics(activity)
    private val treeAdapter = FileTreeAdapter(metrics, currentUri)
    private val treeLayoutManager = TreeLayoutManager(rowHeight = metrics.rowHeight)

    // the node whose load failed for lack of the storage permission
    private var nodeAwaitingAccess: TreeNode<FileTreeEntry>? = null

    /** Sets the tree up with the "external storage" root. Call once, when the views are there. */
    fun attach() {
        treeAdapter.apply {
            rowWidthCalculator = metrics
            autoExpandSingleChild = true
            childLoader = DocumentChildLoader(activity.lifecycleScope, hasStorageAccess)
            // Click: a text file is opened; a directory is toggled by TreeAdapter itself
            // (toggleOnClick). Other files are not clickable (see FileTreeAdapter).
            onNodeClickListener = OnNodeClickListener { node, _, _ ->
                if (node.value.canOpen) openFile(node.value)
            }
            onNodeLongClickListener = OnNodeLongClickListener { node, _, view ->
                showNodeMenu(node, view)
            }
            onChildrenLoadFailed = { node, error ->
                when {
                    error is StorageAccessDeniedException -> {
                        toast(activity.getString(R.string.file_open_permission_denied))
                        nodeAwaitingAccess = node
                        onStorageAccessRequired()
                    }
                    error?.isPermissionDenied() == true -> {
                        toast(activity.getString(R.string.file_open_permission_denied))
                        if (node.value.isRawFile) {
                            nodeAwaitingAccess = node
                            onStorageAccessRequired()
                        }
                    }
                    else -> toast(activity.getString(R.string.file_tree_load_failed, node.value.name))
                }
            }
        }

        // The adapter is a ContentWidthProvider: the layout manager picks up the exact
        // content width (horizontal scroll range) by itself.
        binding.fileTreePanel.apply {
            layoutManager = treeLayoutManager
            adapter = treeAdapter
            addItemDecoration(FileTreeDividerDecoration(metrics.dividerHeight, DIVIDER_COLOR))
        }

        treeAdapter.setRoots(listOf(TreeNode(storageEntry(), childrenLoaded = false)))
    }

    /** Whether a root folder or file was added for [grantUri]. */
    fun containsRoot(grantUri: Uri): Boolean = findRoot(grantUri) != null

    /**
     * Adds a root folder for each tree uri not in the tree yet; their permissions must
     * be persisted already. With [reveal], the last folder of [treeUris] is expanded
     * and scrolled to, also when it was in the tree already.
     */
    suspend fun addFolders(treeUris: List<Uri>, reveal: Boolean = true) {
        val missing = treeUris.filter { !containsRoot(it) }
        val entries = withContext(Dispatchers.IO) { missing.mapNotNull { createFolderEntry(it) } }
        for (entry in entries) {
            val grantUri = entry.grantUri ?: continue
            // added meanwhile (picked twice in a row)
            if (containsRoot(grantUri)) continue
            // after "external storage" and the other folders, before the files
            val index = 1 + treeAdapter.rootNodes.count { it.value.kind == FileTreeEntry.Kind.FOLDER }
            treeAdapter.insertNode(null, TreeNode(entry, childrenLoaded = false), index)
        }
        if (reveal) {
            treeUris.lastOrNull()?.let { findRoot(it) }?.let { reveal(it) }
        }
    }

    /** Adds a root file for each document uri not in the tree yet; see [addFolders]. */
    suspend fun addFiles(documentUris: List<Uri>) {
        val missing = documentUris.filter { !containsRoot(it) }
        val entries = withContext(Dispatchers.IO) {
            missing.map { uri ->
                FileTreeEntry.of(DocumentFile.fromSingleUri(activity, uri), FileTreeEntry.Kind.FILE, uri)
            }
        }
        var firstPosition = RecyclerView.NO_POSITION
        for (entry in entries) {
            val grantUri = entry.grantUri ?: continue
            if (containsRoot(grantUri)) continue
            val node = TreeNode(entry)
            treeAdapter.insertNode(null, node)
            if (firstPosition == RecyclerView.NO_POSITION) firstPosition = treeAdapter.positionOf(node)
        }
        if (firstPosition != RecyclerView.NO_POSITION) {
            binding.fileTreePanel.scrollToPosition(firstPosition)
        }
    }

    /**
     * The document at [uri] was opened or closed: its row is highlighted, or no longer.
     * Does nothing for a uri that is not shown.
     */
    fun notifyChanged(uri: Uri?) {
        treeAdapter.notifyHighlightChanged(uri)
    }

    /** The storage permission was granted: lists the folder that was refused, again. */
    fun retryStorageAccess() {
        val node = nodeAwaitingAccess ?: return
        nodeAwaitingAccess = null
        if (node in treeAdapter) {
            // a node whose load failed is collapsed; expanding it retries
            treeAdapter.expand(node)
        }
    }

    private fun findRoot(grantUri: Uri): TreeNode<FileTreeEntry>? =
        treeAdapter.rootNodes.firstOrNull { it.value.grantUri == grantUri }

    private fun reveal(node: TreeNode<FileTreeEntry>) {
        treeAdapter.expand(node)
        scrollTo(node)
    }

    private fun scrollTo(node: TreeNode<FileTreeEntry>) {
        val position = treeAdapter.positionOf(node)
        if (position != RecyclerView.NO_POSITION) {
            binding.fileTreePanel.scrollToPosition(position)
        }
    }

    private fun openFile(entry: FileTreeEntry) {
        // below /sdcard without "all files access": would fail with EACCES
        if (entry.isRawFile && !hasStorageAccess()) {
            toast(activity.getString(R.string.file_open_permission_denied))
            onStorageAccessRequired()
            return
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        // skip the same file
        if (entry.uri != currentUri()) {
            onOpen(entry.document)
        }
    }

    // #region long press menu

    // Builds the menu for the node; see the class comment for which items.
    @SuppressLint("RestrictedApi")
    private fun showNodeMenu(node: TreeNode<FileTreeEntry>, anchor: View): Boolean {
        val entry = node.value
        val popup = PopupMenu(activity, anchor)
        val menu = popup.menu

        if (entry.isDirectory) {
            menu.addItem(MENU_NEW_DIRECTORY, R.string.file_tree_new_folder, R.drawable.ic_add_folder)
            menu.addItem(MENU_NEW_FILE, R.string.file_tree_new_file, R.drawable.ic_add_file)
        }
        menu.addItem(MENU_COPY_PATH, R.string.file_tree_copy_path, R.drawable.ic_content_copy)
        if (entry.isDirectory && !entry.isRawFile && entry.kind != FileTreeEntry.Kind.STORAGE) {
            menu.addItem(MENU_REFRESH, R.string.file_tree_refresh, R.drawable.ic_restart)
        }
        if (entry.isChild) {
            menu.addItem(MENU_RENAME, R.string.file_tree_rename, R.drawable.ic_rename)
            menu.addItem(MENU_DELETE, R.string.file_tree_delete, R.drawable.ic_delete, danger = true)
        }
        if (entry.isRemovable) {
            menu.addItem(MENU_REMOVE, R.string.file_tree_remove, R.drawable.ic_close)
        }

        // show the icons (the same as for the options menu of the activity)
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REFRESH -> refresh(node)
                MENU_NEW_DIRECTORY -> askName(R.string.file_tree_new_folder, "") { name ->
                    create(node, name, directory = true)
                }
                MENU_NEW_FILE -> askName(R.string.file_tree_new_file, "") { name ->
                    create(node, name, directory = false)
                }
                MENU_COPY_PATH -> copyPath(entry)
                MENU_RENAME -> askName(R.string.file_tree_rename, entry.name) { name ->
                    rename(node, name)
                }
                MENU_DELETE -> confirmDelete(node)
                MENU_REMOVE -> remove(node)
            }
            true
        }
        popup.show()
        return true
    }

    // Adds an item with an icon; a [danger] item is drawn in the error color of the
    // Material theme, icon and text.
    private fun Menu.addItem(
        id: Int,
        @StringRes title: Int,
        @DrawableRes icon: Int,
        danger: Boolean = false
    ) {
        val text = activity.getString(title)
        val drawable = ContextCompat.getDrawable(activity, icon)?.mutate()
        if (!danger) {
            add(Menu.NONE, id, size(), text).icon = drawable
            return
        }
        val errorColor = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError)
        drawable?.setTint(errorColor)
        val colored = SpannableString(text).apply {
            setSpan(ForegroundColorSpan(errorColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        add(Menu.NONE, id, size(), colored).icon = drawable
    }

    // #endregion

    // #region actions

    private fun copyPath(entry: FileTreeEntry) {
        val path = DocumentPaths.displayPath(entry.uri)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(entry.name, path))
        toast("${activity.getString(R.string.file_tree_path_copied)}: $path")
    }

    // lists the directory again: an expanded one at once, a collapsed one is expanded
    private fun refresh(node: TreeNode<FileTreeEntry>) {
        if (node !in treeAdapter) return
        treeAdapter.reloadChildren(node)
        if (!node.isExpanded) treeAdapter.expand(node)
    }

    private fun create(parent: TreeNode<FileTreeEntry>, name: String, directory: Boolean) {
        val parentEntry = parent.value
        if (!checkStorageAccess(parentEntry)) return
        activity.lifecycleScope.launch {
            val result = runIo {
                val document = if (directory) {
                    parentEntry.document.createDirectory(name)
                } else {
                    parentEntry.document.createFile(mimeTypeOf(name), name)
                }
                document?.let { FileTreeEntry.of(it, FileTreeEntry.Kind.CHILD) }
            }
            val entry = result.getOrNull() ?: return@launch showFailure(result)
            // the tree may have changed meanwhile
            if (parent !in treeAdapter) return@launch

            if (parent.loadState == TreeNode.LoadState.LOADED) {
                // a new folder is empty: known, nothing to load
                val node = TreeNode(entry)
                val index = parent.children
                    .indexOfFirst { DocumentChildLoader.ORDER.compare(entry, it.value) < 0 }
                    .let { if (it < 0) parent.children.size else it }
                treeAdapter.insertNode(parent, node, index)
                treeAdapter.expand(parent)
                scrollTo(node)
            } else {
                // not listed yet (or the listing failed): listing it now includes the new one
                treeAdapter.expand(parent)
            }

            if (entry.canOpen) openFile(entry)
        }
    }

    private fun rename(node: TreeNode<FileTreeEntry>, name: String) {
        val entry = node.value
        if (name == entry.name) return
        if (!checkStorageAccess(entry)) return
        activity.lifecycleScope.launch {
            beforeChange(entry.uri, entry.isDirectory)
            val result = try {
                runIo { entry.document.renameTo(name)?.let { entry.renamed(it) } }.also { result ->
                    // first the activity: the open document may have moved, and the row
                    // highlight is drawn from it
                    result.getOrNull()?.let { onRenamed(entry.uri, it.document, it.name, entry.isDirectory) }
                }
            } finally {
                afterChange()
            }
            val renamed = result.getOrNull() ?: return@launch showFailure(result)
            if (node !in treeAdapter) return@launch
            node.value = renamed
            treeAdapter.notifyNodeChanged(node)
            if (entry.isDirectory) {
                // the uris of everything inside changed with the name
                treeAdapter.reloadChildren(node)
            }
        }
    }

    private fun confirmDelete(node: TreeNode<FileTreeEntry>) {
        val entry = node.value
        var message = activity.getString(
            if (entry.isDirectory) R.string.file_tree_delete_folder_message else R.string.file_tree_delete_file_message,
            entry.name
        )
        if (hasUnsavedChangesIn(entry.uri, entry.isDirectory)) {
            message += "\n\n" + activity.getString(R.string.file_tree_delete_unsaved_message)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.file_tree_delete)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.file_tree_delete) { _, _ -> delete(node) }
            .show()
    }

    private fun delete(node: TreeNode<FileTreeEntry>) {
        val entry = node.value
        if (!checkStorageAccess(entry)) return
        activity.lifecycleScope.launch {
            beforeChange(entry.uri, entry.isDirectory)
            try {
                val result = runIo { entry.document.delete().takeIf { it } }
                if (result.getOrNull() != true) return@launch showFailure(result)
                nodeAwaitingAccess?.let { if (it === node || it.isDescendantOf(node)) nodeAwaitingAccess = null }
                if (node in treeAdapter) treeAdapter.removeNode(node)
                onDeleted(entry.uri, entry.isDirectory)
            } finally {
                afterChange()
            }
        }
    }

    // a SAF root: off the list, the files stay where they are
    private fun remove(node: TreeNode<FileTreeEntry>) {
        nodeAwaitingAccess?.let { if (it.root === node) nodeAwaitingAccess = null }
        treeAdapter.removeNode(node)
        node.value.grantUri?.let(onRemove)
    }

    // Files below /sdcard need the all-files permission; false (and asked for) if missing.
    private fun checkStorageAccess(entry: FileTreeEntry): Boolean {
        if (!entry.isRawFile || hasStorageAccess()) return true
        toast(activity.getString(R.string.file_tree_permission_denied))
        onStorageAccessRequired()
        return false
    }

    // Runs [block] on the IO dispatcher. Providers fail in many ways: a failure is
    // returned, never thrown; so is null, when the operation said no.
    private suspend fun <R> runIo(block: () -> R?): Result<R?> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // "operation failed", or "permission denied" when that is the reason
    private fun showFailure(result: Result<*>) {
        val denied = result.exceptionOrNull()?.isPermissionDenied() == true
        toast(activity.getString(if (denied) R.string.file_tree_permission_denied else R.string.file_tree_operation_failed))
    }

    // #endregion

    // #region name dialog

    // Asks for a file or folder name; [onName] gets it trimmed and checked.
    private fun askName(@StringRes title: Int, initial: String, onName: (String) -> Unit) {
        val input = TextInputEditText(activity).apply {
            setText(initial)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val inputLayout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.file_tree_name_hint)
            addView(input)
        }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(inputLayout)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        fun submit() {
            val name = input.text?.toString()?.trim().orEmpty()
            if (!isValidName(name)) {
                inputLayout.error = activity.getString(R.string.file_tree_name_invalid)
                return
            }
            dialog.dismiss()
            onName(name)
        }

        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        dialog.setOnShowListener {
            // set here, not in the builder: an invalid name must keep the dialog open
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
            // select the name without the extension, like a file manager
            val end = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
            input.setSelection(0, end)
        }
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.show()
    }

    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }

    // #endregion

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    private fun storageEntry() = FileTreeEntry(
        document = DocumentFile.fromFile(activity, STORAGE_DIRECTORY),
        name = STORAGE_NAME,
        isDirectory = true,
        kind = FileTreeEntry.Kind.STORAGE
    )

    // IO: reads the name of the folder from its provider
    private fun createFolderEntry(treeUri: Uri): FileTreeEntry? {
        val document = try {
            DocumentFile.fromTreeUri(activity, treeUri)
        } catch (e: Exception) {
            // provider gone, permission gone: the folder cannot be shown
            null
        } ?: return null
        return FileTreeEntry.of(document, FileTreeEntry.Kind.FOLDER, treeUri)
    }

    companion object {
        const val MENU_NEW_DIRECTORY = 1
        const val MENU_NEW_FILE = 2
        const val MENU_COPY_PATH = 3
        const val MENU_RENAME = 4
        const val MENU_DELETE = 5
        const val MENU_REMOVE = 6
        const val MENU_REFRESH = 7

        const val DIVIDER_COLOR = 0x33888888

        // the fixed first root of the tree
        const val STORAGE_NAME = "external storage"
        val STORAGE_DIRECTORY = File("/sdcard")

        // The type a new file is created with. With the type of its extension the
        // provider keeps the name as it is; octet-stream for anything else (Makefile,
        // .kt, ...), so that no ".bin" or ".txt" is appended.
        fun mimeTypeOf(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
    }
}
