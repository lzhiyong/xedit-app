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

package x.editor.app.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log

import androidx.annotation.AnyThread

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

import x.editor.app.AppDirs
import x.editor.app.highlight.TreeSitterEngine
import x.editor.app.session.SessionFiles
import x.editor.app.util.DeviceUtils
import x.editor.app.util.FileUtils

import x.github.module.editor.model.EditSession
import x.github.module.editor.view.EditorView
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.PieceTreeTextBufferBuilder

/**
 * The file work of the editor: reading and writing documents, keeping their
 * editing sessions, loading the tree-sitter resources (their downloads are
 * TreeSitterUpdater's).
 *
 * Every suspend function here may be called from the main thread: it moves to a
 * background thread by itself where it reads, writes or waits.
 */
public class EditorService : Service() {

    /**
     * The text of a document as it was read.
     *
     * @param contentHash SHA-256 (hex) of the bytes of the file, the same value
     *   [writeDocument] returns for the bytes it wrote
     */
    class LoadedText(
        val buffer: PieceTreeTextBuffer,
        val contentHash: String
    )

    // in the app's home (AppDirs): files/home/sessions, one JSON file per document
    private val sessionFiles by lazy { SessionFiles(File(AppDirs.home(this), SESSION_DIR)) }
    
    inner class LocalBinder : Binder() {
        fun getService() = this@EditorService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    // #region tree-sitter
    
    /**
     * Sets [syntaxEngine] up for the document [fileName] with the text [textBuffer]:
     * picks the grammar and parses the text. Call [TreeSitterEngine.release] on the main
     * thread before, and do not change the text meanwhile.
     */
    suspend fun prepareHighlighting(
        fileName: String,
        syntaxEngine: TreeSitterEngine,
        textBuffer: PieceTreeTextBuffer
    ) = withContext(Dispatchers.IO) {
        syntaxEngine.prepare(fileName, textBuffer)
    }
    
    /** Loads the grammar libraries, the file type map and the style [styleName]. */
    suspend fun loadHighlightResources(
        styleName: String,
        syntaxEngine: TreeSitterEngine
    ) = withContext(Dispatchers.IO) {
        // reads files and loads libraries: not on the main thread
        syntaxEngine.loadResources(DeviceUtils.getArchName(), styleName)
    }

    /** Loads the style [styleName] in place of the one loaded before. */
    suspend fun loadHighlightStyle(
        styleName: String,
        syntaxEngine: TreeSitterEngine
    ) = withContext(Dispatchers.IO) {
        syntaxEngine.loadHighlightStyle(styleName)
    }

    // #endregion

    // #region documents
    
    /**
     * Reads the document at [uri].
     *
     * @throws IOException if it does not exist or cannot be read
     * @throws SecurityException if the permission for it is gone
     */
    @Throws(IOException::class)
    suspend fun readDocument(
        uri: Uri,
        encoding: Charset = Charsets.UTF_8
    ): LoadedText = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val pieceBuilder = PieceTreeTextBufferBuilder()

        val input = contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open $uri for reading")
        // The hash is taken from the bytes on their way to the reader, so the file
        // is read once, and what is hashed is exactly what was loaded.
        //
        // Decoded by a reader, not chunk by chunk: a character of several bytes
        // (any non-ASCII one in UTF-8) can lie across the end of a chunk of
        // bytes, and decoding the two halves separately turns it into garbage.
        // The reader carries the unfinished bytes over to the next read.
        InputStreamReader(DigestInputStream(BufferedInputStream(input), digest), encoding).use { reader ->
            // read in chunks of 64K chars
            val buffer = CharArray(READ_CHUNK_CHARS)
            var chars = reader.read(buffer)
            while (chars != -1) {
                // opening another document cancels this one
                ensureActive()
                pieceBuilder.acceptChunk(String(buffer, 0, chars))
                chars = reader.read(buffer)
            }
        }

        LoadedText(pieceBuilder.build(), FileUtils.bytesToHex(digest.digest()))
    }
    
    /**
     * Writes [textBuffer] to the document at [uri], replacing its content. A byte
     * order mark the text was loaded with is written back.
     *
     * @return SHA-256 (hex) of the bytes written, to be stored with the session
     *   ([saveSession]) so that a later change of the file can be noticed
     * @throws IOException if the document cannot be written
     * @throws SecurityException if the permission for it is gone
     */
    @Throws(IOException::class)
    suspend fun writeDocument(
        uri: Uri,
        textBuffer: PieceTreeTextBuffer,
        encoding: Charset = Charsets.UTF_8
    ): String = withContext(Dispatchers.IO) {
        // A digest of its own for each write: two saves can overlap (the menu and
        // onLowMemory), and a write that fails must not leave half a hash behind
        // for the next one.
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)

