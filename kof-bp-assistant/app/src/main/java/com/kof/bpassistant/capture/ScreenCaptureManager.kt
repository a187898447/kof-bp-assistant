package com.kof.bpassistant.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.kof.bpassistant.data.LayoutConfig
import com.kof.bpassistant.data.SlotRect
import kotlin.math.abs

/**
 * ScreenCaptureManager 封装 MediaProjection 截图能力。
 *
 * Android 14+ 修复（正确版本）：
 * 问题：Android 14 限制"同一授权 Intent 多次 getMediaProjection"（不只是限制同一实例多次 createVirtualDisplay）
 * 方案：一次授权后创建一个 MediaProjection + VirtualDisplay + ImageReader session，
 *       BP 阶段保持激活，点击扫描时只取当前最新帧，进入游戏时统一 stop()。
 *
 * 优点：
 *   - 符合 Android 14 语义（一次授权 → 一个 projection 实例 → 一个 VirtualDisplay）
 *   - 无需重复弹授权（体验好）
 *   - 手动触发扫描时只读取最新帧，不启动新的录屏 session
 */
class ScreenCaptureManager(private val context: Context) {

    private val tag = "ScreenCaptureManager"
    private val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    // 持久化的 projection session（BP 期间保持激活）
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0

    /**
     * 初始化：创建一个 MediaProjection session 并立即 createVirtualDisplay + ImageReader。
     * 此 session 在 BP 阶段保持激活，每次扫描只需取最新帧，无需重建。
     *
     * @param resultCode MediaProjection 授权结果码
     * @param data MediaProjection 授权 Intent
     * @return true 表示 session 成功创建，可以扫描；false 表示失败，调用方应引导用户重新授权
     */
    fun init(resultCode: Int, data: Intent): Boolean {
        if (mediaProjection != null) {
            Log.w(tag, "已有激活的 projection session，先停止旧的")
            stop()
        }

        initScreenSize()
        if (screenWidth == 0 || screenHeight == 0) {
            Log.e(tag, "屏幕尺寸获取失败，无法创建截图 session")
            return false
        }

        // 创建 MediaProjection（Android 14：同一授权只能调用一次 getMediaProjection）
        mediaProjection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(tag, "创建 MediaProjection 失败: ${e.message}")
            return false
        }

        val projection = mediaProjection ?: run {
            Log.e(tag, "getMediaProjection 返回 null")
            return false
        }

