package com.example.questtuner.tuner

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isVr: Boolean,
)

/**
 * Enumerate installed launchable apps.
 * VR detection on Quest: a VR app's launch activity (or the package) declares
 * meta-data "com.oculus.vr.intent.category.VR" / category
 * "com.meta.intent.category.VR" on its launcher intent, or is not a 2D
 * launcher at all. Since getLaunchIntentForPackage loses manifest categories,
 * we inspect the resolve info instead.
 */
object AppScanner {

    private const val VR_CATEGORY_META = "com.oculus.intent.category.VR"
    private const val VR_CATEGORY_META2 = "com.meta.intent.category.VR"

    fun query(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val vr = mutableListOf<InstalledApp>()
        val twoD = mutableListOf<InstalledApp>()
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
        intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        resolveInfos.forEach { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) return@forEach
            // 检查 activity 或 application 的 VR 类别
            val isVr = hasVrCategory(pm, ri.activityInfo)
            val item = InstalledApp(
                packageName = pkg,
                label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                isVr = isVr,
            )
            if (isVr) vr.add(item) else twoD.add(item)
        }
        return vr.sortedBy { it.label.lowercase() } + twoD.sortedBy { it.label.lowercase() }
    }

    /**
     * Quest VR 启动页声明 <category android:name="com.meta.intent.category.VR" />
     * queryIntentActivities 不返回 manifest categories，需要从 activityInfo 手动读。
     */
    private fun hasVrCategory(pm: PackageManager, info: android.content.pm.ActivityInfo): Boolean {
        // queryIntentActivities 已解析的 intent filter 在 ri.filter，但该 API 已是 resolved,
        // 手动加载一次完整 ActivityInfo
        val full = pm.getActivityInfo(android.content.ComponentName(info.packageName, info.name), 0)
        // ActivityInfo 无法直接读 categories；VR 应用还会声明 oculus intent filter
        // 简化：VR 应用通常没有 CATEGORY_LAUNCHER 而是 CATEGORY_INFO + VR 类别
        // 检查 application meta-data
        val appMeta = full.applicationInfo.metaData
        val metaVr = appMeta?.getBoolean(VR_CATEGORY_META, false)
            ?: appMeta?.getBoolean(VR_CATEGORY_META2, false)
            ?: false
        if (metaVr) return true
        // 兜底：知名 VR 包名前缀
        val pkgLower = info.packageName.lowercase()
        return pkgLower.startsWith("com.oculus") || pkgLower.startsWith("com.meta.")
    }
}
