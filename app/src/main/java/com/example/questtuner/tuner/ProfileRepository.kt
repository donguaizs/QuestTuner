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
        // cpu/gpu 各自独立生成（setprop 相互独立，无成对必要；与 describeProfile 独立显示一致）
        cpuLevel?.let { addAll(listOf("setprop debug.oculus.cpuLevel $it")) }
        gpuLevel?.let { addAll(listOf("setprop debug.oculus.gpuLevel $it")) }
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
                        // BUG-040：超域字段置 null 丢弃（不 coerce——静默产生用户没写过的值
                        // 不诚实；置 null 让 describeProfile 显示「未设该项」，且不下发超域命令）
                        refreshRate = sanitizeRefreshRate(o.optInt("refreshRate", -1)),
                        textureWidth = sanitizeTexture(o.optInt("textureWidth", -1), isHeight = false),
                        textureHeight = sanitizeTexture(o.optInt("textureHeight", -1), isHeight = true),
                        cpuLevel = sanitizeLevel(o.optInt("cpuLevel", -1)),
                        gpuLevel = sanitizeLevel(o.optInt("gpuLevel", -1)),
                    )
                    // BUG-040：超域观测——一次性 Log.w，静默修正可诊断
                    if (p.refreshRate == null && o.has("refreshRate") ||
                        p.textureWidth == null && o.has("textureWidth") ||
                        p.textureHeight == null && o.has("textureHeight") ||
                        p.cpuLevel == null && o.has("cpuLevel") ||
                        p.gpuLevel == null && o.has("gpuLevel")) {
                        android.util.Log.w("ProfileRepository", "out-of-range fields dropped for ${p.packageName}")
                    }
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
        /** BUG-040：与 UI 同域的数值校验（FilterChip/Slider/coerceAtMost 同源规则）。
         *  纯函数可单测：入参 JSON 原始值，出参 null=超域丢弃。 */
        fun sanitizeRefreshRate(v: Int): Int? = v.takeIf { it in intArrayOf(72, 90, 120) }

        /** texture 上限统一 3208：README「上限 3072×3208」的 Quest 3 硬件上限语义。
         *  UI 侧 TunerApp 的 coerceAtMost(3200) 是缩放计算产物（1760*2>3200 才被截），
         *  与本域不冲突——reload 用 3208 校验存量，UI 不改。 */
        fun sanitizeTexture(v: Int, isHeight: Boolean): Int? =
            v.takeIf { it > 0 && it <= if (isHeight) 3208 else 3072 }

        fun sanitizeLevel(v: Int): Int? = v.takeIf { it in 0..4 }

        fun of(filesDir: File): ProfileRepository =
            ProfileRepository(File(filesDir, "profiles.json"))
    }
}
