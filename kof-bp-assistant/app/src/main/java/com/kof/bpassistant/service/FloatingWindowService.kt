package com.kof.bpassistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.kof.bpassistant.R
import com.kof.bpassistant.analysis.AnalysisResult
import com.kof.bpassistant.analysis.CounterAnalysisEngine
import com.kof.bpassistant.capture.ScreenCaptureManager
import com.kof.bpassistant.data.AppController
import com.kof.bpassistant.recognition.HeroRecognizer
import kotlinx.coroutines.*

/**
 * FloatingWindowService
 * - Foreground Service，持有通知栏图标
 * - 初始化时从 Intent 拿到 MediaProjection 授权凭据（resultCode + data）
 * - 展开/折叠悬浮窗，可拖动，位置记忆
 * - 扫描按钮防重复点击；扫描完成后覆盖展示结果
 * - 进入游戏按钮：折叠 + 清除截图凭据
 *
 * Fix P0-3: captureManager 改为 lateinit var，在 onCreate() 里初始化（不再提前于 Android 组件生命周期）
 * Fix P1-3: 返回 START_NOT_STICKY；Intent 为 null（系统重启 Service）时不显示悬浮窗，而是发通知引导用户重新授权
 */
class FloatingWindowService : Service() {

    private val tag = "FloatingWindowService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var windowManager: WindowManager

