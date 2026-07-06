package com.kof.bpassistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * PermissionHelper 集中处理应用所需的三类权限：
 *  1. 悬浮窗权限（SYSTEM_ALERT_WINDOW）
 *  2. 前台服务 / 通知权限（Android 13+）
 *  3. MIUI 电池白名单引导（省电策略 → 无限制）
 */
object PermissionHelper {

    private const val TAG = "PermissionHelper"

    // ---- 1. 悬浮窗权限（task 8.1）----

    /** 检查是否已有悬浮窗权限 */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * 跳转到系统设置页申请悬浮窗权限。
     * 在 Activity 中调用，从 onActivityResult 处理回调。
     */
    fun requestOverlayPermission(activity: Activity, requestCode: Int) {
        if (canDrawOverlays(activity)) return
        Log.i(TAG, "跳转悬浮窗权限设置页")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivityForResult(intent, requestCode)
    }

    // ---- 2. MIUI 电池白名单引导（task 8.2）----

    /** 检测是否为 MIUI 设备（包含 HyperOS） */
    fun isMiui(): Boolean {
        val miuiVersion = getSystemProperty("ro.miui.ui.version.code")
        val hyperOs = getSystemProperty("ro.mi.os.version.code")
        return miuiVersion.isNotEmpty() || hyperOs.isNotEmpty()
    }

    /**
     * 跳转到 MIUI / HyperOS 电池省电策略设置页。
     * 若设备不支持直接跳转，则回退到通用应用详情页并给出操作提示。
     */
    fun guideMiuiBatteryWhitelist(context: Context) {
        if (!isMiui()) {
            Log.d(TAG, "非 MIUI 设备，跳过电池白名单引导")
            return
        }
        val launched = tryLaunchMiuiBatterySettings(context)
        if (!launched) {
            // 回退：打开应用详情页
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            try {
                context.startActivity(intent)
                Toast.makeText(
                    context,
                    "请进入「省电策略」→「无限制」",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.w(TAG, "无法打开应用详情页: ${e.message}")
            }
        }
    }

    private fun tryLaunchMiuiBatterySettings(context: Context): Boolean {
        // MIUI 14 / HyperOS 已知 Intent action
        val actions = listOf(
            "miui.intent.action.POWER_HIDE_MODE_APP_LIST",
            "com.miui.powercenter.action.APP_BATTERY_DETAIL"
        )
        for (action in actions) {
            try {
                val intent = Intent(action).apply {
                    putExtra("package_name", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "MIUI 电池设置页已打开 via $action")
                return true
            } catch (_: Exception) { /* 尝试下一个 */ }
        }
        return false
    }

    private fun getSystemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String ?: ""
        } catch (_: Exception) { "" }
    }

    // ---- 3. 通知权限（Android 13+）----

    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 需要通知权限时，调用方在 Activity 中调用 requestPermissions() */
    fun getNotificationPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.POST_NOTIFICATIONS
        else ""
}