        // 注册 Callback（Android 14 要求）
        // onStop() 由系统/projection.stop() 触发，此路径不能再调 mediaProjection?.stop()，
        // 否则会重入。传 stopProjection=false，只释放 VirtualDisplay 和 ImageReader。
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(tag, "MediaProjection 被系统停止")
                releaseSession(stopProjection = false)
            }
        }, Handler(Looper.getMainLooper()))

        // 创建 ImageReader（BP 期间持久保留）
        // 低内存或异常分辨率下 newInstance 可能抛异常，包 try/catch 走统一失败路径
        imageReader = try {
            ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        } catch (e: Exception) {
            Log.e(tag, "创建 ImageReader 失败: ${e.message}")
            releaseSession(stopProjection = true)   // projection 已创建，需要主动 stop
            return false
        }

        // 创建 VirtualDisplay（Android 14：同一 projection 只能调用一次 createVirtualDisplay）
        virtualDisplay = try {
            projection.createVirtualDisplay(
                "KofBpCapture",
                screenWidth, screenHeight,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(tag, "创建 VirtualDisplay 失败: ${e.message}")
            releaseSession(stopProjection = true)   // projection 已创建，需要主动 stop
            return false
        }

        Log.i(tag, "截图 session 已创建: ${screenWidth}x${screenHeight}，可开始扫描")
        return true
    }

    private fun initScreenSize() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    /**
     * 截取当前屏幕最新帧。
     * session 已存在，直接从 imageReader 取最新帧，无需重建 projection。
     * 失败时返回 null，记录日志，不抛异常。
     */
    fun captureScreen(): Bitmap? {
        val reader = imageReader
        if (reader == null || virtualDisplay == null || mediaProjection == null) {
            Log.e(tag, "截图 session 未激活")
            return null
        }
        if (screenWidth == 0 || screenHeight == 0) {
            Log.e(tag, "屏幕尺寸未初始化")
            return null
        }

        return try {
            // 等待最新帧就绪（最多 600ms）
            var bitmap: Bitmap? = null
            val deadline = System.currentTimeMillis() + 600L
            while (System.currentTimeMillis() < deadline) {
                // 丢弃旧帧，只取最新帧
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    // 先写入含 padding 的临时 bitmap，再裁剪
                    val bmpWithPadding = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bmpWithPadding.copyPixelsFromBuffer(buffer)
                    bitmap = Bitmap.createBitmap(bmpWithPadding, 0, 0, screenWidth, screenHeight)
                    bmpWithPadding.recycle()
                    image.close()
                    break
                }
                Thread.sleep(16)
            }
            bitmap
        } catch (e: Exception) {
            Log.e(tag, "截图失败: ${e.message}")
            null
        }
    }

    /** 根据 layout_config 裁剪对方 Ban/Pick 槽位 ROI */
    fun cropSlots(screenshot: Bitmap, config: LayoutConfig): List<SlotCrop> {
        val result = mutableListOf<SlotCrop>()
        config.enemyBanSlots.forEachIndexed { i, rect ->
            cropRect(screenshot, rect)?.let { result.add(SlotCrop(it, i, "ban")) }
        }
        config.enemyPickSlots.forEachIndexed { i, rect ->
            cropRect(screenshot, rect)?.let { result.add(SlotCrop(it, i, "pick")) }
        }
        return result
    }

    private fun cropRect(screenshot: Bitmap, rect: SlotRect): Bitmap? {
        return try {
            val x = (rect.x * screenshot.width).toInt().coerceIn(0, screenshot.width - 1)
            val y = (rect.y * screenshot.height).toInt().coerceIn(0, screenshot.height - 1)
            val w = (rect.w * screenshot.width).toInt().coerceAtLeast(1).coerceAtMost(screenshot.width - x)
            val h = (rect.h * screenshot.height).toInt().coerceAtLeast(1).coerceAtMost(screenshot.height - y)
            Bitmap.createBitmap(screenshot, x, y, w, h)
        } catch (e: Exception) {
            Log.w(tag, "ROI 裁剪失败: ${e.message}")
            null
        }
    }

    /** 从 LayoutConfig 列表中找纵横比最接近的配置 */
    fun findBestLayoutConfig(configs: List<LayoutConfig>): LayoutConfig? {
        if (configs.isEmpty()) return null
        if (screenWidth == 0 || screenHeight == 0) return configs.first()
        val currentRatio = screenWidth.toDouble() / screenHeight
        return configs.minByOrNull { cfg ->
            val parts = cfg.screenRatio.split(":")
            if (parts.size == 2) {
                val r = parts[0].toDoubleOrNull()?.let { w -> parts[1].toDoubleOrNull()?.let { h -> w / h } }
                abs((r ?: Double.MAX_VALUE) - currentRatio)
            } else Double.MAX_VALUE
        }
    }

    /**
     * 进入游戏：用户主动停止，释放全部资源包括 projection。
     */
    fun stop() {
        Log.i(tag, "停止截图 session")
        releaseSession(stopProjection = true)
    }

    /**
     * @param stopProjection true = 主动调用路径（需要 stop projection）；
     *                       false = callback 路径（projection 已在停止中，不再 stop 防重入）
     */
    private fun releaseSession(stopProjection: Boolean) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        if (stopProjection) {
            // 先置 null，防止 stop() 触发的 onStop() callback 再次进入此分支
            val proj = mediaProjection
            mediaProjection = null
            try { proj?.stop() } catch (_: Exception) {}
        } else {
            mediaProjection = null
        }
    }

    val isActive: Boolean get() = mediaProjection != null && virtualDisplay != null && imageReader != null
}

data class SlotCrop(
    val bitmap: Bitmap,
    val slotIndex: Int,
    val type: String   // "ban" or "pick"
)