    // P0-3: 改为 lateinit var，在 onCreate() 初始化，不在字段声明处使用 Context
    private lateinit var captureManager: ScreenCaptureManager
    private lateinit var recognizer: HeroRecognizer
    private lateinit var analysisEngine: CounterAnalysisEngine
    private lateinit var appController: AppController

    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isExpanded = false

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "kof_bp_assistant_channel"
        const val EXTRA_PROJECTION_RESULT_CODE = "proj_result_code"
        const val EXTRA_PROJECTION_DATA = "proj_data"
    }

    // ---- 生命周期 ----

    override fun onCreate() {
        super.onCreate()
        // P0-3: 在 onCreate() 中初始化所有依赖 Context 的对象
        appController = AppController(this)
        captureManager = ScreenCaptureManager(this)
        recognizer = HeroRecognizer()
        analysisEngine = CounterAnalysisEngine()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(tag, "FloatingWindowService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // P1-3: intent 为 null 说明是系统重启 Service（无投影凭据），不继续初始化
        if (intent == null) {
            Log.w(tag, "系统重启 Service，无 MediaProjection 凭据，停止服务")
            sendReauthNotification()
            stopSelf()
            // P1-3: 返回 START_NOT_STICKY，不让系统自动重启此 Service
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, -1)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION_DATA)

        // P1-2 fix: Intent 非空但缺少有效凭据时，同样通知并停止服务
        if (resultCode == -1 || data == null) {
            Log.w(tag, "Intent 中无有效 MediaProjection 凭据（resultCode=$resultCode），停止服务")
            sendReauthNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        // P1-2 fix: init() 失败（getMediaProjection/createVirtualDisplay 抛异常）时，
        // 不显示悬浮窗，通知用户重新授权并停止服务，避免出现"悬浮窗可见但扫描必失败"
        if (!captureManager.init(resultCode, data)) {
            Log.e(tag, "截图 session 初始化失败，停止服务并引导重新授权")
            sendReauthNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(tag, "截图 session 已初始化")

        loadAnalysisData()
        showFloatingWindow()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 数据加载 ----

    private fun loadAnalysisData() {
        val repo = appController.getRepository()
        recognizer.updateHashLibrary(repo.loadHeroHashes())
        analysisEngine.loadData(
            repo.loadHeroes(),
            repo.loadCombos(),
            repo.loadSeasonMeta(),
            repo.loadCounterStrategies()
        )
        Log.i(tag, "分析数据加载完成")
    }

    // ---- 悬浮窗生命周期 ----

    private fun showFloatingWindow() {
        if (floatingView != null) return

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window_collapsed, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val prefs = getSharedPreferences("kof_prefs", Context.MODE_PRIVATE)
        params.x = prefs.getInt("float_x", 100)
        params.y = prefs.getInt("float_y", 200)

        layoutParams = params
        windowManager.addView(floatingView, params)
        isExpanded = false
        setupTouchAndClick()
        Log.i(tag, "悬浮窗已显示（折叠态）")
    }

    private fun expand() {
        val params = layoutParams ?: return
        floatingView?.let { windowManager.removeView(it) }
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window_expanded, null)
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.addView(floatingView, params)
        isExpanded = true
        setupTouchAndClick()
    }

    private fun collapse() {
        val params = layoutParams ?: return
        floatingView?.let { windowManager.removeView(it) }
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window_collapsed, null)
        windowManager.addView(floatingView, params)
        isExpanded = false
        setupTouchAndClick()
    }

    // ---- 触摸/拖动/点击 ----

    private fun setupTouchAndClick() {
        val view = floatingView ?: return
        val params = layoutParams ?: return
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragging = true
                        params.x = initX + dx.toInt()
                        params.y = initY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        if (isExpanded) collapse() else expand()
                    } else {
                        getSharedPreferences("kof_prefs", Context.MODE_PRIVATE).edit()
                            .putInt("float_x", params.x)
                            .putInt("float_y", params.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }

        if (isExpanded) {
            view.findViewById<Button>(R.id.btnMinimize)?.setOnClickListener { collapse() }
            view.findViewById<Button>(R.id.btnScan)?.setOnClickListener { triggerScan() }
            view.findViewById<Button>(R.id.btnEnterGame)?.setOnClickListener {
                collapse()
                captureManager.stop()
                Log.i(tag, "用户点击进入游戏，截图凭据已清除")
            }
        }
    }

    // ---- 扫描流程 ----

    private fun triggerScan() {
        val btnScan = floatingView?.findViewById<Button>(R.id.btnScan) ?: return
        btnScan.isEnabled = false
        btnScan.text = "扫描中…"

        serviceScope.launch {
            val result = withContext(Dispatchers.Default) { runScan() }
            displayResult(result)
            btnScan.isEnabled = true
            btnScan.text = getString(R.string.btn_scan)
        }
    }

    private fun runScan(): AnalysisResult? {
        if (!captureManager.isActive) {
            Log.w(tag, "截图凭据无效，无法扫描")
            return null
        }
        val screenshot = captureManager.captureScreen() ?: return null
        val configs = appController.getRepository().loadLayoutConfigs()
        val config = captureManager.findBestLayoutConfig(configs) ?: run {
            screenshot.recycle(); return null
        }
        val slots = captureManager.cropSlots(screenshot, config)
        screenshot.recycle()
        val recognitionResult = recognizer.recognizeAll(slots)
        slots.forEach { it.bitmap.recycle() }
        return analysisEngine.analyze(recognitionResult)
    }

    private fun displayResult(result: AnalysisResult?) {
        if (!isExpanded) expand()
        val tvResult = floatingView?.findViewById<TextView>(R.id.tvResult) ?: return
        if (result == null) {
            tvResult.text = "识别失败，请确认已进入 BP 界面后重试"
            return
        }
        val sb = StringBuilder()
        sb.appendLine("【Ban 意图】\n${result.banIntentText}\n")
        result.comboMatch?.let { sb.appendLine("【阵容识别】\n${it.comboName}（置信度：${it.confidence}）\n") }
        result.counterStrategy?.let {
            sb.appendLine("【阵容级反制】\n${it.threatPoints.joinToString("；")}\n${it.counterThought}\n")
        }
        result.seasonNote?.let { sb.appendLine("【赛季强势】\n$it\n") }
        sb.appendLine("【对位克制 TOP3】")
        result.heroCounters.forEach { c ->
            sb.appendLine("▶ ${c.heroName}")
            if (c.top3Counters.isEmpty()) sb.appendLine("  ${c.note}")
            else c.top3Counters.forEach { h -> sb.appendLine("  • ${h.heroName}（${h.counterType}）") }
        }
        tvResult.text = sb.toString()
        Log.i(tag, "结果展示完毕，耗时 ${result.analysisMs}ms")
    }

    // ---- 生命周期 ----

    override fun onDestroy() {
        serviceScope.cancel()
        captureManager.stop()
        floatingView?.let { windowManager.removeView(it) }
        floatingView = null
        super.onDestroy()
    }

    // ---- 通知 ----

    /** 系统重启 Service 且无投影凭据时，通知用户重新授权 */
    private fun sendReauthNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // P2-2 fix: 补 PendingIntent，点击通知回到 MainActivity 重新授权
        val activityIntent = Intent(this, com.kof.bpassistant.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BP 助手需要重新授权")
            .setContentText("请点击重新启动并授权截图权限")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "BP 助手悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KOF BP 助手")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
