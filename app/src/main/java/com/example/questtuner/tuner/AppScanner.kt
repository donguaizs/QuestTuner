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
            val isVr = hasVrCategory(pm, ri)
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
     * queryIntentActivities 返回的 ResolveInfo.filter 携带 manifest categories，优先读；
     * application meta-data 作为兜底（需 GET_META_DATA）。
     */
    private fun hasVrCategory(pm: PackageManager, ri: android.content.pm.ResolveInfo): Boolean {
        // 1) intent-filter categories（VR launcher 的标准声明方式）
        val f = ri.filter
        if (f != null) {
            for (i in 0 until f.countCategories()) {
                val c = f.getCategory(i)
                if (c == VR_CATEGORY_META || c == VR_CATEGORY_META2) return true
            }
        }
        // 2) 兜底：application meta-data（需 GET_META_DATA）
        val full = runCatching {
            pm.getActivityInfo(
                android.content.ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                PackageManager.GET_META_DATA,
            )
        }.getOrNull() ?: return packageFallback(ri.activityInfo.packageName)
        val appMeta = full.applicationInfo?.metaData
        val metaVr = appMeta?.getBoolean(VR_CATEGORY_META, false)
            ?: appMeta?.getBoolean(VR_CATEGORY_META2, false)
            ?: false
        if (metaVr) return true
        return packageFallback(ri.activityInfo.packageName)
    }

    private fun packageFallback(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.startsWith("com.oculus") || p.startsWith("com.meta.")
    }
}
