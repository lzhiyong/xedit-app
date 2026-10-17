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

package x.editor.app.service

import android.net.Uri

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The writes of documents and of their sessions, for the whole process: they are
 * not cancelled with the activity that started them.
 *
 * When the editor screen is recreated (another theme, the night mode), the open
 * documents are written back as it goes (see EditorActivity.onDestroy), and the
 * new screen reads them again. These writes must run to the end although the screen
 * that started them is gone, and the new one must not read a file before the write
 * of it is through: [await].
 *
 * The writes of a document run one after the other. Main thread only.
 */
internal object DocumentSaves {

    // Main: the writes take what they write from the editors there (the IO itself
    // switches threads, see EditorService)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // the last write of each document, until it is through
    private val last = HashMap<Uri, Deferred<Boolean>>()

    /**
     * Runs [write] for [uri] after the writes of it before.
     *
     * @return the write, completing with its result
     */
    fun launch(uri: Uri, write: suspend () -> Boolean): Deferred<Boolean> {
        val previous = last[uri]
        val save = scope.async {
            previous?.join()
            write()
        }
        last[uri] = save
        save.invokeOnCompletion {
            // on the main thread: the write ran there
            if (last[uri] === save) last.remove(uri)
        }
        return save
    }

    /** Runs [block] outside of any activity, like a write (a session to store, say). */
    fun launchDetached(block: suspend () -> Unit): Job = scope.launch { block() }

    /** Returns once the writes of [uri] that were started are through. */
    suspend fun await(uri: Uri) {
        last[uri]?.join()
    }

    /** Returns once every write that was started is through. */
    suspend fun awaitAll() {
        last.values.toList().forEach { it.join() }
    }
}
