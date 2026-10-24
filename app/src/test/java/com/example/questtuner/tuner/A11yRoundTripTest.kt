package com.example.questtuner.tuner

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * BUG-025 回归防线（round-7 盲区 3）：SecureAutomation a11y 两键的 Settings 往返闭环。
 * 覆盖：追加式写入 / 幂等重入 / 回读探测 / 第三方服务保留红线 / accessibility_enabled 联动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A11yRoundTripTest {

    private lateinit var ctx: Context
    private val flat: String by lazy { SecureAutomation.a11yComponentName(ctx).flattenToString() }

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ctx = app
        // Robolectric 4.12：WRITE_SECURE_SETTINGS 是 signature 级权限，shadowOf(app)
        // .grantPermissions 对 ShadowContextImpl 生效但 checkPermission 走的是
        // ShadowPackageManager 的 packageInfo.requestedPermissions 路径——需要把权限
        // 直接写进本包 PackageInfo 再 rebroadcast（4.12 无 grantPermission(String,String)）
        val pm = app.packageManager
        val pi = pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val perms = pi.requestedPermissions?.toMutableList() ?: mutableListOf()
        if (!perms.contains(android.Manifest.permission.WRITE_SECURE_SETTINGS)) {
            perms.add(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            pi.requestedPermissions = perms.toTypedArray()
        }
        val granted = pi.requestedPermissionsFlags ?: IntArray(perms.size)
        // REQUESTED_PERMISSION_GRANTED = 1<<1
        for (i in perms.indices) {
            if (i < granted.size) granted[i] = granted[i] or 2
            else if (i == granted.size) {}
        }
        pi.requestedPermissionsFlags = granted
        val shadowPm = shadowOf(pm)
        shadowPm.installPackage(pi)
        shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(ctx.contentResolver, "enabled_accessibility_services", "")
    }

    private fun enabledList(): String =
        Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: ""

    @Test
    fun enableWritesAndReadsBack() {
        val r = SecureAutomation.enableA11yService(ctx)
        assertTrue("enable 应 Ok（实际 $r）", r is SecureAutomation.CapProbe.Ok)
        assertTrue(enabledList().split(':').contains(flat))          // 往返：写入可读回
        assertEquals("1", Settings.Secure.getString(
            ctx.contentResolver, "accessibility_enabled"))           // 总开关联动
    }

    @Test
    fun enableIsIdempotent_noDuplicate() {
        SecureAutomation.enableA11yService(ctx)
        SecureAutomation.enableA11yService(ctx)                       // 二次 enable
        assertEquals(1, enabledList().split(':').count { it == flat }) // 不重复追加
    }

    @Test
    fun disableRemovesOurs_keepsThirdParty() {
        // 预置第三方服务（红线：我们开/关不动它）
        Settings.Secure.putString(ctx.contentResolver,
            "enabled_accessibility_services", "com.other.svc/.OtherService")
        SecureAutomation.enableA11yService(ctx)
        assertTrue(enabledList().contains("com.other.svc/.OtherService"))   // 追加式保留

        val r = SecureAutomation.disableA11yService(ctx)
        assertTrue(r is SecureAutomation.CapProbe.Ok)
        val list = enabledList().split(':')
        assertFalse(list.contains(flat))                              // 我们已移除
        assertTrue(list.contains("com.other.svc/.OtherService"))      // 第三方保留
        // 第三方仍在 → accessibility_enabled 不应为 0（disable 的 rest 非空分支）
        assertEquals("1", Settings.Secure.getString(
            ctx.contentResolver, "accessibility_enabled"))
    }

    @Test
    fun disableLastService_clearsMasterSwitch() {
        SecureAutomation.enableA11yService(ctx)
        SecureAutomation.disableA11yService(ctx)
        assertEquals("0", Settings.Secure.getString(
            ctx.contentResolver, "accessibility_enabled"))            // 移除至空 → 总开关关
        assertEquals("", enabledList())
    }
}
