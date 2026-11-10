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

package x.editor.app.config

import android.content.Context

import com.google.android.material.color.MaterialColors

import com.google.android.material.R as MaterialR

/**
 * How an entry of a panel of the developer tools is doing, in one of five levels, each with
 * its colour, the same on every panel (the dot of a row, of the status card, of an info
 * window). Red is kept for what the user has to see to:
 *
 *  - [GOOD]: working, usable (running, installed, rules right, N snippets);
 *  - [WARN]: not in the way, or waiting (starting, not running yet, an update, not saved, a draft);
 *  - [BAD]: to be seen to (wrong, failed to start or connect, on but not installed, install failed);
 *  - [QUIET]: turned off by the user, empty, nothing to do (off, the defaults, not set);
 *  - [ACTIVE]: being edited, going on, in use.
 */
internal enum class StateLevel {
    GOOD, WARN, BAD, QUIET, ACTIVE;

    /** The colour of the level, in [context]'s theme (QUIET and ACTIVE follow it). */
    fun color(context: Context): Int = when (this) {
        GOOD -> GOOD_COLOR
        WARN -> WARN_COLOR
        BAD -> BAD_COLOR
        QUIET -> MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, 0xFF9E9E9E.toInt())
        ACTIVE -> MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF82AAFF.toInt())
    }

    companion object {
        const val GOOD_COLOR = 0xFF98C379.toInt()
        const val WARN_COLOR = 0xFFE5C07B.toInt()
        const val BAD_COLOR = 0xFFE06C75.toInt()
    }
}
