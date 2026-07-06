package com.kof.bpassistant.analysis

import android.os.SystemClock
import android.util.Log
import com.kof.bpassistant.data.Combo
import com.kof.bpassistant.data.CounterStrategy
import com.kof.bpassistant.data.Hero
import com.kof.bpassistant.data.SeasonMeta
import com.kof.bpassistant.recognition.RecognitionResult

private const val TAG = "CounterAnalysisEngine"
private const val MAX_RULES = 200   // MVP 上限：超过时截断并记录日志

/**
 * CounterAnalysisEngine
 * 根据识别结果输出：
 *  - Ban 阶段意图分析
 *  - 阵容/套路命中结果（轻量规则引擎）
 *  - 阵容级反制建议
 *  - 单英雄对位克制 TOP3
 * 所有分析在本地完成，不依赖网络。
 */
class CounterAnalysisEngine {

    // ---- 数据注入 ----

    private var heroes: Map<String, Hero> = emptyMap()       // heroId → Hero
    private var combos: List<Combo> = emptyList()
    private var seasonMeta: SeasonMeta? = null
    private var counterStrategies: Map<String, CounterStrategy> = emptyMap()  // tag → strategy

    fun loadData(
        heroList: List<Hero>,
        comboList: List<Combo>,
        season: SeasonMeta?,
        strategies: List<CounterStrategy>
    ) {
        heroes = heroList.associateBy { it.id }
        // 规则截断（规范：> 200 条时只用前 200 条）
        combos = if (comboList.size > MAX_RULES) {
            Log.w(TAG, "规则数量 ${comboList.size} 超过上限 $MAX_RULES，已截断")
            comboList.take(MAX_RULES)
        } else comboList
        seasonMeta = season
        counterStrategies = strategies.associateBy { it.comboTag }
        Log.i(TAG, "数据加载完成：${heroes.size} 英雄, ${combos.size} 规则")
    }

    // ---- 主分析入口 ----

    /**
     * 分析入口，返回 AnalysisResult（Parcelable）。
     */
    fun analyze(result: RecognitionResult): AnalysisResult {
        val t0 = SystemClock.elapsedRealtime()

        val banIntent = analyzeBanIntent(result.bannedHeroIds)
        val comboMatch = analyzeCombo(result.bannedHeroIds, result.pickedHeroIds)
        val counterStrategy = comboMatch?.let { counterStrategies[it.comboTag] }
        val heroCounters = analyzeHeroCounters(result.pickedHeroIds)
        val seasonNote = analyzeSeasonStrength(result.pickedHeroIds)

        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.d(TAG, "分析耗时: ${elapsed}ms（规则数: ${combos.size}）")

        return AnalysisResult(
            banIntentText = banIntent,
            comboMatch = comboMatch,
            counterStrategy = counterStrategy,
            heroCounters = heroCounters,
            seasonNote = seasonNote,
            analysisMs = elapsed
        )
    }

    // ---- Ban 阶段意图分析（task 6.1）----

    private fun analyzeBanIntent(bannedIds: List<String>): String {
        if (bannedIds.isEmpty()) return "对方尚未禁用英雄"

        val roleCounts = bannedIds
            .mapNotNull { heroes[it]?.role }
            .groupingBy { it }
            .eachCount()

        val total = bannedIds.size
        val assassinWarrior = (roleCounts["assassin"] ?: 0) + (roleCounts["warrior"] ?: 0)

        return when {
            total >= 3 && assassinWarrior.toDouble() / total >= 0.75 ->
                "对方集中 ban 刺客/战士，可能倾向后排输出阵容，建议选坦克/辅助护卫后排"
            total >= 3 && (roleCounts["mage"] ?: 0).toDouble() / total >= 0.6 ->
                "对方集中 ban 法师，可能偏向近战/突进打法"
            total >= 3 && (roleCounts["marksman"] ?: 0).toDouble() / total >= 0.5 ->
                "对方针对性 ban 射手，留意对方可能走无射手快攻套路"
            total >= 2 -> {
                val topRole = roleCounts.maxByOrNull { it.value }?.key ?: "未知"
                "对方禁用以${translateRole(topRole)}为主，意图待观察"
            }
            else -> "禁用意图不明显，关注对方选人阶段"
        }
    }

    // ---- 轻量级阵容/套路规则引擎（tasks 6.2 + 6.3）----

    private fun analyzeCombo(bannedIds: List<String>, pickedIds: List<String>): ComboMatchResult? {
        if (combos.isEmpty()) return null

        var bestScore = -1.0
        var bestCombo: Combo? = null

        for (combo in combos) {
            var score = 0.0
            val pickHits = pickedIds.count { it in combo.coreHeroes }
            score += pickHits * combo.pickWeight
            val banHits = bannedIds.count { it in combo.banTriggers }
            score += banHits * combo.banWeight
            if (seasonMeta != null && combo.tag in (seasonMeta?.strongComboTags ?: emptyList())) {
                score += combo.seasonWeight
            }
            if (pickHits < combo.minHits) continue
            if (score > bestScore && score >= combo.threshold) {
                bestScore = score
                bestCombo = combo
            }
        }

        if (bestCombo == null) return null

        val confidence = when {
            bestScore >= bestCombo.threshold * 2 -> "高"
            bestScore >= bestCombo.threshold -> "中"
            else -> "低"
        }
        return ComboMatchResult(
            comboName = bestCombo.name,
            comboTag = bestCombo.tag,
            confidence = confidence,
            score = bestScore
        )
    }

    // ---- Pick 阶段对位克制 TOP3（tasks 6.5 + 6.6 + 6.7）----

    private fun analyzeHeroCounters(pickedIds: List<String>): List<HeroCounterResult> {
        return pickedIds.map { heroId ->
            val hero = heroes[heroId]
            if (hero == null) {
                return@map HeroCounterResult(heroId, heroId, emptyList(), "暂无克制数据")
            }
            val top3 = buildTop3Counters(hero)
            HeroCounterResult(
                heroId = heroId,
                heroName = hero.name,
                top3Counters = top3,
                note = if (top3.isEmpty()) "暂无克制数据" else ""
            )
        }
    }

    private fun buildTop3Counters(hero: Hero): List<CounterHero> {
        val result = mutableListOf<CounterHero>()
        hero.hardCounters.take(3).forEach { id ->
            heroes[id]?.let { result.add(CounterHero(id, it.name, "强克制")) }
        }
        if (result.size < 3) {
            hero.softCounters.take(3 - result.size).forEach { id ->
                heroes[id]?.let { result.add(CounterHero(id, it.name, "弱克制")) }
            }
        }
        return result.take(3)
    }

    // ---- 赛季强势提示（task 6.3）----

    private fun analyzeSeasonStrength(pickedIds: List<String>): String? {
        val meta = seasonMeta ?: return null
        val strongPicked = pickedIds.filter { it in meta.strongHeroes }
        if (strongPicked.isEmpty()) return null
        val names = strongPicked.mapNotNull { heroes[it]?.name }
        return "对方已拿当前赛季强势英雄：${names.joinToString("、")}，优先参考对位克制建议"
    }

    private fun translateRole(role: String) = when (role) {
        "warrior" -> "战士"; "mage" -> "法师"; "assassin" -> "刺客"
        "marksman" -> "射手"; "tank" -> "坦克"; "support" -> "辅助"
        else -> role
    }
}
