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

package x.editor.app.controller

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope

import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONException

import x.editor.app.R

import com.google.android.material.R as MaterialR

/**
 * The tabs of the open documents, over the editor ([tabLayout]).
 *
 * Each tab stands for an open document, which has an editor of its own (see
 * EditorActivity): selecting a tab shows that editor, with its text, unsaved
 * changes, undo history and cursor as they were.
 *
 * This class owns the tabs and their order, and keeps them in [preferences] for the
 * next start; what happens to the documents is the activity's. It changes the tabs
 * when told to (from the activity, silently), and tells the activity what the user
 * did: selected a tab ([onSelect]) or asked to close some ([onClose]).
 *
 * @param nameOf the name of a document, for its tab; called off the main thread
 * @param onSelect the user selected the tab of [Uri]
 * @param onClose the user asked to close the tabs of these documents; the activity
 *   removes them (see [remove]) once the unsaved changes are saved or let go
 */
internal class EditorTabsController(
    private val activity: AppCompatActivity,
    private val tabLayout: TabLayout,
    private val preferences: SharedPreferences,
    private val nameOf: (Uri) -> String,
    private val onSelect: (Uri) -> Unit,
    private val onClose: (List<Uri>) -> Unit,
    // the file of a tab to another app (its menu)
    private val onShare: (Uri) -> Unit
) {

    // Set while the tabs are changed from here: TabLayout reports selections it
    // makes by itself (removing the selected tab selects another), which are not
    // the user's.
    private var changing = false

    // the documents with unsaved changes, whose tab shows a dot
    private val modified = HashSet<Uri>()

    /**
     * The colors of the tabs, to match the editor: [background] of the row, the name
     * on the [selected] tab and on the others ([unselected]), the [indicator] under
     * the selected one (and the dot of unsaved changes).
     */
    data class Colors(val background: Int, val selected: Int, val unselected: Int, val indicator: Int)

    // null: the colors of the app theme
    private var colors: Colors? = null

    // what the app theme gives the row, to go back to
    private val defaultBackground = tabLayout.background

    /** The documents of the tabs, in their order. */
    val uris: List<Uri>
        get() = (0 until tabLayout.tabCount).mapNotNull { tabLayout.getTabAt(it)?.tag as? Uri }

    val selectedUri: Uri?
        get() = tabLayout.getTabAt(tabLayout.selectedTabPosition)?.tag as? Uri

    fun attach() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                styleTab(tab, selected = true)
                if (changing) return
                (tab.tag as? Uri)?.let(onSelect)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                styleTab(tab, selected = false)
            }

            // a tap on the selected tab: nothing to switch to
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    // #region changes from the activity (silent: onSelect is not called)

    /**
     * Adds a tab for [uri] if there is none: after the selected tab, or at the end.
     * Leaves the selection as it is.
     */
    fun add(uri: Uri) {
        if (indexOf(uri) >= 0) return
        val position = if (tabLayout.selectedTabPosition >= 0) tabLayout.selectedTabPosition + 1 else tabLayout.tabCount
        silently {
            tabLayout.addTab(createTab(uri), position, false)
        }
        updateVisibility()
        persist()
    }

    /** Adds a tab for [uri] if there is none (see [add]), and selects it. */
    fun addAndSelect(uri: Uri) {
        add(uri)
        select(uri)
    }

    /** Selects the tab of [uri] and scrolls it into view. */
    fun select(uri: Uri) {
        val tab = tabAt(uri) ?: return
        if (!tab.isSelected) {
            silently { tab.select() }
        }
        tabLayout.post { tabLayout.setScrollPosition(tab.position, 0f, true) }
    }

    /** No tab is selected: the editor shows no document of the tabs. */
    fun clearSelection() {
        silently { tabLayout.selectTab(null) }
    }

    /** Removes the tabs of [removed]; the selection goes wherever TabLayout puts it. */
    fun remove(removed: Collection<Uri>) {
        if (removed.isEmpty()) return
        silently {
            removed.forEach { uri ->
                tabAt(uri)?.let { tabLayout.removeTab(it) }
                modified.remove(uri)
            }
        }
        updateVisibility()
        persist()
    }

    /**
     * The tab next to [uri] that a closing tab hands over to: the one after it, or
     * else the one before it; none of [excluding]. Null if there is none.
     */
    fun neighborOf(uri: Uri, excluding: Collection<Uri> = emptyList()): Uri? {
        val all = uris
        val index = all.indexOf(uri)
        if (index < 0) return all.firstOrNull { it !in excluding && it != uri }
        return all.drop(index + 1).firstOrNull { it !in excluding }
            ?: all.take(index).lastOrNull { it !in excluding }
    }

    /** [oldUri] is [newUri] now (renamed, or a folder above it was); [newName] if its name changed. */
    fun rename(oldUri: Uri, newUri: Uri, newName: String? = null) {
        val tab = tabAt(oldUri) ?: return
        tab.setTag(newUri)
        if (oldUri in modified) {
            modified.remove(oldUri)
            modified.add(newUri)
        }
        if (newName != null) {
            setName(tab, newName)
        } else {
            loadName(tab, newUri)
        }
        persist()
    }

    /** The name on the tab of [uri]; null if it has none. */
    fun tabName(uri: Uri): String? = tabAt(uri)?.text?.toString()

    /** Shows whether [uri] has unsaved changes (a dot instead of the cross). */
    fun setModified(uri: Uri, isModified: Boolean) {
        val changed = if (isModified) modified.add(uri) else modified.remove(uri)
        if (changed) tabAt(uri)?.let { bindCloseButton(it) }
    }

    /** The documents of the tabs of the last run, in their order. */
    fun savedUris(): List<Uri> {
        val json = preferences.getString(PREF_OPEN_TABS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    // #endregion

    // #region the tabs

    private fun indexOf(uri: Uri): Int = uris.indexOf(uri)

    private fun tabAt(uri: Uri): TabLayout.Tab? =
        (0 until tabLayout.tabCount).asSequence()
            .mapNotNull { tabLayout.getTabAt(it) }
            .firstOrNull { it.tag == uri }

    private fun createTab(uri: Uri): TabLayout.Tab {
        val view = LayoutInflater.from(tabLayout.context).inflate(R.layout.item_editor_tab, tabLayout, false)
        val tab = tabLayout.newTab().setCustomView(view)
        tab.setTag(uri)
        // a name at once, from the uri; the real one when it is read
        setName(tab, fallbackName(uri))
        loadName(tab, uri)
        bindCloseButton(tab)
        styleTab(tab, selected = false)
        colorName(tab)

        // a long press: close it, the others, all; copy its path
        tab.view.setOnLongClickListener { anchor ->
            (tab.tag as? Uri)?.let { showMenu(anchor, it) }
            true
        }
        return tab
    }

    /** Colors the tabs like the editor ([Colors]); null goes back to the app theme. */
    fun setColors(colors: Colors?) {
        this.colors = colors
        if (colors != null) {
            tabLayout.setBackgroundColor(colors.background)
            tabLayout.setSelectedTabIndicatorColor(colors.indicator)
        } else {
            tabLayout.background = defaultBackground
            tabLayout.setSelectedTabIndicatorColor(
                MaterialColors.getColor(tabLayout, androidx.appcompat.R.attr.colorPrimary)
            )
        }
        (0 until tabLayout.tabCount).mapNotNull { tabLayout.getTabAt(it) }.forEach { tab ->
            colorName(tab)
            bindCloseButton(tab)
        }
    }

    // the name: the selector of the layout (primary when selected), or the colors set
    private fun colorName(tab: TabLayout.Tab) {
        val name = tab.customView?.findViewById<TextView>(android.R.id.text1) ?: return
        val scheme = colors
        name.setTextColor(
            if (scheme != null) {
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                    intArrayOf(scheme.selected, scheme.unselected)
                )
            } else {
                androidx.core.content.ContextCompat.getColorStateList(tabLayout.context, R.color.editor_tab_text)
            }
        )
    }

    // The name on the selected tab, the document shown, stands out: in the primary
    // color (the selector of the view, see item_editor_tab) and bold.
    private fun styleTab(tab: TabLayout.Tab, selected: Boolean) {
        val name = tab.customView?.findViewById<TextView>(android.R.id.text1) ?: return
        name.typeface = if (selected) SELECTED_TYPEFACE else UNSELECTED_TYPEFACE
    }

    private fun bindCloseButton(tab: TabLayout.Tab) {
        val button = tab.customView?.findViewById<ImageButton>(R.id.tab_close) ?: return
        val uri = tab.tag as? Uri ?: return
        val isModified = uri in modified
        button.setImageResource(if (isModified) R.drawable.ic_tab_modified else R.drawable.ic_close)
        // the dot in the accent color, so that it is seen
        val scheme = colors
        button.imageTintList = ColorStateList.valueOf(
            when {
                scheme != null -> if (isModified) scheme.indicator else scheme.unselected
                else -> MaterialColors.getColor(
                    button,
                    if (isModified) androidx.appcompat.R.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant
                )
            }
        )
        button.setOnClickListener { (tab.tag as? Uri)?.let { onClose(listOf(it)) } }
        updateDescription(tab)
    }

    private fun setName(tab: TabLayout.Tab, name: String) {
        tab.setText(name)
        updateDescription(tab)
    }

    private fun updateDescription(tab: TabLayout.Tab) {
        val name = tab.text?.toString().orEmpty()
        val uri = tab.tag as? Uri
        tab.setContentDescription(
            if (uri != null && uri in modified) activity.getString(R.string.tab_modified_description, name) else name
        )
    }

    private fun loadName(tab: TabLayout.Tab, uri: Uri) {
        activity.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { runCatching { nameOf(uri) }.getOrNull() }
            // the tab may be gone, or stand for another document by now
            if (!name.isNullOrEmpty() && tab.tag == uri && tab.parent != null) {
                setName(tab, name)
            }
        }
    }

    // "primary:Download/a.txt" → "a.txt"
    private fun fallbackName(uri: Uri): String =
        uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':').ifEmpty { uri.toString() }

    private fun showMenu(anchor: View, uri: Uri) {
        val all = uris
        PopupMenu(activity, anchor).apply {
            menuInflater.inflate(R.menu.editor_tab_popup, menu)
            menu.findItem(R.id.tab_menu_close_others)?.isEnabled = all.size > 1
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.tab_menu_close -> onClose(listOf(uri))
                    R.id.tab_menu_close_others -> onClose(all.filter { it != uri })
                    R.id.tab_menu_close_all -> onClose(all)
                    R.id.tab_menu_share -> onShare(uri)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun updateVisibility() {
        tabLayout.visibility = if (tabLayout.tabCount > 0) View.VISIBLE else View.GONE
    }

    private fun persist() {
        val array = JSONArray()
        uris.forEach { array.put(it.toString()) }
        preferences.edit { putString(PREF_OPEN_TABS, array.toString()) }
    }

    private inline fun silently(block: () -> Unit) {
        val wasChanging = changing
        changing = true
        try {
            block()
        } finally {
            changing = wasChanging
        }
    }

    // #endregion

    private companion object {
        // the uris of the tabs, as a JSON array, in the activity preferences
        const val PREF_OPEN_TABS = "open_tabs"

        val SELECTED_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val UNSELECTED_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
