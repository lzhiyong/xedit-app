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
 
package io.github.code.app.common

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

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

object DownloadManager {
    
    private val TAG = DownloadManager::class.simpleName
    
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().apply {
            connectTimeout(10, TimeUnit.SECONDS)  
            readTimeout(10, TimeUnit.SECONDS)
            writeTimeout(10, TimeUnit.SECONDS)
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
    
    fun isNetworkConnected(context: Context) : Boolean{
        val connectManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = connectManager.activeNetworkInfo
        if(info != null){
            return info.isAvailable
        }
        return false
    }
    
    fun isWifiConnected(context: Context) : Boolean{
        val connectManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = connectManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
        if(info != null){
            return info.isAvailable
        }
        return false
    }
    
    fun isConnected(context: Context) : Boolean {
        if(isNetworkConnected(context) || isWifiConnected(context)) {
            return true
        }
        return false
    }
    
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
    
    suspend fun validate(url: String, etag: String?): Boolean {
        val response = try {
            service.validate(url)
        } catch(e: Exception) {
            Log.e(this::class.simpleName, "${e.message}")
            null
        }
        
        if (response != null && response.isSuccessful) {
            if(etag != response.headers().get("etag")!!) {
                return true
            }
        }
        return false
    }

    suspend fun download(
        url: String, outputFile: File, startByte: Long
    ) = flow {
        val response = service.download(url, "bytes=$startByte-")
        
        if (response.isSuccessful) {
            val headers = response.headers()
            // save byte stream to file
            writeFile(response.body()!!, outputFile, startByte) { progress ->
                emit(DownloadState.InProgress(progress))
            }
            // download finish
            emit(DownloadState.Success(outputFile, headers.get("etag")!!))
        } else {
            // start bytes == contentLength, the file download success
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
    
    private inline fun writeFile(
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
    
    private inline fun saveFile(
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

