package com.kof.bpassistant.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * AppController: 启动时调用，协调版本检测→更新→进入主界面的流程。
 * 网络不可用时走离线分支，不阻断启动。
 */
class AppController(private val context: Context) {

    private val tag = "AppController"
    private val syncManager: DataSyncManager
    private val repository: DataRepository
    private val prefs = context.getSharedPreferences("kof_prefs", Context.MODE_PRIVATE)

    init {
        repository = DataRepository(context)
        syncManager = DataSyncManager(repository)
        // 首次安装时，把 assets 中的初始数据复制到内部存储
        JsonUtils.copyAssetsToDataDir(
            context,
            listOf(
                "heroes.json", "combos.json", "season_meta.json",
                "counter_strategies.json", "layout_config.json",
                "hero_hashes.json"
            ),
            repository.dataDir
        )
    }

    /**
     * 检查是否有网络连接（不区分 WiFi / Mobile）。
     */
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 执行启动检测：
     *  - 网络可用 → checkVersion() → 如有强制更新返回 StartupResult.ForceUpdate
     *  - 本地版本不同 → 返回 StartupResult.SoftUpdate（可选更新）
     *  - 无需更新 → 返回 StartupResult.Ready
     *  - 网络不可用 → 返回 StartupResult.Offline（使用本地数据继续）
     *
     * **必须在后台线程调用**（网络 IO）。
     */
    fun checkStartup(): StartupResult {
        if (!isNetworkAvailable()) {
            Log.i(tag, "网络不可用，进入离线模式")
            return StartupResult.Offline
        }
        val remote = syncManager.checkVersion() ?: return StartupResult.Offline
        val localVersion = syncManager.getLocalVersion(prefs)
        return when {
            remote.forceUpdate -> {
                Log.i(tag, "强制更新: remote=${remote.dataVersion}")
                StartupResult.ForceUpdate(remote)
            }
            localVersion != remote.dataVersion -> {
                Log.i(tag, "可选更新: local=$localVersion remote=${remote.dataVersion}")
                StartupResult.SoftUpdate(remote)
            }
            else -> {
                Log.i(tag, "数据已是最新: version=$localVersion")
                StartupResult.Ready
            }
        }
    }

    /**
     * 下载并安装更新包，成功后保存新版本号。
     * 需要在后台线程调用；通过 onProgress 回调更新 UI。
     */
    fun performUpdate(
        versionInfo: VersionCheckResponse,
        onProgress: (Float) -> Unit
    ): UpdateResult {
        val tmpFile = java.io.File(context.cacheDir, "data_update.zip")
        val downloaded = syncManager.download(versionInfo.downloadUrl, tmpFile, onProgress)
        if (!downloaded) return UpdateResult.Failure("下载失败，请检查网络后重试")
        val extracted = syncManager.verifyAndExtract(tmpFile, versionInfo.md5)
        if (!extracted) return UpdateResult.Failure("更新失败，文件校验未通过，请重试")
        syncManager.saveLocalVersion(prefs, versionInfo.dataVersion)
        Log.i(tag, "数据包更新完成: ${versionInfo.dataVersion}")
        return UpdateResult.Success
    }

    fun getRepository(): DataRepository = repository
}

/** 启动检测结果 */
sealed class StartupResult {
    object Ready : StartupResult()
    object Offline : StartupResult()
    data class ForceUpdate(val info: VersionCheckResponse) : StartupResult()
    data class SoftUpdate(val info: VersionCheckResponse) : StartupResult()
}

/** 更新操作结果 */
sealed class UpdateResult {
    object Success : UpdateResult()
    data class Failure(val message: String) : UpdateResult()
}
