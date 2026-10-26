package com.example.questtuner.ui

import com.example.questtuner.tuner.AppProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPT-round10 ④：filterProfiles 纯函数单测（label/包名子串、大小写不敏感、空 query 全量、null label 兜底） */
class ProfileFilterTest {

    private val profiles = listOf(
        AppProfile(packageName = "com.meta.xr.game", label = "XR Game"),
        AppProfile(packageName = "com.example.twod", label = null),
        AppProfile(packageName = "com.vendor.video", label = "Video Player"),
        AppProfile(packageName = "global", label = "全局默认"),
    )

    @Test
    fun blankQuery_returnsAll() {
        assertEquals(profiles, filterProfiles(profiles, ""))
        assertEquals(profiles, filterProfiles(profiles, "   "))
        assertEquals(emptyList<AppProfile>(), filterProfiles(emptyList(), "x"))
    }

    @Test
    fun matchesLabelAndPackage_casInsensitive() {
        // label 命中（大小写不敏感）
        assertEquals(listOf("com.meta.xr.game"),
            filterProfiles(profiles, "xr game").map { it.packageName })
        assertEquals(listOf("com.vendor.video"),
            filterProfiles(profiles, "VIDEO").map { it.packageName })
        // 包名命中
        assertEquals(listOf("com.example.twod"),
            filterProfiles(profiles, "twod").map { it.packageName })
        // 子串命中（跨段）
        assertEquals(listOf("com.meta.xr.game"),
            filterProfiles(profiles, "meta.xr").map { it.packageName })
    }

    @Test
    fun nullLabel_fallsBackToPackageName_andNoMatchIsEmpty() {
        // label 为 null 的条目仍可经包名命中
        assertEquals(listOf("com.example.twod"),
            filterProfiles(profiles, "example").map { it.packageName })
        // 无匹配
        assertTrue(filterProfiles(profiles, "zzz-nothing").isEmpty())
    }
}
