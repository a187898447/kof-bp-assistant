package com.kof.bpassistant.data

import android.util.Log
import com.google.gson.Gson
import com.kof.bpassistant.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 服务端版本检测接口的响应数据 */
data class VersionCheckResponse(
    val dataVersion: String = "",
    val forceUpdate: Boolean = false,
    val downloadUrl: String = "",
    val md5: String = ""
)

/**
 * DataSyncManager 负责：
 *  - 启动时版本检测（GET /api/version/check）
 *  - 数据包下载（带进度回调，完成后 MD5 校验）
 *  - zip 解压覆盖内部存储（原子性替换，见 JsonUtils.unzipToDir）
 *  - 网络不可用时降级使用本地数据
 *
 * P1-4 fix: URL 从 BuildConfig 读取，不再硬编码占位地址；
 * debug 构建指向 dev 服务器，release 构建指向 prod 服务器（见 app/build.gradle）。
 */
class DataSyncManager(
    private val repository: DataRepository,
    private val versionCheckUrl: String = BuildConfig.VERSION_CHECK_URL
) {
    private val tag = "DataSyncManager"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // 本地存储的版本号 key
    private val prefVersionKey = "local_data_version"

    /** 检测服务端版本，网络不可用时返回 null（调用方应降级） */
    fun checkVersion(): VersionCheckResponse? {
        return try {
            val request = Request.Builder().url(versionCheckUrl).get().build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(tag, "版本检测请求失败: ${resp.code}")
                    return null
                }
                resp.body?.string() ?: return null
            }
            gson.fromJson(body, VersionCheckResponse::class.java)
        } catch (e: Exception) {
            Log.w(tag, "版本检测网络异常（离线模式）: ${e.message}")
            null
        }
    }

    /**
     * 下载数据包，保存到 tmpFile，带进度回调。
     * @param url 下载地址
     * @param tmpFile 临时文件
     * @param onProgress 进度 0.0–1.0
     * @return 下载是否成功
     */
    fun download(url: String, tmpFile: File, onProgress: (Float) -> Unit): Boolean {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(tag, "下载失败: ${resp.code}")
                    return false
                }
                val body = resp.body ?: run {
                    Log.e(tag, "下载响应 body 为空")
                    return false
                }
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                tmpFile.parentFile?.mkdirs()
                tmpFile.outputStream().buffered().use { out ->
                    body.byteStream().buffered().use { input ->
                        val buf = ByteArray(8192)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                onProgress(downloadedBytes.toFloat() / totalBytes)
                            }
                        }
                    }
                }
                onProgress(1.0f)
                true
            }
        } catch (e: Exception) {
            Log.e(tag, "下载异常: ${e.message}")
            tmpFile.delete()
            false
        }
    }

    /**
     * MD5 校验 + 解压覆盖。
     * @param tmpFile 已下载的 zip 文件
     * @param expectedMd5 服务端给的 MD5
     * @return 成功返回 true，失败时删除 tmpFile 并返回 false
     */
    fun verifyAndExtract(tmpFile: File, expectedMd5: String): Boolean {
        val actualMd5 = JsonUtils.md5Hex(tmpFile)
        if (expectedMd5.isNotEmpty() && !actualMd5.equals(expectedMd5, ignoreCase = true)) {
            Log.e(tag, "MD5 校验失败: expected=$expectedMd5 actual=$actualMd5")
            tmpFile.delete()
            return false
        }
        val extracted = JsonUtils.unzipToDir(tmpFile, repository.dataDir)
        return if (extracted.isEmpty()) {
            Log.e(tag, "解压失败，无文件输出")
            tmpFile.delete()
            false
        } else {
            Log.i(tag, "数据包更新成功，解压 ${extracted.size} 个文件")
            tmpFile.delete()
            true
        }
    }

    /** 读取本地已保存的数据版本号 */
    fun getLocalVersion(prefs: android.content.SharedPreferences): String =
        prefs.getString(prefVersionKey, "") ?: ""

    /** 保存数据版本号 */
    fun saveLocalVersion(prefs: android.content.SharedPreferences, version: String) {
        prefs.edit().putString(prefVersionKey, version).apply()
    }
}
