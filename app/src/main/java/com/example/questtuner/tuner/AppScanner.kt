package com.example.questtuner.tuner

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(
    val packageName: String,
    val label: String,
    /** OPT-round10 ②：扫描期已降采样的 64px 小图（杜绝全尺寸 Drawable 常驻）；null=无图标 */
    val icon: Bitmap?,
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
        // OPT-round10 ①：GET_META_DATA 一次带全（component 级 + application 级 meta 均填充，
        // 语义等价论证见 OPT-REPORT-round10 ②节）——消除每应用一次 getActivityInfo 的 N+1 IPC
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        resolveInfos.forEach { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) return@forEach
            // 检查 activity 或 application 的 VR 类别
            val isVr = hasVrCategory(ri)
            val item = InstalledApp(
                packageName = pkg,
                label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                // OPT-round10 ②：扫描期降采样 64px——内存 N×16KB（150 应用≈2.4MB，vs 全尺寸 Drawable 21~150MB）
                icon = runCatching { ri.loadIcon(pm).toBitmap(64, 64) }.getOrNull(),
                isVr = isVr,
            )
            if (isVr) vr.add(item) else twoD.add(item)
        }
        return vr.sortedBy { it.label.lowercase() } + twoD.sortedBy { it.label.lowercase() }
    }

    /**
     * Quest VR 启动页声明 <category android:name="com.meta.intent.category.VR" />
     * queryIntentActivities 返回的 ResolveInfo.filter 携带 manifest categories，优先读；
     * application meta-data 作为兜底（GET_META_DATA 已在 queryIntentActivities 一次带全）。
     */
    private fun hasVrCategory(ri: android.content.pm.ResolveInfo): Boolean {
        // 1) intent-filter categories（VR launcher 的标准声明方式）
        if (intentFilterHasVrCategory(ri.filter)) return true
        // 2) 兜底：application 级 meta-data（queryIntentActivities 已带 GET_META_DATA，零额外 IPC）
        // ★读 applicationInfo.metaData（application 级）；activityInfo.metaData 是 component 级，多数应用为空
        val appMeta = ri.activityInfo.applicationInfo?.metaData
        if (appMeta?.getBoolean(VR_CATEGORY_META, false) == true) return true
        if (appMeta?.getBoolean(VR_CATEGORY_META2, false) == true) return true
        return packageFallback(ri.activityInfo.packageName)
    }

    /**
     * OPT-round10 ①：filter categories 判定的纯函数（可单测）。
     * 抽出原因：Robolectric 4.12.2 的 ShadowPackageManager 在 queryIntentActivities 返回时
     * 一律剥掉 ResolveInfo.filter（实测四种注入方式均如此，见 AppScannerVrClassifyTest 注释），
     * 该路径无法用 Robolectric 覆盖——用本纯函数直接在 JVM 构造 IntentFilter 守住判定逻辑。
     */
    internal fun intentFilterHasVrCategory(f: android.content.IntentFilter?): Boolean {
        if (f == null) return false
        for (i in 0 until f.countCategories()) {
            val c = f.getCategory(i)
            if (c == VR_CATEGORY_META || c == VR_CATEGORY_META2) return true
        }
        return false
    }

    /** com.oculus* / com.meta.* 前缀兜底（纯字符串，零 IPC）。 */
    internal fun packageFallback(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.startsWith("com.oculus") || p.startsWith("com.meta.")
    }
}
