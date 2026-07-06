package com.kof.bpassistant.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.kof.bpassistant.capture.SlotCrop
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * HeroRecognizer 实现基于 pHash 的英雄头像识别。
 *
 * 算法流程：
 *   Bitmap → 32×32 灰度 → DCT → 64-bit hash
 * 匹配：遍历 hero_hashes，取汉明距离最小的英雄 ID。
 * 空槽位：所有汉明距离均 > 20 时返回 EMPTY_SLOT。
 */
class HeroRecognizer {

    private val tag = "HeroRecognizer"

    companion object {
        const val EMPTY_SLOT = "__empty__"
        const val PHASH_SIZE = 32       // resize 目标边长
        const val DCT_SIZE = 8          // 取左上 8×8 均值区块
        const val THRESHOLD_MATCH = 10  // 汉明距离 ≤ 10 视为匹配
        const val THRESHOLD_EMPTY = 20  // 汉明距离全部 > 20 视为空槽位
    }

    // 当前哈希库：heroId → pHash(Long)
    private var hashLibrary: Map<String, Long> = emptyMap()

    /** 更新哈希库（数据包更新后调用） */
    fun updateHashLibrary(library: Map<String, Long>) {
        hashLibrary = library
        Log.i(tag, "哈希库已更新，共 ${library.size} 个英雄")
    }

    // ---- pHash 核心实现 ----

    /**
     * 计算 Bitmap 的 pHash（64-bit Long）。
     * 步骤：缩放到 32×32 → 灰度化 → 二维 DCT → 取左上 8×8 均值 → 每格与均值比较生成 64-bit
     */
    fun pHash(bitmap: Bitmap): Long {
        // 1. 缩放到 32×32
        val resized = Bitmap.createScaledBitmap(bitmap, PHASH_SIZE, PHASH_SIZE, true)

        // 2. 转为灰度浮点矩阵
        val gray = Array(PHASH_SIZE) { y ->
            DoubleArray(PHASH_SIZE) { x ->
                val pixel = resized.getPixel(x, y)
                // 标准亮度公式 Y = 0.299R + 0.587G + 0.114B
                0.299 * Color.red(pixel) +
                0.587 * Color.green(pixel) +
                0.114 * Color.blue(pixel)
            }
        }
        if (resized != bitmap) resized.recycle()

        // 3. 二维 DCT（只需计算左上 8×8）
        val dct = Array(DCT_SIZE) { DoubleArray(DCT_SIZE) }
        val n = PHASH_SIZE.toDouble()
        for (u in 0 until DCT_SIZE) {
            for (v in 0 until DCT_SIZE) {
                var sum = 0.0
                for (y in 0 until PHASH_SIZE) {
                    for (x in 0 until PHASH_SIZE) {
                        sum += gray[y][x] *
                            cos((2 * x + 1) * u * Math.PI / (2 * n)) *
                            cos((2 * y + 1) * v * Math.PI / (2 * n))
                    }
                }
                val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                dct[u][v] = (2.0 / n) * cu * cv * sum
            }
        }

        // 4. 计算左上 8×8 的均值（排除 DC 分量 dct[0][0]）
        var total = 0.0
        var count = 0
        for (u in 0 until DCT_SIZE) {
            for (v in 0 until DCT_SIZE) {
                if (u == 0 && v == 0) continue
                total += dct[u][v]
                count++
            }
        }
        val avg = if (count > 0) total / count else 0.0

        // 5. 每格与均值比较，大于均值则对应 bit 置 1
        var hash = 0L
        var bit = 0
        for (u in 0 until DCT_SIZE) {
            for (v in 0 until DCT_SIZE) {
                if (dct[u][v] > avg) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** 汉明距离：两个 Long 异或后统计置位数（popcount） */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * 识别单个槽位图像，返回英雄 ID（或 EMPTY_SLOT）。
     */
    fun recognize(slotBitmap: Bitmap): String {
        if (hashLibrary.isEmpty()) {
            Log.w(tag, "哈希库为空，无法识别")
            return EMPTY_SLOT
        }
        val hash = pHash(slotBitmap)
        var minDist = Int.MAX_VALUE
        var bestId = EMPTY_SLOT

        for ((heroId, heroHash) in hashLibrary) {
            val dist = hammingDistance(hash, heroHash)
            if (dist < minDist) {
                minDist = dist
                bestId = heroId
            }
        }

        // 三段式置信度（对齐 spec：<=10 确认命中，11..20 边界命中，>20 空槽）
        return when {
            minDist <= THRESHOLD_MATCH -> {
                Log.d(tag, "确认命中: $bestId（dist=$minDist ≤ $THRESHOLD_MATCH）")
                bestId
            }
            minDist <= THRESHOLD_EMPTY -> {
                // 边界区域：取最近邻作为结果，但记录日志便于排查误判（spec: MVP 静默处理）
                Log.w(tag, "边界命中: $bestId（dist=$minDist，介于 $THRESHOLD_MATCH~$THRESHOLD_EMPTY，置信度偏低）")
                bestId
            }
            else -> {
                Log.d(tag, "空槽位（minDist=$minDist > $THRESHOLD_EMPTY）")
                EMPTY_SLOT
            }
        }
    }

    /**
     * 批量识别槽位列表，返回 RecognitionResult（ban + pick 分开）。
     */
    fun recognizeAll(slots: List<SlotCrop>): RecognitionResult {
        val bans = mutableListOf<String>()
        val picks = mutableListOf<String>()
        for (slot in slots) {
            val heroId = recognize(slot.bitmap)
            if (slot.type == "ban") bans.add(heroId)
            else picks.add(heroId)
        }
        return RecognitionResult(
            bannedHeroIds = bans.filter { it != EMPTY_SLOT },
            pickedHeroIds = picks.filter { it != EMPTY_SLOT }
        )
    }
}

data class RecognitionResult(
    val bannedHeroIds: List<String>,
    val pickedHeroIds: List<String>
)
