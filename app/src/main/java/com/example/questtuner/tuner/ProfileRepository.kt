package com.example.questtuner.tuner

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 按应用配置 Profile（JSON 存储，org.json 为 Android 内置）。
 */
data class AppProfile(
    val packageName: String,
    val label: String? = null,
    val refreshRate: Int? = null,      // 72/90/120
    val textureWidth: Int? = null,     // Quest3 默认约 1680x1760/眼
    val textureHeight: Int? = null,
    val cpuLevel: Int? = null,         // 0..4
    val gpuLevel: Int? = null,         // 0..4
) {
    fun toCommands(): List<String> = buildList {
        refreshRate?.let { addAll(TuningCommands.setRefreshRate(it)) }
        textureWidth?.let { w -> textureHeight?.let { h -> addAll(TuningCommands.setResolution(w, h)) } }
        cpuLevel?.let { c -> gpuLevel?.let { g -> addAll(TuningCommands.setCpuGpuLevel(c, g)) } }
    }
}

class ProfileRepository(file: File) {

    private val store: File = file
    private val all = LinkedHashMap<String, AppProfile>()

    init { reload() }

    @Synchronized
    fun reload() {
        if (store.exists()) {
            // 先解析到局部变量，成功后才替换 all；失败不动 all（保持内存旧值/空表）
            val parsed = LinkedHashMap<String, AppProfile>()
            val ok = runCatching {
                val arr = JSONArray(store.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val p = AppProfile(
                        packageName = o.getString("packageName"),
                        label = o.optString("label").ifEmpty { null },
                        refreshRate = o.optInt("refreshRate", -1).takeIf { it > 0 },
                        textureWidth = o.optInt("textureWidth", -1).takeIf { it > 0 },
                        textureHeight = o.optInt("textureHeight", -1).takeIf { it > 0 },
                        cpuLevel = o.optInt("cpuLevel", -1).takeIf { it >= 0 },
                        gpuLevel = o.optInt("gpuLevel", -1).takeIf { it >= 0 },
                    )
                    parsed[p.packageName] = p
                }
            }.isSuccess
            if (ok) {
                all.clear()
                all.putAll(parsed)
            } else {
                // 解析失败：保留旧文件与内存现状，记日志（不覆盖、不清空）
                android.util.Log.w("ProfileRepository", "profiles.json corrupted, keep ${all.size} in-memory")
            }
        }
    }

    @Synchronized
    fun profiles(): List<AppProfile> = all.values.toList()

    @Synchronized
    fun save(p: AppProfile) {
        all[p.packageName] = p
        persist()
    }

    @Synchronized
    fun delete(packageName: String) {
        all.remove(packageName)
        persist()
    }

    @Synchronized
    fun find(packageName: String): AppProfile? = all[packageName]

    private fun persist() {
        val arr = JSONArray()
        all.values.forEach { p ->
            val o = JSONObject()
            o.put("packageName", p.packageName)
            p.label?.let { o.put("label", it) }
            p.refreshRate?.let { o.put("refreshRate", it) }
            p.textureWidth?.let { o.put("textureWidth", it) }
            p.textureHeight?.let { o.put("textureHeight", it) }
            p.cpuLevel?.let { o.put("cpuLevel", it) }
            p.gpuLevel?.let { o.put("gpuLevel", it) }
            arr.put(o)
        }
        store.parentFile?.mkdirs()
        // 临时文件 + renameTo 原子替换，避免写一半被杀留下半截 JSON
        val tmp = File(store.parentFile, store.name + ".tmp")
        tmp.writeText(arr.toString(2))
        if (!tmp.renameTo(store)) {
            // 极少数文件系统 rename 失败（目标被占用等）回退直接写
            store.writeText(arr.toString(2))
        }
        tmp.delete()
    }

    companion object {
        fun of(filesDir: File): ProfileRepository =
            ProfileRepository(File(filesDir, "profiles.json"))
    }
}
