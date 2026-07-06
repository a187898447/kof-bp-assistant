package com.kof.bpassistant.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kof.bpassistant.BuildConfig

/**
 * AppController: 启动时调用，协调版本检测→更新→进入主界面的流程。
 * 网络不可用时走离线分支，不阻断启动。
 *
 * 数据来源由 BuildConfig.SYNC_ENABLED 控制：
 *  - false（无服务器阶段）：启动不联网，直接使用打包进 APK 的本地数据。
 *    数据写死在 assets/kof_data，通过"改 JSON → 升 versionCode → 重新打包"生效。
 *  - true（有服务器后）：启动检测版本并按需下载数据包热更新。
 */
class AppController(private val context: Context) {

    private val tag = "AppController"
    private val syncManager: DataSyncManager
    private val repository: DataRepository
    private val prefs = context.getSharedPreferences("kof_prefs", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_ASSET_VERSION = "asset_bundled_version"
        val BUNDLED_FILES = listOf(
            "heroes.json", "combos.json", "season_meta.json",
            "counter_strategies.json", "layout_config.json", "hero_hashes.json"
        )
    }

    init {
        repository = DataRepository(context)
        syncManager = DataSyncManager(repository)
        syncBundledAssets()
    }

    /**
     * 把打包进 APK 的 assets 数据同步到内部存储。
     *  - 首次安装：文件不存在，复制。
     *  - 升级安装：versionCode 变化时，强制用新 assets 覆盖内部存储（force=true），
     *    确保"改了 assets/kof_data 里的 JSON 再重新打包"能真正生效。
     *
     * 热更新开启（SYNC_ENABLED=true）后，内部存储可能已被下载的数据包覆盖成更新版本，
     * 此时不应再用旧 assets 回退，因此仅在 versionCode 变化时覆盖一次。
     */
    private fun syncBundledAssets() {
        val lastAssetVersion = prefs.getInt(KEY_ASSET_VERSION, -1)
        val currentVersion = BuildConfig.VERSION_CODE
        val force = lastAssetVersion != currentVersion
        JsonUtils.copyAssetsToDataDir(context, BUNDLED_FILES, repository.dataDir, force)
        if (force) {
            prefs.edit().putInt(KEY_ASSET_VERSION, currentVersion).apply()
            Log.i(tag, "已用打包数据刷新本地存储（versionCode $lastAssetVersion → $currentVersion）")
        }
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
        // 无服务器阶段：不联网，直接用打包进 APK 的本地数据启动
        if (!BuildConfig.SYNC_ENABLED) {
            Log.i(tag, "数据同步未启用，使用本地打包数据启动")
            return StartupResult.LocalData
        }
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
    object LocalData : StartupResult()   // 无服务器阶段：使用打包进 APK 的本地数据，不联网
    data class ForceUpdate(val info: VersionCheckResponse) : StartupResult()
    data class SoftUpdate(val info: VersionCheckResponse) : StartupResult()
}

/** 更新操作结果 */
sealed class UpdateResult {
    object Success : UpdateResult()
    data class Failure(val message: String) : UpdateResult()
}
