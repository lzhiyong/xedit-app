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
 
package x.code.app.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.Request

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.Streaming
import retrofit2.http.Url

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.lang.Exception

import java.nio.channels.FileChannel
import java.nio.MappedByteBuffer
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

public interface ApiService {
    @Streaming
    @GET
    suspend fun validate(
        @Url url: String
    ): Response<ResponseBody>
    
    @Streaming
    @GET
    suspend fun download(
        @Url url: String, 
        @Header("RANGE") range: String
    ): Response<ResponseBody>
}

sealed class DownloadState {
    data class InProgress(val progress: Int) : DownloadState()
    data class Success(val file: File, val etag: String) : DownloadState()
    data class Error(val throwable: Throwable) : DownloadState()
}

public object DownloadManager {
    
    private val LOG_TAG = DownloadManager::class.simpleName
    
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().apply {
            connectTimeout(60, TimeUnit.SECONDS)  
            readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(60, TimeUnit.SECONDS)
        }.build()
    }
    
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder().apply {
            baseUrl("https://github.com/")
            client(client)
        }.build()
    }
    
    private val service: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
    
    @WorkerThread
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            // For older APIs, you might still need to use the deprecated NetworkInfo approach
            // or consider other libraries that handle backward compatibility
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo != null && networkInfo.isConnected
        }
    }
    
    @AnyThread
    fun getBaseUrl(url: String): String {
        var scheme = url
        var head = "https://"
        var index = scheme.indexOf("://")
        if (index >= 0) {
            head = scheme.substring(0, index + 3)
            scheme = scheme.substring(index + 3)
        }
        index = scheme.indexOf("/")
        if (index >= 0) {
            scheme = scheme.substring(0, index + 1)
        }
        return head + scheme
    }
    
    @WorkerThread
    suspend fun validate(url: String, etag: String?): Boolean {
        val response = try {
            service.validate(url)
        } catch(e: Exception) {
            null
        }
        
        if (response != null && response.isSuccessful) {
            if(etag != response.headers().get("etag")!!) {
                return true
            }
        }
        return false
    }
    
    @WorkerThread
    suspend fun download(
        url: String, outputFile: File, startByte: Long
    ) = flow {
        val response = service.download(url, "bytes=$startByte-")
        
        if (response.isSuccessful) {
            val headers = response.headers()
            // save byte stream to file
            writeToFile(response.body()!!, outputFile, startByte) { progress ->
                emit(DownloadState.InProgress(progress))
            }
            // download finish
            emit(DownloadState.Success(outputFile, headers.get("etag")!!))
        } else {
            // start bytes == contentLength, the file has been downloaded success
            // http status code 416 (HTTP_REQUESTED_RANGE_NOT_SATISFIABLE)
            if(response.code() == 416) {
                emit(DownloadState.Success(outputFile, response.headers().get("etag")!!))
            } else {
                emit(DownloadState.Error(IOException(response.toString())))
            }
        }
    }.catch { e ->
        emit(DownloadState.Error(e))
    }.flowOn(Dispatchers.IO)
    
    // convenience method for download and callback
    @WorkerThread
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
                        onError?.invoke(state.throwable.message!!)
                    }
                }
            }
        }
    }
    
    @WorkerThread
    private inline fun writeToFile(
        responseBody: ResponseBody, 
        targetFile: File,
        startBytes: Long,
        block: (progress: Int) -> Unit
    ) {
        val total = responseBody.contentLength() + startBytes
        var bytesCopied = startBytes
        
        FileOutputStream(targetFile, true).use { output ->
            val input = responseBody.byteStream()
            val buffer = ByteArray(1024)
            var bytes = input.read(buffer)
            while (bytes >= 0) {
                output.write(buffer, 0, bytes)
                bytesCopied += bytes
                // progress callback
                block((bytesCopied * 100 / total).toInt())
                // continue to read
                bytes = input.read(buffer)
            }
        }
    }
    
    @WorkerThread
    private inline fun saveToFile(
        responseBody: ResponseBody, 
        targetFile: File,
        startBytes: Long,
        block: (progress: Int) -> Unit
    ) {
        val total = responseBody.contentLength() + startBytes
        var bytesCopied = startBytes
        
        RandomAccessFile(targetFile, "rws").use { randomFile ->
            val input = responseBody.byteStream()
            val fileChannel = randomFile.getChannel()
            val mapByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, startBytes, total)
            
            val buffer = ByteArray(1024)
            var bytes = input.read(buffer)
            while (bytes >= 0) {
                mapByteBuffer.put(buffer, 0, bytes)
                bytesCopied += bytes
                // progress callback
                block((bytesCopied * 100 / total).toInt())
                // continue to read
                bytes = input.read(buffer)
            }
        }
    }
}

