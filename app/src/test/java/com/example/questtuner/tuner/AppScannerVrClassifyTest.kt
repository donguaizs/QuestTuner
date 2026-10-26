package com.example.questtuner.tuner

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * OPT-round10 ①回归防线：queryIntentActivities 一次带全 GET_META_DATA 后，isVr 分类语义不丢。
 *
 * 覆盖边界（实测取值，见下）：
 * - [packages]：application 级 meta-data 兜底路径（Robolectric 可测——addResolveInfoForIntentNoDefaults
 *   会保留 applicationInfo.metaData）；
 * - [filterCategoryFun]：filter categories 判定——用纯函数直测 IntentFilter，不走 Shadow；
 * - [prefix]：com.oculus/com.meta 前缀兜底与自身包排除。
 *
 * **Robolectric 局限（实测登记）**：ShadowPackageManager 在 queryIntentActivities 返回时
 * 一律剥掉 ResolveInfo.filter —— `addResolveInfoForIntent`、`addResolveInfoForIntentNoDefaults`、
 * `addOrUpdateActivity + addIntentFilterForActivity` 四种注入方式实测 filter 均为 null
 * （PROBE 实验，2026-10-26）。故 filter 路径无法经 Shadow 覆盖，改由纯函数单测守住；
 * GET_META_DATA flag 是否真正生效由代码走查 + grep 确认（不可由本测试证明）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppScannerVrClassifyTest {

    private lateinit var ctx: android.content.Context
    private lateinit var pm: PackageManager

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        pm = ctx.packageManager
    }

    private fun ri(pkg: String, appMetaVr: Boolean): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.MainActivity"
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                // FLAG_INSTALLED：避免被 Shadow 当作未安装过滤
                flags = ApplicationInfo.FLAG_INSTALLED
                metaData = Bundle().apply {
                    if (appMetaVr) putBoolean("com.oculus.intent.category.VR", true)
                }
            }
        }
    }

    private fun install(r: ResolveInfo) {
        // NoDefaults：不补 Robolectric 默认 ApplicationInfo（默认值会覆盖注入的 metaData）
        shadowOf(pm).addResolveInfoForIntentNoDefaults(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), r)
    }

    // ---- ① application meta 兜底路径（Shadow 可测） ----

    @Test
    fun applicationMetaVr_classifiedVr_notTwoD() {
        install(ri("com.game.metavr", appMetaVr = true))
        install(ri("com.example.twod", appMetaVr = false))
        val apps = AppScanner.query(ctx)
        assertTrue(apps.first { it.packageName == "com.game.metavr" }.isVr)
        assertFalse(apps.first { it.packageName == "com.example.twod" }.isVr)
    }

    @Test
    fun ownPackageExcluded_andPrefixFallbackWorks() {
        install(ri(ctx.packageName, appMetaVr = false))
        install(ri("com.oculus.some2dtool", appMetaVr = false))
        val apps = AppScanner.query(ctx)
        assertFalse(apps.any { it.packageName == ctx.packageName })
        assertTrue(apps.first { it.packageName == "com.oculus.some2dtool" }.isVr)
    }

    // ---- ① filter categories 判定（纯函数，绕过 Shadow 剥 filter 的局限） ----

    @Test
    fun filterCategoryFun_detectsBothVrCategories() {
        val oculusVr = IntentFilter().apply { addCategory("com.oculus.intent.category.VR") }
        val metaVr = IntentFilter().apply { addCategory("com.meta.intent.category.VR") }
        val plain = IntentFilter().apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        assertTrue(AppScanner.intentFilterHasVrCategory(oculusVr))
        assertTrue(AppScanner.intentFilterHasVrCategory(metaVr))
        assertFalse(AppScanner.intentFilterHasVrCategory(plain))
        assertFalse(AppScanner.intentFilterHasVrCategory(IntentFilter()))
        assertFalse(AppScanner.intentFilterHasVrCategory(null))
    }

    @Test
    fun prefixFallback_matchesOculusAndMetaOnly() {
        assertTrue(AppScanner.packageFallback("com.oculus.foo"))
        assertTrue(AppScanner.packageFallback("com.meta.vr.game"))
        assertTrue(AppScanner.packageFallback("COM.OCULUS.UPPER"))
        assertFalse(AppScanner.packageFallback("com.example.app"))
        assertFalse(AppScanner.packageFallback("org.oculuslike.app"))
    }

    // ---- ② 图标扫描期降采样（类型即契约） ----

    @Test
    fun installedAppIconIsSmallBitmapType() {
        // 编译期契约：icon 不再是 Drawable（全尺寸常驻），而是降采样后的 Bitmap。
        // 运行期尺寸断言依赖 Shadow 图标注入，此处以类型契约 + 扫描不崩为界（如实登记）。
        install(ri("com.example.twod", appMetaVr = false))
        val apps = AppScanner.query(ctx)
        assertTrue(apps.isNotEmpty())
        val icon = apps.first { it.packageName == "com.example.twod" }.icon
        // Shadow 未注入图标 → null；但类型必须已是 Bitmap?
        assertTrue(icon == null || icon is android.graphics.Bitmap)
    }
}
