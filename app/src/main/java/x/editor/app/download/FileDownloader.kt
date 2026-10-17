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

package x.editor.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

import androidx.annotation.AnyThread

import okhttp3.OkHttpClient
import okhttp3.ResponseBody

import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

import x.editor.app.util.NetUtils

public interface DownloadApi {
    @Streaming
    @GET
    suspend fun fetch(
        @Url url: String
    ): Response<ResponseBody>
    
    @Streaming
    @GET
    suspend fun download(
        @Url url: String, 
        @Header("RANGE") range: String
    ): Response<ResponseBody>

    // the range only if the file is still [ifRange] (an ETag): else the whole file
    @Streaming
    @GET
    suspend fun downloadIfRange(
        @Url url: String,
        @Header("Range") range: String,
        @Header("If-Range") ifRange: String
    ): Response<ResponseBody>
}

/**
 * What the server says about a file, from the headers of its response.
 *
 * @property length in bytes; null if the server did not say
 */
data class RemoteFile(val etag: String?, val lastModified: String?, val length: Long?) {

    /**
     * What tells one version of the file from another: the ETag; without one, the
     * date and the size. Null if the server said neither.
     */
    val version: String?
        get() = etag ?: lastModified?.let { "$it/${length ?: -1}" }
}

sealed class DownloadState {
    /** [progress] percent (0..100) are on disk; reported only when the number changes. */
    data class InProgress(val progress: Int) : DownloadState()
    /** The whole file is on disk. [etag] is empty if the server sent none. */
    data class Success(val file: File, val etag: String) : DownloadState()
    data class Error(val throwable: Throwable) : DownloadState()
}

public object FileDownloader {

    private const val HEADER_ETAG = "etag"

    private const val COPY_BUFFER_SIZE = 8 * 1024

    // http status code 416: the range starts at or behind the end of the file
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().apply {
            connectTimeout(60, TimeUnit.SECONDS)  
            readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(60, TimeUnit.SECONDS)
        }.build()
    }
    
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder().apply {
            // only a placeholder: every request passes its full url (@Url)
            baseUrl("https://github.com/")
            client(client)
        }.build()
    }
    
    private val api: DownloadApi by lazy {
        retrofit.create(DownloadApi::class.java)
    }
    
    /** Whether there is an active network that claims to reach the internet. */
    @AnyThread
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo != null && networkInfo.isConnected
        }
    }
    
    @AnyThread
    fun getBaseUrl(url: String): String = NetUtils.getBaseUrl(url)
    
    /**
     * Whether the file at [url] is another one than the one that was downloaded
     * with [etag]: the server answers, sends an ETag, and it differs.
     *
     * false whenever that cannot be told: no connection, an error status, or a
     * server that sends no ETag. Never throws (except for cancellation).
     */
    suspend fun hasRemoteChanged(url: String, etag: String?): Boolean {
        val response = try {
            api.fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }

        try {
            if (!response.isSuccessful) return false
            val remoteEtag = response.headers().get(HEADER_ETAG) ?: return false
            return remoteEtag != etag
        } finally {
            // only the headers were wanted: give the connection back without
            // reading the body
            response.body()?.close()
        }
    }
    
    /**
     * What the server says about the file at [url] (redirects followed): its ETag,
     * date and size, from the headers; the body is not read. Null if that cannot be
     * told: no connection, an error status. Never throws (except for cancellation).
     */
    suspend fun remoteFile(url: String): RemoteFile? {
        val response = try {
            api.fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        try {
            if (!response.isSuccessful) return null
            val headers = response.headers()
            return RemoteFile(
                etag = headers.get(HEADER_ETAG),
                lastModified = headers.get("last-modified"),
                length = headers.get("content-length")?.toLongOrNull()
                    ?: response.body()?.contentLength()?.takeIf { it >= 0 }
            )
        } finally {
            // only the headers were wanted: give the connection back without
            // reading the body
            response.body()?.close()
        }
    }

    /**
     * Downloads [url] into [outputFile], continuing after the [startByte] bytes
     * that are already there (0 to start from the beginning).
     *
     * The flow never throws: a failure is its last value, [DownloadState.Error].
     * What was written until then stays in [outputFile], so that the download can
     * be continued. It runs on Dispatchers.IO whatever the collector runs on.
     */
    fun download(
        url: String, outputFile: File, startByte: Long, ifRange: String? = null
    ): Flow<DownloadState> = flow {
        // With [ifRange] (the ETag of what is there), a file that changed on the
        // server meanwhile is sent whole, and replaces the part of the old one.
        val response = if (startByte > 0L && ifRange != null) {
            api.downloadIfRange(url, "bytes=$startByte-", ifRange)
        } else {
            api.download(url, "bytes=$startByte-")
        }
        val etag = response.headers().get(HEADER_ETAG) ?: ""

        if (response.isSuccessful) {
            val body = response.body() ?: throw IOException("Empty response for $url")
            // A server that ignores the range sends the whole file (200, not 206):
            // it must then replace what is there, not be appended to it.
            val offset = if (response.code() == HttpURLConnection.HTTP_PARTIAL) startByte else 0L
            body.use {
                writeBodyToFile(it, outputFile, offset) { progress ->
                    emit(DownloadState.InProgress(progress))
                }
            }
            // download finish
            emit(DownloadState.Success(outputFile, etag))
        } else if (response.code() == HTTP_RANGE_NOT_SATISFIABLE && startByte > 0L) {
            // start bytes == contentLength, the file has been downloaded success
            emit(DownloadState.Success(outputFile, etag))
        } else {
            emit(DownloadState.Error(IOException(response.toString())))
        }
    }.catch { e ->
        emit(DownloadState.Error(e))
    }.flowOn(Dispatchers.IO)
    
    /**
     * Convenience method for download and callback. Continues a download whose
     * beginning is already in [outputFile].
     *
     * [onProgress] and [onError] are called on the main thread; [onComplete] is
     * called on the thread of the caller, so that it can go on working with the file.
     */
    suspend fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null,       
        onComplete: ((File, String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val startBytes = if (outputFile.exists()) outputFile.length() else 0L
        this.download(url, outputFile, startBytes).collect { state ->
            when (state) {
                is DownloadState.InProgress -> {
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(state.progress)
                    }
                }
                is DownloadState.Success -> {
                    if (state.file.exists()) {                        
                        onComplete?.invoke(state.file, state.etag)
                    }                    
                }
                is DownloadState.Error -> {
                    withContext(Dispatchers.Main) {
                        onError?.invoke(state.throwable.message ?: state.throwable.toString())
                    }
                }
            }
        }
    }

    // Writes the body behind the first [offset] bytes of the file (0: replaces it).
    // [block] is called with the percentage, each value once.
    private inline fun writeBodyToFile(
        responseBody: ResponseBody, 
        targetFile: File,
        offset: Long,
        block: (progress: Int) -> Unit
    ) {
        val contentLength = responseBody.contentLength()
        // -1: the server did not say how much it sends
        val total = if (contentLength >= 0L) contentLength + offset else -1L
        var bytesCopied = offset
        var lastProgress = -1
        
        FileOutputStream(targetFile, offset > 0L).use { output ->
            val input = responseBody.byteStream()
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var bytes = input.read(buffer)
            while (bytes >= 0) {
                output.write(buffer, 0, bytes)
                bytesCopied += bytes
                // progress callback
                val progress = NetUtils.progressPercent(bytesCopied, total)
                if (progress != lastProgress) {
                    lastProgress = progress
                    block(progress)
                }
                // continue to read
                bytes = input.read(buffer)
            }
        }
    }
}
