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

package x.editor.app.preference

import androidx.annotation.LayoutRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat

import x.editor.app.R

/**
 * Gives a screen of preferences the Material 3 look of the settings: each group of
 * rows (the preferences of a category, or the ones between two categories) is one
 * card, with large outer and small inner corners and a thin gap between the rows,
 * a header in the primary color over it, the icons in tonal circles, and Material 3
 * switches.
 *
 * It only sets the layouts of the preferences (public API of androidx.preference),
 * so the adapter of the screen is the library's own. Which of the four row layouts
 * a preference gets depends on its place in its group, so call [apply] again after
 * making a preference visible or invisible.
 */
object CardPreferenceStyler {

    fun apply(group: PreferenceGroup) {
        val run = ArrayList<Preference>()
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (!preference.isVisible) continue
            if (preference is KeepsLayout) {
                // a layout of its own: it stands apart, between groups
                styleRun(run)
                run.clear()
                continue
            }
            if (preference is PreferenceCategory) {
                // a category ends the run before it, and its rows are a group of their own
                styleRun(run)
                run.clear()
                preference.layoutResource = R.layout.preference_category_md3
                apply(preference)
            } else {
                if (preference is SwitchPreferenceCompat) {
                    preference.widgetLayoutResource = R.layout.preference_widget_material_switch
                }
                run += preference
            }
        }
        styleRun(run)
    }

    private fun styleRun(run: List<Preference>) {
        run.forEachIndexed { index, preference ->
            preference.layoutResource = layoutFor(index, run.size)
        }
    }

    @LayoutRes
    private fun layoutFor(index: Int, count: Int): Int = when {
        count == 1 -> R.layout.preference_card_single
        index == 0 -> R.layout.preference_card_top
        index == count - 1 -> R.layout.preference_card_bottom
        else -> R.layout.preference_card_middle
    }
}
