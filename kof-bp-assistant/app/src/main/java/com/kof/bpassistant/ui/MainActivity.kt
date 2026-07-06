package com.kof.bpassistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.kof.bpassistant.R
import com.kof.bpassistant.data.AppController
import com.kof.bpassistant.data.StartupResult
import com.kof.bpassistant.data.UpdateResult
import com.kof.bpassistant.service.FloatingWindowService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY = 1001
        private const val REQ_PROJECTION = 1002
        private const val REQ_NOTIFICATION = 1003
    }

    private lateinit var appController: AppController
    private val mainScope = MainScope()

    private lateinit var tvStatus: TextView
    private lateinit var tvOfflineBanner: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnGrantOverlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvOfflineBanner = findViewById(R.id.tvOfflineBanner)
        btnStartService = findViewById(R.id.btnStartService)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)

        appController = AppController(this)

        btnGrantOverlay.setOnClickListener {
            PermissionHelper.requestOverlayPermission(this, REQ_OVERLAY)
        }
        btnStartService.setOnClickListener {
            launchFloatingWindow()
        }

        performStartupCheck()

        if (PermissionHelper.needsNotificationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(PermissionHelper.getNotificationPermission()),
                REQ_NOTIFICATION
            )
        }
    }

    // ---- 启动检测流程（tasks 3.4 + 3.5）----

    private fun performStartupCheck() {
        tvStatus.text = "正在检查数据版本…"
        mainScope.launch {
            val result = withContext(Dispatchers.IO) { appController.checkStartup() }
            handleStartupResult(result)
        }
    }

    private fun handleStartupResult(result: StartupResult) {
        when (result) {
            StartupResult.Ready -> {
                tvStatus.text = "数据已是最新，可以开始使用"
                showMainControls()
            }
            StartupResult.Offline -> {
                tvOfflineBanner.visibility = View.VISIBLE
                tvStatus.text = "数据加载完成（离线模式）"
                showMainControls()
            }
            is StartupResult.SoftUpdate -> {
                tvStatus.text = "发现新版数据包（可选更新）"
                showMainControls()
            }
            is StartupResult.ForceUpdate -> {
                showForceUpdateDialog(result.info)
            }
        }
    }

    private fun showForceUpdateDialog(info: com.kof.bpassistant.data.VersionCheckResponse) {
        val progress = ProgressDialog(this).apply {
            setTitle(getString(R.string.force_update_title))
            setMessage("正在下载数据包…")
            setCancelable(false)
            isIndeterminate = false
            max = 100
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.force_update_title)
            .setMessage(R.string.force_update_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_update_now) { _, _ ->
                progress.show()
                mainScope.launch {
                    val updateResult = withContext(Dispatchers.IO) {
                        appController.performUpdate(info) { p ->
                            runOnUiThread { progress.progress = (p * 100).toInt() }
                        }
                    }
                    progress.dismiss()
                    when (updateResult) {
                        UpdateResult.Success -> {
                            tvStatus.text = "数据包更新完成"
                            showMainControls()
                        }
                        is UpdateResult.Failure -> {
                            // P1-1 fix: 强制更新失败时不允许进入主界面
                            // 规格要求"阻止进入主界面，直到更新完成"
                            showForceUpdateRetryDialog(info, updateResult.message)
                        }
                    }
                }
            }
            .show()
    }

    /**
     * 强制更新失败时显示重试对话框。
     * 不调用 showMainControls()，确保用户无法绕过强制更新进入主界面。
     */
    private fun showForceUpdateRetryDialog(
        info: com.kof.bpassistant.data.VersionCheckResponse,
        errorMsg: String
    ) {
        tvStatus.text = "更新失败"
        AlertDialog.Builder(this)
            .setTitle("更新失败")
            .setMessage("$errorMsg\n\n必须更新后才能使用。")
            .setCancelable(false)
            .setPositiveButton("重试") { _, _ ->
                showForceUpdateDialog(info)
            }
            .setNegativeButton("退出") { _, _ ->
                finish()
            }
            .show()
    }

    private fun showMainControls() {
        if (!PermissionHelper.canDrawOverlays(this)) {
            btnGrantOverlay.visibility = View.VISIBLE
            btnStartService.isEnabled = false
        } else {
            btnGrantOverlay.visibility = View.GONE
            btnStartService.isEnabled = true
        }

        // MIUI 电池白名单引导（task 8.2），仅首次显示
        if (PermissionHelper.isMiui()) {
            val prefs = getSharedPreferences("kof_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("miui_battery_guide_shown", false)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.miui_battery_guide_title)
                    .setMessage(R.string.miui_battery_guide_msg)
                    .setPositiveButton(R.string.btn_go_to_setting) { _, _ ->
                        PermissionHelper.guideMiuiBatteryWhitelist(this)
                    }
                    .setNegativeButton(R.string.btn_skip, null)
                    .show()
                prefs.edit().putBoolean("miui_battery_guide_shown", true).apply()
            }
        }
    }

    // ---- 悬浮窗 + MediaProjection 启动（tasks 4.1 + 7.1）----

    private fun launchFloatingWindow() {
        if (!PermissionHelper.canDrawOverlays(this)) {
            PermissionHelper.requestOverlayPermission(this, REQ_OVERLAY)
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (PermissionHelper.canDrawOverlays(this)) {
                    btnGrantOverlay.visibility = View.GONE
                    btnStartService.isEnabled = true
                }
            }
            REQ_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val intent = Intent(this, FloatingWindowService::class.java).apply {
                        putExtra(FloatingWindowService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                        putExtra(FloatingWindowService.EXTRA_PROJECTION_DATA, data)
                    }
                    startForegroundService(intent)
                    Toast.makeText(this, "悬浮窗已启动，可切换到游戏", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "截图授权被拒绝，无法启动", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (PermissionHelper.canDrawOverlays(this)) {
            btnGrantOverlay.visibility = View.GONE
            btnStartService.isEnabled = true
        }
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }
}
