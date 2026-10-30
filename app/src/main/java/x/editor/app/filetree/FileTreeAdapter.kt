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

import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView

import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.color.MaterialColors

import x.editor.app.R
import x.github.module.treeview.TreeAdapter
import x.github.module.treeview.TreeNode

/**
 * The file tree of the start drawer, in the style of the TreeView demo: ▶ / ▼
 * indicator (red after a failed load, a progress bar while loading), emoji icons,
 * a dimmed 📁 for a directory known to be empty. A file the editor cannot open
 * (image, video, archive, ...: [FileTreeEntry.isText]) is greyed out and does not
 * react to a tap; it can still be long-pressed for its menu.
 *
 * The highlighted row is the document that is open in the editor ([currentUri]),
 * not a selection of its own: it follows openDocument() of the activity.
 */
class FileTreeAdapter(
    private val metrics: FileTreeMetrics,
    private val currentUri: () -> Uri?,
) : TreeAdapter<FileTreeEntry, FileTreeAdapter.ViewHolder>() {

    private companion object {
        const val SELECTED_COLOR = 0x332196F3
        const val ERROR_COLOR = 0xFFE53935.toInt()
        const val EMPTY_DIRECTORY_ALPHA = 0.4f
        const val DISABLED_ICON_ALPHA = 0.4f

        // The highlight doesn't affect the row width: its own payload and plain
        // notifyItemChanged instead of notifyNodeChanged.
        val PAYLOAD_HIGHLIGHT_CHANGED = Any()
    }

    // Only directories form auto-expanded chains.
    override fun canAutoExpand(child: TreeNode<FileTreeEntry>): Boolean = child.value.isDirectory

    /** Draws the highlight of every visible row showing [uri] again. */
    fun notifyHighlightChanged(uri: Uri?) {
        if (uri == null) return
        for (position in 0 until itemCount) {
            if (getNode(position).value.uri == uri) {
                notifyItemChanged(position, PAYLOAD_HIGHLIGHT_CHANGED)
            }
        }
    }

    private fun isHighlighted(node: TreeNode<FileTreeEntry>): Boolean =
        node.value.canOpen && node.value.uri == currentUri()

    override fun onCreateNodeViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_tree_node, parent, false)
        return ViewHolder(view)
    }

    override fun onBindNode(holder: ViewHolder, node: TreeNode<FileTreeEntry>, payloads: List<Any>) {
        val isPartial = payloads.isNotEmpty() && payloads.all {
            it === TreeAdapter.PAYLOAD_EXPANSION_CHANGED || it === PAYLOAD_HIGHLIGHT_CHANGED
        }
        if (!isPartial) {
            holder.bind(node)
            holder.bindExpansion(node)
            holder.bindHighlight(isHighlighted(node))
            return
        }
        if (payloads.any { it === TreeAdapter.PAYLOAD_EXPANSION_CHANGED }) holder.bindExpansion(node)
        if (payloads.any { it === PAYLOAD_HIGHLIGHT_CHANGED }) holder.bindHighlight(isHighlighted(node))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val arrowView: TextView = itemView.findViewById(R.id.arrow)
        private val progressView: ProgressBar = itemView.findViewById(R.id.progress)
        private val iconView: TextView = itemView.findViewById(R.id.icon)
        private val nameView: TextView = itemView.findViewById(R.id.name)
        private val defaultArrowColors: ColorStateList = arrowView.textColors
        private val defaultNameColors: ColorStateList = nameView.textColors

        // the grey of the Material theme, for files the editor cannot open
        private val disabledNameColor: Int = MaterialColors.getColor(
            itemView, com.google.android.material.R.attr.colorOutline
        )

        fun bind(node: TreeNode<FileTreeEntry>) {
            val entry = node.value
            itemView.setPaddingRelative(metrics.paddingStartOf(node.depth), 0, metrics.paddingEnd, 0)
            nameView.text = entry.name

            val enabled = entry.isDirectory || entry.isText
            if (enabled) nameView.setTextColor(defaultNameColors) else nameView.setTextColor(disabledNameColor)
            // Not clickable: no tap, no click sound. Still long-clickable (TreeAdapter
            // sets that before onBindNode), so the menu stays available; a long press
            // still presses the row, so its ripple (the foreground) shows as well.
            itemView.isClickable = enabled
        }

        /** Indicator and icon: both depend on expansion and load state. */
        fun bindExpansion(node: TreeNode<FileTreeEntry>) {
            val isLoading = node.loadState == TreeNode.LoadState.LOADING
            progressView.isVisible = isLoading
            arrowView.text = when {
                isLoading || !isExpandable(node) -> ""
                node.isExpanded -> "▼"
                else -> "▶"
            }
            if (node.loadState == TreeNode.LoadState.LOAD_FAILED) {
                arrowView.setTextColor(ERROR_COLOR)
            } else {
                arrowView.setTextColor(defaultArrowColors)
            }
            iconView.text = if (node.value.isDirectory) "📁" else "📄"
            iconView.alpha = when {
                node.value.isDirectory && node.isKnownEmpty -> EMPTY_DIRECTORY_ALPHA
                !node.value.isDirectory && !node.value.isText -> DISABLED_ICON_ALPHA
                else -> 1f
            }
        }

        fun bindHighlight(isHighlighted: Boolean) {
            if (isHighlighted) itemView.setBackgroundColor(SELECTED_COLOR) else itemView.background = null
        }
    }
}