        val output = contentResolver.openOutputStream(uri, "wt")
            ?: throw FileNotFoundException("Cannot open $uri for writing")
        BufferedOutputStream(DigestOutputStream(output, digest)).use { bos ->
            val bom = textBuffer.getBOM()
            if (bom.isNotEmpty()) {
                bos.write(bom.toByteArray(encoding))
            }
            // read the pieces content
            textBuffer.readPiecesContent { content ->
                bos.write(content.toByteArray(encoding))
            }
        }

        FileUtils.bytesToHex(digest.digest())
    }

    /**
     * SHA-256 (hex) of the bytes of the document at [uri], as [readDocument] and
     * [writeDocument] give it: to tell whether a file whose stamp changed (see
     * DiskChanges) holds another text than the one in the editor.
     *
     * @throws IOException if it does not exist or cannot be read
     * @throws SecurityException if the permission for it is gone
     */
    @Throws(IOException::class)
    suspend fun hashDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val input = contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open $uri for reading")
        input.use { stream ->
            val buffer = ByteArray(HASH_CHUNK_BYTES)
            var count = stream.read(buffer)
            while (count != -1) {
                // the document was closed, or the screen went: no need for the answer
                ensureActive()
                digest.update(buffer, 0, count)
                count = stream.read(buffer)
            }
        }
        FileUtils.bytesToHex(digest.digest())
    }

    // #endregion

    // #region editing sessions

    /** Whether a session is stored for the document at [uri]. */
    @AnyThread
    fun hasSession(uri: Uri): Boolean = sessionFiles.exists(uri.toString())

    /** The file the session of the document at [uri] is written to. It may not exist. */
    @AnyThread
    fun getSessionFile(uri: Uri): File = sessionFiles.fileFor(uri.toString())
    
    /**
     * Stores the editing session of [editor] (cursor, undo and redo history) for
     * the document at [uri], which must be the one the editor shows.
     *
     * The editor only has a session for a text without unsaved edits: write the
     * document and call `EditorView.markSaved` first. If the text was edited
     * since, nothing is stored, and the session stored before is deleted: it
     * belongs to the content the file had before it was written.
     *
     * @param contentHash the hash of the content of the document that the session
     *   belongs to, as returned by [writeDocument]
     * @return whether a session was stored
     * @throws IOException if the session cannot be written
     */
    @Throws(IOException::class)
    suspend fun saveSession(uri: Uri, editor: EditorView, contentHash: String): Boolean {
        // EditorView.saveSession reads the cursor and the undo history: main thread
        val session = withContext(Dispatchers.Main) {
            editor.saveSession(contentHash)
        }
        return withContext(Dispatchers.IO) {
            if (session == null) {
                sessionFiles.delete(uri.toString())
                false
            } else {
                val bytes = SESSION_JSON.encodeToString(EditSession.serializer(), session)
                    .toByteArray(Charsets.UTF_8)
                sessionFiles.write(uri.toString(), bytes)
                true
            }
        }
    }
    
    /**
     * The session stored for the document at [uri], or null if there is none or it
     * cannot be read. A session that cannot be read (written by another version of
     * the app, or damaged) is deleted.
     */
    suspend fun loadSession(uri: Uri): EditSession? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        try {
            val bytes = sessionFiles.read(key) ?: return@withContext null
            SESSION_JSON.decodeFromString(EditSession.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // IOException, SerializationException, or whatever a decoder makes of
            // bytes that are not what it expects
            Log.w(TAG, "dropping unreadable session of $uri", e)
            sessionFiles.delete(key)
            null
        }
    }

    /**
     * Files the session of the document at [from] under [to]: the document was renamed
     * or moved, its text is the same, so its undo history still fits it.
     */
    suspend fun moveSession(from: Uri, to: Uri) = withContext(Dispatchers.IO) {
        val source = sessionFiles.find(from.toString()) ?: return@withContext
        val target = sessionFiles.fileFor(to.toString())
        if (source == target) return@withContext
        target.delete()
        if (!source.renameTo(target)) {
            // another file system or no space: rather none than one for a wrong uri
            source.delete()
        }
    }

    /** Forgets the session stored for the document at [uri]. */
    suspend fun deleteSession(uri: Uri) = withContext(Dispatchers.IO) {
        sessionFiles.delete(uri.toString())
    }

    // #endregion

    private companion object {
        const val TAG = "EditorService"
        const val HASH_ALGORITHM = "SHA-256"
        const val SESSION_DIR = "sessions"
        const val READ_CHUNK_CHARS = 64 * 1024
        const val HASH_CHUNK_BYTES = 64 * 1024

        // Sessions are JSON, so that one can be read as it is (adb shell run-as
        // <package> cat files/home/sessions/<sha-256>.json | jq). Compact: a session
        // holds the text of its undo steps, and indenting every one would only make
        // the file larger. A field a newer version added is skipped, and one an older
        // version did not write takes its default.
        val SESSION_JSON = Json { ignoreUnknownKeys = true }
    }
}
