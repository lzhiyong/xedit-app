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

package x.code.app.activity

import android.content.Context
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder

import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread

import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.lang.SecurityException
import java.io.InputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.charset.Charset
import java.lang.Runtime
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.security.MessageDigest

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.cbor.*

import x.code.app.databinding.ActivityEditorBinding
import x.code.app.model.MainViewModel
import x.code.app.model.TreeSitter
import x.code.app.model.DatabaseManager
import x.code.app.model.DownloadManager
import x.code.app.util.DeviceUtils
import x.code.app.util.FileUtils
import x.code.app.util.PackageUtils
import x.code.app.R

import x.github.module.document.DocumentFile
import x.github.module.editor.view.EditorView
import x.github.module.editor.SavedState
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.PieceTreeTextBufferBuilder


public class EditorService : Service() {
    
    private val digest by lazy { MessageDigest.getInstance("SHA-256") }  
    
    inner class ServiceBinder : Binder() {
        fun getService() = this@EditorService
    }
    
    override fun onCreate() {
        super.onCreate()        
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return ServiceBinder()
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
    
    @WorkerThread
    suspend fun initTreeSitter(
        fileName: String,
        treeSitter: TreeSitter,
        textBuffer: PieceTreeTextBuffer
    ) {
        treeSitter.init(fileName, textBuffer)
    }
    
    @WorkerThread
    suspend fun loadTreeSitter(
        styleName: String,
        treeSitter: TreeSitter
    ) {
        treeSitter.load(DeviceUtils.getArchName(), styleName)
    }
    
    @WorkerThread
    suspend fun getDownloadLinks(): Map<String, Pair<File, File>> {
        // check the network state
        if (DownloadManager.isNetworkAvailable(this@EditorService)) {
            val versionName = PackageUtils.getVersionName(this@EditorService)
            // database for save the download links
            val database = DatabaseManager.getInstance(this@EditorService).entityDao()
            val baseUrl = getString(R.string.download_base_url)
                        
            // the pair first is outputFile, pair second is extractFile
            val links = mutableMapOf(
                "$baseUrl/v$versionName/tree-sitter.zip" to 
                Pair(File(getCacheDir(), "tree-sitter.zip"), getFilesDir())                
            )

            // check need download
            val needDownloadLinks = links.filter {
                database.query(it.key) == null
            }
            
            val needUpdateLinks = links.filter {
                val headerEntity = database.query(it.key)
                headerEntity != null &&
                DownloadManager.validate(it.key, headerEntity.etag)
            }
            return needDownloadLinks + needUpdateLinks
        }
        return mapOf<String, Pair<File, File>>()
    }
    
    @WorkerThread
    suspend fun downloadFile(
        urlLink: String,
        saveToFile: File,
        progressCallback: (Int) -> Unit,
        finishCallback: (File, String) -> Unit,
        errorCallback: (String) -> Unit
    ) {
        DownloadManager.downloadFile(
            urlLink, 
            saveToFile, 
            progressCallback,
            finishCallback,
            errorCallback
        )
    }
    
    @WorkerThread
    suspend fun readFile(
        uri: Uri,
        encoding: Charset = Charsets.UTF_8
    ): PieceTreeTextBuffer {
        val pieceBuilder = PieceTreeTextBufferBuilder()        
        contentResolver.openInputStream(uri)?.use { fis ->            
            BufferedInputStream(fis).use { bis ->
                // read in 64KB chunks                
                val buffer = ByteArray(64 * 1024) 
                var bytes: Int = 0
                while (bis.read(buffer).also { bytes = it } != -1) {
                    pieceBuilder.acceptChunk(String(buffer, 0, bytes, encoding))                    
                }
            }
        }        
        return pieceBuilder.build()
    }
    
    @WorkerThread
    suspend fun writeFile(
        uri: Uri,
        textBuffer: PieceTreeTextBuffer,
        encoding: Charset = Charsets.UTF_8
    ) {        
        contentResolver.openOutputStream(uri, "wt")?.use { fos ->
            BufferedOutputStream(fos).use { bos ->                
                // read the pieces content
                textBuffer.readPiecesContent { content ->
                    content.toByteArray(encoding).also {
                        bos.write(it)
                        digest.update(it)
                    }
                }
            }
        }
    }
    
    @AnyThread
    fun getSerializeName(uri: Uri): String {
        return FileUtils.bytesToHex(uri.toString().toByteArray())
    }
    
    @AnyThread
    fun getSerializeFile(uri: Uri): File {
        return File(getFilesDir(), "serialize/${getSerializeName(uri)}")  
    }
    
    @WorkerThread
    suspend fun serializeStream(
        file: File, 
        callback: (BufferedOutputStream) -> Unit,
        error: ((Exception) -> Unit)? = null
    ) {
        try {
            FileOutputStream(file).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    callback.invoke(bos)
                }
            }
        } catch(e: Exception) {
            error?.invoke(e)
        }
    }
    
    @WorkerThread
    suspend fun deserializeStream(
        file: File, 
        callback: (BufferedInputStream) -> Unit,
        error: ((Exception) -> Unit)? = null
    ) {        
        try {
            FileInputStream(file).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    callback.invoke(bis)
                }
            }
        } catch (e: Exception) {
            error?.invoke(e)        
        }
    }
    
    @WorkerThread
    suspend fun serializeTextBuffer(uri: Uri, editor: EditorView) {
        val textBuffer = editor.getTextBuffer()
        val name = getSerializeName(uri)        
        
        // check the serialized dir 
        File(getFilesDir(), "serialize/${name}").apply {
            if(!exists()) mkdirs()
        }
        
        // serialize the PieceTreeTextBuffer
        serializeStream(
            File(getFilesDir(), "serialize/${name}/text_buffer.cbor"),
            callback = { bos ->
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)                
                bos.write(Cbor.encodeToByteArray(textBuffer))                
            },
            error = { e ->
                e.printStackTrace()
            }
        )
    }
    
    @WorkerThread
    suspend fun deserializeTextBuffer(uri: Uri): PieceTreeTextBuffer? {
        var textBuffer: PieceTreeTextBuffer? = null
        val name = getSerializeName(uri)
        deserializeStream(
            File(getFilesDir(), "serialize/${name}/text_buffer.cbor"),
            callback = { bis ->
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                textBuffer = Cbor.decodeFromByteArray<PieceTreeTextBuffer>(bis.readBytes())               
            },
            error = { e ->
                e.printStackTrace()
            }
        )
        return textBuffer
    }
    
    @WorkerThread
    suspend fun serializeEditorState(
        uri: Uri,
        editor: EditorView,    
        viewModel: MainViewModel
    ) {        
        // serialize the SavedState data
        val savedState = editor.preserveState(
            uri.toString(), 
            FileUtils.bytesToHex(digest.digest()), 
            viewModel.isTextChanged.value
        )
        val name = getSerializeName(uri)
        
        // check the serialized dir 
        File(getFilesDir(), "serialize/${name}").apply {
            if(!exists()) mkdirs()
        }
        
        // serialize some datas of the editor
        serializeStream(
            File(getFilesDir(), "serialize/${name}/saved_state.cbor"),
            callback = { bos ->
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)                
                bos.write(Cbor.encodeToByteArray(savedState))                
            },
            error = { e ->
                e.printStackTrace()
            }
        )     
        // resets the digest for further use
        digest.reset()   
    }
    
    @WorkerThread
    suspend fun deserializeEditorState(uri: Uri): SavedState? {
        var savedState: SavedState? = null
        val name = getSerializeName(uri)        
        deserializeStream(
            File(getFilesDir(), "serialize/${name}/saved_state.cbor"),
            callback = { bis ->
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)          
                savedState = Cbor.decodeFromByteArray<SavedState>(bis.readBytes())
            },
            error = { e ->
                e.printStackTrace()
            }
        )
        return savedState
    }
}

