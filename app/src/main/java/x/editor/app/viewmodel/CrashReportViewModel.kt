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

package x.editor.app.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import x.github.module.crash.CrashLogStore
import x.github.module.crash.CrashReportUploader

import x.editor.app.crash.CrashLogExporter
import x.editor.app.crash.CrashReportFormatter

/**
 * Holds everything CrashActivity must not lose on a configuration change.
 *
 * The crash logs are deleted from disk as soon as they are loaded, so from
 * then on the text exists only here. It survives rotation, but not the
 * :crash process being killed - by design, seen logs are not kept.
 * Exporting is how the user keeps a copy.
 */
class CrashReportViewModel(application: Application) : AndroidViewModel(application) {

    enum class Exit { CLOSE, RESTART }

    sealed interface State {
        object Loading : State
        object Empty : State
        class Loaded(val text: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var loadStarted = false

    // Queuing a report (staging + WorkManager enqueue); short, but leaving waits for it
    var reportJob: Job? = null
        private set

    // Set once the report was queued successfully: one report per screen
    var reportQueued = false
        private set

    // Writing the export file; leaving waits for it, so no half-written file is left
    var exportJob: Job? = null
        private set

    // Set once the user asked to leave; the exit runs after reportJob/exportJob finish
    var pendingExit: Exit? = null

    val isReporting: Boolean
        get() = reportJob?.isActive == true

    val isExporting: Boolean
        get() = exportJob?.isActive == true

    /** Loads (and deletes) every unseen crash log, once per screen. */
    fun load(inlineText: String?) {
        if (loadStarted) return
        loadStarted = true

        viewModelScope.launch {
            val crashes = withContext(Dispatchers.IO) {
                CrashLogStore.consumeAll(getApplication())
            }
            // newest first, as the store returns them
            val text = CrashReportFormatter.format(
                crashes.map {
                    CrashReportFormatter.Entry(it.time, it.type?.label, it.count, it.content)
                },
                inlineText
            )
            _state.value = if (text.isEmpty()) State.Empty else State.Loaded(text)
        }
    }

    /**
     * Queues [content] for upload to [url]. The upload itself runs in
     * WorkManager and does not depend on this screen staying open.
     *
     * @return false if a report is already being queued or was queued
     */
    fun submitReport(url: String, content: String): Boolean {
        if (isReporting || reportQueued) return false

        val app = getApplication<Application>()
        reportJob = viewModelScope.launch {
            val queued = try {
                withContext(Dispatchers.IO) {
                    CrashReportUploader.enqueue(app, url, content)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "failed to queue crash report", t)
                false
            }

            reportQueued = queued
            Toast.makeText(
                app,
                if (queued) "报告已提交，将在联网时自动上传" else "报告提交失败，请重试",
                Toast.LENGTH_SHORT
            ).show()
        }
        return true
    }

    /**
     * Exports [content] as a text file into the public Download directory.
     * Every export creates a new file, so exporting twice is allowed.
     *
     * The caller must already hold WRITE_EXTERNAL_STORAGE on API < 29.
     *
     * @return false if an export is already running
     */
    fun exportLog(content: String): Boolean {
        if (isExporting) return false

        val app = getApplication<Application>()
        exportJob = viewModelScope.launch {
            val location = try {
                withContext(Dispatchers.IO) {
                    CrashLogExporter.export(app, content)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "failed to export crash log", t)
                null
            }

            Toast.makeText(
                app,
                if (location != null) "已导出到 $location" else "导出失败，请重试",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    private companion object {
        const val TAG = "CrashReportViewModel"
    }
}
