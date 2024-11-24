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
 
@file:JvmName("RefCleaner")

package io.github.module.treesitter

import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.TIRAMISU
import java.lang.ref.Cleaner

internal object RefCleaner {
    private val INSTANCE = if (SDK_INT < TIRAMISU) null else Cleaner.create()

    @JvmName("register")
    operator fun invoke(obj: Any, action: Runnable) {
        if (SDK_INT >= TIRAMISU) INSTANCE!!.register(obj, action)
    }
}
