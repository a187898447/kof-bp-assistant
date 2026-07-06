package com.kof.bpassistant.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.util.zip.ZipInputStream

/**
 * JsonUtils: 纯静态工具，提供安全的 JSON 解析和数据文件操作。
 * 所有方法在出错时返回降级值，不向上层抛出异常。
 */
object JsonUtils {

    private const val TAG = "JsonUtils"
    private val gson = Gson()

    /**
     * 安全地将 JSON 字符串反序列化为指定类型。
     * 解析失败时返回 null 并记录日志。
     */
    fun <T> parseOrNull(json: String, clazz: Class<T>): T? {
        return try {
            gson.fromJson(json, clazz)
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败 [${clazz.simpleName}]: ${e.message}")
            null
        }
    }

    /**
     * 读取文件内容为字符串；文件不存在或读取失败时返回 null，记录 warn 日志。
     */
    fun readFileOrNull(file: File): String? {
        if (!file.exists()) {
            Log.w(TAG, "文件不存在: ${file.absolutePath}")
            return null
        }
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "读取文件失败 [${file.name}]: ${e.message}")
            null
        }
    }

    /**
     * 原子性解压替换：
     *   1. 解压到临时目录
     *   2. 用 Gson 真实解析每个必需文件，校验结构合法（非首字符判断）
     *   3. 逐文件备份旧文件后 rename 新文件，全部 renameTo 检查返回值
     *   4. 任意步骤失败：回滚已完成的备份，清理临时目录，旧数据完整保留
     *
     * @return 成功替换的文件名列表；任何步骤失败则返回空列表
     */
    fun unzipToDir(zipFile: File, targetDir: File): List<String> {
        if (!zipFile.exists()) {
            Log.e(TAG, "zip 文件不存在: ${zipFile.absolutePath}")
            return emptyList()
        }

        val tmpDir = File(targetDir.parent, "${targetDir.name}_tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        // 步骤 1：解压到临时目录
        val extracted = mutableListOf<String>()
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(tmpDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                        extracted.add(entry.name)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压到临时目录失败: ${e.message}")
            tmpDir.deleteRecursively()
            return emptyList()
        }

        // 步骤 2：用 Gson 真实解析必需文件，而非只检查首字符
        val required = listOf(
            "heroes.json", "combos.json", "season_meta.json",
            "counter_strategies.json", "layout_config.json", "hero_hashes.json"
        )
        for (name in required) {
            val f = File(tmpDir, name)
            if (!f.exists()) {
                Log.e(TAG, "数据包缺少必需文件: $name，中止替换")
                tmpDir.deleteRecursively()
                return emptyList()
            }
            try {
                val content = f.readText(Charsets.UTF_8)
                // 用 Gson 真实解析为 JsonElement，捕获语法错误
                val element = gson.fromJson(content, com.google.gson.JsonElement::class.java)
                if (element == null || element.isJsonNull) {
                    Log.e(TAG, "必需文件解析为 null: $name，中止替换")
                    tmpDir.deleteRecursively()
                    return emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "必需文件 JSON 解析失败: $name — ${e.message}，中止替换")
                tmpDir.deleteRecursively()
                return emptyList()
            }
        }

        // 步骤 3：逐文件备份旧文件 → rename 新文件，全部检查 renameTo 返回值
        val backups = mutableMapOf<File, File>()   // destFile → backupFile（已成功备份的条目）
        val renamed = mutableListOf<Pair<File, File>>() // (srcTmp, dest)（已成功 rename 的条目）

        for (name in extracted) {
            val srcFile = File(tmpDir, name)
            val destFile = File(targetDir, name)
            destFile.parentFile?.mkdirs()

            // 备份现有文件
            if (destFile.exists()) {
                val backup = File(targetDir, "$name.bak")
                if (!destFile.renameTo(backup)) {
                    Log.e(TAG, "备份旧文件失败: $name，回滚并中止")
                    rollback(backups, renamed)
                    tmpDir.deleteRecursively()
                    return emptyList()
                }
                backups[destFile] = backup
            }

            // 将新文件移入正式目录
            if (!srcFile.renameTo(destFile)) {
                Log.e(TAG, "移动新文件失败: $name，回滚并中止")
                rollback(backups, renamed)
                tmpDir.deleteRecursively()
                return emptyList()
            }
            renamed.add(srcFile to destFile)
        }

        // 全部成功：清理备份和临时目录
        backups.values.forEach { it.delete() }
        tmpDir.deleteRecursively()
        Log.i(TAG, "原子替换完成，共 ${extracted.size} 个文件")
        return extracted
    }

    /** 回滚：恢复所有已备份的旧文件，并删除已 rename 到正式目录的新文件 */
    private fun rollback(
        backups: Map<File, File>,
        renamed: List<Pair<File, File>>
    ) {
        renamed.forEach { (_, dest) -> dest.delete() }
        backups.forEach { (dest, backup) ->
            if (!backup.renameTo(dest)) {
                Log.e(TAG, "回滚失败，无法恢复备份: ${dest.name}，数据可能损坏！")
            }
        }
    }

    /**
     * 计算文件 MD5（十六进制小写字符串）；失败时返回空字符串。
     */
    fun md5Hex(file: File): String {
        if (!file.exists()) return ""
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            file.inputStream().buffered().use { stream ->
                val buf = ByteArray(8192)
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "MD5 计算失败: ${e.message}")
            ""
        }
    }

    /**
     * 从 assets 复制打包数据文件到内部存储。
     * @param force 为 false 时仅在目标文件不存在时复制（首次安装）；
     *              为 true 时无条件覆盖（升级安装、versionCode 变化时刷新写死数据）。
     */
    fun copyAssetsToDataDir(
        context: Context,
        assetNames: List<String>,
        dataDir: File,
        force: Boolean = false
    ) {
        assetNames.forEach { name ->
            val target = File(dataDir, name)
            if (force || !target.exists()) {
                try {
                    context.assets.open("kof_data/$name").use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                    Log.i(TAG, "打包数据已复制: $name（force=$force）")
                } catch (e: Exception) {
                    Log.w(TAG, "打包数据复制失败 [$name]: ${e.message}")
                }
            }
        }
    }
}
