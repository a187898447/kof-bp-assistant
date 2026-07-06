package com.kof.bpassistant.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * DataRepository 从内部存储加载所有本地 JSON 数据文件。
 * 文件不存在或解析失败时返回空列表/空对象，记录日志，不抛出异常。
 */
class DataRepository(private val context: Context) {

    private val gson = Gson()
    private val tag = "DataRepository"

    // 内部存储数据目录：/data/data/<pkg>/files/kof_data/
    val dataDir: File
        get() = File(context.filesDir, "kof_data").also { it.mkdirs() }

    // ---- 加载接口 ----

    fun loadHeroes(): List<Hero> =
        loadJsonList("heroes.json", object : TypeToken<List<Hero>>() {})

    fun loadCombos(): List<Combo> =
        loadJsonList("combos.json", object : TypeToken<List<Combo>>() {})

    fun loadSeasonMeta(): SeasonMeta? =
        loadJsonObject("season_meta.json", SeasonMeta::class.java)

    fun loadCounterStrategies(): List<CounterStrategy> =
        loadJsonList("counter_strategies.json", object : TypeToken<List<CounterStrategy>>() {})

    fun loadLayoutConfigs(): List<LayoutConfig> =
        loadJsonList("layout_config.json", object : TypeToken<List<LayoutConfig>>() {})

    fun loadHeroHashes(): Map<String, Long> =
        loadJsonObject("hero_hashes.json", object : TypeToken<Map<String, Long>>() {}.type)
            ?: emptyMap()

    // ---- 内部工具 ----

    private fun <T> loadJsonList(fileName: String, typeToken: TypeToken<List<T>>): List<T> {
        val file = File(dataDir, fileName)
        if (!file.exists()) {
            Log.w(tag, "数据文件不存在，返回空列表：$fileName")
            return emptyList()
        }
        return try {
            val json = file.readText(Charsets.UTF_8)
            gson.fromJson(json, typeToken.type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(tag, "解析 $fileName 失败：${e.message}")
            emptyList()
        }
    }

    private fun <T> loadJsonObject(fileName: String, clazz: Class<T>): T? {
        val file = File(dataDir, fileName)
        if (!file.exists()) {
            Log.w(tag, "数据文件不存在：$fileName")
            return null
        }
        return try {
            val json = file.readText(Charsets.UTF_8)
            gson.fromJson(json, clazz)
        } catch (e: Exception) {
            Log.e(tag, "解析 $fileName 失败：${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadJsonObject(fileName: String, type: java.lang.reflect.Type): T? {
        val file = File(dataDir, fileName)
        if (!file.exists()) {
            Log.w(tag, "数据文件不存在：$fileName")
            return null
        }
        return try {
            val json = file.readText(Charsets.UTF_8)
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(tag, "解析 $fileName 失败：${e.message}")
            null
        }
    }

    /** 将 json 字符串直接写入数据文件（数据包解压后调用） */
    fun writeDataFile(fileName: String, content: String) {
        File(dataDir, fileName).writeText(content, Charsets.UTF_8)
    }
}
