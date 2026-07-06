package com.kof.bpassistant.data

// 英雄基础信息
data class Hero(
    val id: String,
    val name: String,
    val role: String,          // warrior / mage / assassin / support / tank / marksman
    val hardCounters: List<String> = emptyList(),   // hero ids that hard-counter this hero
    val softCounters: List<String> = emptyList()    // hero ids that soft-counter this hero
)

// 单个克制关系
data class Counter(
    val heroId: String,
    val counterHeroId: String,
    val type: String,          // "hard" or "soft"
    val reason: String = ""
)

// 阵容/套路规则
data class Combo(
    val id: String,
    val name: String,
    val tag: String,           // e.g. "burst", "poke", "tank", "fun", "special"
    val coreHeroes: List<String>,         // hero ids that are core triggers
    val banTriggers: List<String> = emptyList(),  // hero ids whose ban also scores
    val minHits: Int = 2,                 // minimum core heroes needed to trigger
    val pickWeight: Double = 1.0,
    val banWeight: Double = 0.5,
    val seasonWeight: Double = 0.0,       // extra weight if current season meta
    val threshold: Double = 1.5           // minimum score to report
)

// 赛季强势元数据
data class SeasonMeta(
    val season: String,
    val version: String,
    val strongHeroes: List<String>,       // hero ids currently strong
    val strongComboTags: List<String>,    // combo tags currently dominant
    val tierList: Map<String, Int> = emptyMap()  // hero_id -> tier (1=S, 2=A, ...)
)

// ROI 布局配置（比例坐标 0.0～1.0）
data class LayoutConfig(
    val screenRatio: String,              // e.g. "20:9"
    val statusBarRatioH: Double = 0.03,   // status bar height as ratio of screen height
    val enemyBanSlots: List<SlotRect>,
    val enemyPickSlots: List<SlotRect>
)

data class SlotRect(
    val x: Double,    // left edge ratio
    val y: Double,    // top edge ratio
    val w: Double,    // width ratio
    val h: Double     // height ratio
)
