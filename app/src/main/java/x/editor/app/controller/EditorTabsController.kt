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

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope

import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
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
 * A document still being loaded ([setLoading]: a large file) while another one is shown
 * has a ring before its name; the one shown has none, the bar under the toolbar says it.
 * Not for one that takes only a moment (LOADING_DELAY_MILLIS), whose tab would jump for
 * nothing; left while it loads, the ring is there at once.
 *
 * With dozens of tabs, scrolling the row to find one is slow: [showOpenFiles] lists them
 * all in a popup (a tap on the title of the toolbar), the one shown marked, and a tap on
 * a row selects its tab as a tap on the tab would.
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

    // the documents still being loaded
    private val loading = HashSet<Uri>()

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

    // the list of open files while it is shown (showOpenFiles)
    private var openFilesPopup: ListPopupWindow? = null

    // full screen: the row is hidden whatever it holds (setHidden)
    private var hidden = false

    /**
     * Something the tabs show changed: which one is selected, which are open, a name, the
     * dot of unsaved changes (the slim bar of full screen shows them too).
     */
    var onChanged: (() -> Unit)? = null

    /** The documents of the tabs, in their order. */
    val uris: List<Uri>
        get() = (0 until tabLayout.tabCount).mapNotNull { tabLayout.getTabAt(it)?.tag as? Uri }

    val selectedUri: Uri?
        get() = tabLayout.getTabAt(tabLayout.selectedTabPosition)?.tag as? Uri

    fun attach() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                styleTab(tab, selected = true)
                bindLoading(tab)
                onChanged?.invoke()
                if (changing) return
                (tab.tag as? Uri)?.let(onSelect)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                styleTab(tab, selected = false)
                bindLoading(tab, atOnce = true)
            }

            // a tap on the selected tab: nothing to switch to
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        // the list of open files goes with the activity (a rotation, say), not leaked
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                openFilesPopup?.dismiss()
            }
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
                loading.remove(uri)
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
        if (loading.remove(oldUri)) loading.add(newUri)
        if (newName != null) {
            setName(tab, newName)
        } else {
            loadName(tab, newUri)
        }
        persist()
    }

    /** The name on the tab of [uri]; null if it has none. */
    fun tabName(uri: Uri): String? = tabAt(uri)?.text?.toString()

    /** Whether the tab of [uri] shows unsaved changes. */
    fun isModified(uri: Uri): Boolean = uri in modified

    /** Full screen: the row of tabs hidden (true), or shown again while there are tabs. */
    fun setHidden(hidden: Boolean) {
        if (this.hidden == hidden) return
        this.hidden = hidden
        updateVisibility()
        // the selected tab in view when the row is back
        if (!hidden) selectedUri?.let { uri -> tabAt(uri)?.let { tab -> tabLayout.post { tabLayout.setScrollPosition(tab.position, 0f, true) } } }
    }

    /** Shows whether [uri] has unsaved changes (a dot instead of the cross). */
    fun setModified(uri: Uri, isModified: Boolean) {
        val changed = if (isModified) modified.add(uri) else modified.remove(uri)
        if (changed) tabAt(uri)?.let { bindCloseButton(it) }
    }

    /** Whether [uri] is still being loaded (a ring before its name, while its tab is not the selected one). */
    fun setLoading(uri: Uri, isLoading: Boolean) {
        val changed = if (isLoading) loading.add(uri) else loading.remove(uri)
        if (changed) tabAt(uri)?.let { bindLoading(it) }
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

    // #region the list of open files

    /**
     * Shows the open documents in a list under [anchor] (the toolbar), one row a tab, in the
     * order of the tabs: the document shown is the selected row (a tinted background, its
     * name in the primary color and bold) and is scrolled to the middle; a dot marks unsaved
     * changes. A tap on a row selects its tab (the activity hears of it as of a tap on the
     * tab) and scrolls the row of tabs to it. The list is no taller than a few rows
     * (`open_files_visible_rows`, and the room below the toolbar): more, and it scrolls.
     *
     * @param textStart where the title's text starts in [anchor], so the names in the list
     *   line up under it
     */
    fun showOpenFiles(anchor: View, textStart: Int = 0) {
        openFilesPopup?.dismiss()
        val entries = uris
        if (entries.isEmpty()) {
            Toast.makeText(activity, R.string.open_files_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val shown = selectedUri
        val resources = activity.resources
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val rowHeight = resources.getDimensionPixelSize(R.dimen.open_files_row_height)
        val listTop = dp(2)
        val listBottom = dp(6)

        val primary = MaterialColors.getColor(anchor, androidx.appcompat.R.attr.colorPrimary)
        val onSurface = MaterialColors.getColor(anchor, MaterialR.attr.colorOnSurface)
        val muted = MaterialColors.getColor(anchor, MaterialR.attr.colorOnSurfaceVariant)

        // the header: how many are open; it stays while the rows scroll
        val header = LayoutInflater.from(activity).inflate(R.layout.view_open_files_header, null) as TextView
        header.text = activity.getString(R.string.open_files_title, entries.size)

        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: LayoutInflater.from(parent.context).inflate(R.layout.item_open_file, parent, false)
                val uri = entries[position]
                val current = uri == shown
                val isModified = uri in modified
                val label = tabName(uri) ?: fallbackName(uri)

                val row = view.findViewById<View>(R.id.open_file_row)
                row.background = if (current) {
                    GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(MaterialColors.compositeARGBWithAlpha(primary, SELECTED_ROW_ALPHA))
                    }
                } else {
                    null
                }
                // read out as selected by a screen reader
                row.isSelected = current

                with(view.findViewById<TextView>(R.id.open_file_name)) {
                    text = label
                    setTextColor(if (current) primary else onSurface)
                    typeface = if (current) SELECTED_TYPEFACE else UNSELECTED_TYPEFACE
                }
                with(view.findViewById<ImageView>(R.id.open_file_icon)) {
                    imageTintList = ColorStateList.valueOf(if (current) primary else muted)
                }
                with(view.findViewById<ImageView>(R.id.open_file_modified)) {
                    visibility = if (isModified) View.VISIBLE else View.GONE
                    imageTintList = ColorStateList.valueOf(primary)
                }
                view.contentDescription =
                    if (isModified) activity.getString(R.string.tab_modified_description, label) else label
                return view
            }
        }

        // as wide as fits, up to open_files_max_width; the names line up under the title,
        // as far as the screen lets them (the row's padding, icon and gap before the name)
        val window = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        val margin = dp(8)
        val popupWidth = minOf(resources.getDimensionPixelSize(R.dimen.open_files_max_width), window.width() - 2 * margin)
        val anchorOnScreen = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val nameInset = dp(6 + 12 + 18 + 12)
        val popupLeft = (anchorOnScreen[0] + textStart - nameInset)
            .coerceIn(window.left + margin, maxOf(window.left + margin, window.right - margin - popupWidth))

        // as tall as its rows, at most a few and a half (the half says there are more), and
        // never past the bottom of the window
        header.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxRows = resources.getInteger(R.integer.open_files_visible_rows)
        val rowsHeight = if (entries.size > maxRows) rowHeight * maxRows + rowHeight / 2 else rowHeight * entries.size
        val room = window.bottom - (anchorOnScreen[1] + anchor.height) - margin
        val popupHeight = minOf(header.measuredHeight + listTop + rowsHeight + listBottom, room)
            .coerceAtLeast(header.measuredHeight + listTop + rowHeight + listBottom)

        val popup = ListPopupWindow(activity)
        openFilesPopup = popup
        with(popup) {
            anchorView = anchor
            setAdapter(adapter)
            isModal = true
            setWidth(popupWidth)
            setHeight(popupHeight)
            horizontalOffset = popupLeft - anchorOnScreen[0]
            promptPosition = ListPopupWindow.POSITION_PROMPT_ABOVE
            setPromptView(header)
            setListSelector(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setBackgroundDrawable(
                // as the other menus of the screen: set off from the editor (bg_menu_popup)
                ContextCompat.getDrawable(activity, R.drawable.bg_menu_popup_no_padding)
            )
            inputMethodMode = ListPopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnItemClickListener { _, _, position, _ ->
                dismiss()
                selectFromList(entries[position])
            }
            setOnDismissListener { if (openFilesPopup === popup) openFilesPopup = null }
            show()
        }

        // the document shown, in the middle of the list
        popup.listView?.let { list ->
            list.setPadding(0, listTop, 0, listBottom)
            list.clipToPadding = false
            list.isVerticalScrollBarEnabled = entries.size > maxRows
            val index = entries.indexOf(shown)
            if (index >= 0) {
                val visible = popupHeight - header.measuredHeight - listTop - listBottom
                list.setSelectionFromTop(index, ((visible - rowHeight) / 2).coerceAtLeast(0))
            }
        }
    }

    // a row of the list of open files: its tab selected as by a tap on it (the activity
    // shows its document), and the row of tabs scrolled to it
    private fun selectFromList(uri: Uri) {
        val tab = tabAt(uri) ?: return
        if (!tab.isSelected) tab.select()
        tabLayout.post { tabLayout.setScrollPosition(tab.position, 0f, true) }
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
        bindLoading(tab)
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
            bindLoading(tab)
        }
    }

    // The ring of [tab], in the color of the indicator, only while its document loads and
    // another tab is selected: out a moment after it began ([atOnce]: now), if it is still
    // at it; away as soon as it is done, or its tab is selected.
    private fun bindLoading(tab: TabLayout.Tab, atOnce: Boolean = false) {
        val ring = tab.customView?.findViewById<CircularProgressIndicator>(R.id.tab_progress) ?: return
        val uri = tab.tag as? Uri ?: return
        ring.setIndicatorColor(
            colors?.indicator ?: MaterialColors.getColor(tabLayout, androidx.appcompat.R.attr.colorPrimary)
        )
        val show = (ring.tag as? Runnable) ?: Runnable {
            if ((tab.tag as? Uri)?.let { it in loading && !tab.isSelected } == true) ring.visibility = View.VISIBLE
        }.also { ring.tag = it }
        ring.removeCallbacks(show)
        when {
            uri !in loading || tab.isSelected -> ring.visibility = View.GONE
            atOnce -> ring.visibility = View.VISIBLE
            ring.visibility != View.VISIBLE -> ring.postDelayed(show, LOADING_DELAY_MILLIS)
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
        // its name or its dot changed
        if (tab.parent != null) onChanged?.invoke()
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

    @SuppressLint("RestrictedApi")
    private fun showMenu(anchor: View, uri: Uri) {
        val all = uris
        PopupMenu(activity, anchor).apply {
            menuInflater.inflate(R.menu.editor_tab_popup, menu)
            menu.findItem(R.id.tab_menu_close_others)?.isEnabled = all.size > 1
            // the icons before the items (as in the menu of the file tree); an item that
            // cannot be used has a faint one, as its text is
            (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
            for (index in 0 until menu.size()) {
                val item = menu.getItem(index)
                item.icon = item.icon?.mutate()?.apply { alpha = if (item.isEnabled) 255 else DISABLED_ICON_ALPHA }
            }
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
        tabLayout.visibility = if (tabLayout.tabCount > 0 && !hidden) View.VISIBLE else View.GONE
        onChanged?.invoke()
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
        // a document that takes longer than this to load shows its ring
        const val LOADING_DELAY_MILLIS = 250L

        // the uris of the tabs, as a JSON array, in the activity preferences
        const val PREF_OPEN_TABS = "open_tabs"

        // the background of the row of the document shown in the list of open files: the
        // primary color, faint (about 12%)
        const val SELECTED_ROW_ALPHA = 31

        // the icon of a menu item that cannot be used (Material's disabled opacity, 38%)
        const val DISABLED_ICON_ALPHA = 97

        val SELECTED_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val UNSELECTED_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
