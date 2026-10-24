package com.example.questtuner.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-040/041：Profile 输入链域校验与包名正则（纯函数）。 */
class ProfileValidationTest {

    // ---- isValidPackageName（BUG-041/R6-O1） ----

    @Test
    fun validPackageNames() {
        assertTrue(ProfileRepository.isValidPackageName("com.a"))
        assertTrue(ProfileRepository.isValidPackageName("com.example.app"))
        assertTrue(ProfileRepository.isValidPackageName("com.beatgames.beatsaber"))
        assertTrue(ProfileRepository.isValidPackageName("Com.Example.Mixed_Case1"))
        assertTrue(ProfileRepository.isValidPackageName("global"))   // 白名单
    }

    @Test
    fun invalidPackageNames() {
        assertFalse("单段不合法（Android 规范要求至少一个分隔点）",
            ProfileRepository.isValidPackageName("abc"))
        assertFalse("空串", ProfileRepository.isValidPackageName(""))
        assertFalse("尾点", ProfileRepository.isValidPackageName("com."))
        assertFalse("首段数字", ProfileRepository.isValidPackageName("com.123abc"))
        assertFalse("前导点", ProfileRepository.isValidPackageName(".com.a"))
        assertFalse("注入字符", ProfileRepository.isValidPackageName("com.a; echo x"))
        assertFalse("空格", ProfileRepository.isValidPackageName("com.a b"))
        assertFalse("斜杠", ProfileRepository.isValidPackageName("com/../a"))
        // 长度 >100
        val long = ("a" + ".b".repeat(50))
        assertFalse("长度 101+ 超限", ProfileRepository.isValidPackageName(long))
    }

    // ---- sanitizeRefreshRate（BUG-040） ----

    @Test
    fun refreshRateDomain() {
        assertEquals(72, ProfileRepository.sanitizeRefreshRate(72))
        assertEquals(90, ProfileRepository.sanitizeRefreshRate(90))
        assertEquals(120, ProfileRepository.sanitizeRefreshRate(120))
        assertNull("非枚举值丢弃", ProfileRepository.sanitizeRefreshRate(7))
        assertNull("超上限丢弃", ProfileRepository.sanitizeRefreshRate(144))
        assertNull("默认 -1 丢弃", ProfileRepository.sanitizeRefreshRate(-1))
    }

    // ---- sanitizeTexture（BUG-040：宽 3072 / 高 3208 统一域） ----

    @Test
    fun textureDomain() {
        assertEquals(1680, ProfileRepository.sanitizeTexture(1680, isHeight = false))
        assertEquals(3072, ProfileRepository.sanitizeTexture(3072, isHeight = false))
        assertNull("宽度超 3072 丢弃", ProfileRepository.sanitizeTexture(3073, isHeight = false))
        assertNull("宽度 999999 丢弃", ProfileRepository.sanitizeTexture(999999, isHeight = false))
        assertEquals(1760, ProfileRepository.sanitizeTexture(1760, isHeight = true))
        assertEquals(3208, ProfileRepository.sanitizeTexture(3208, isHeight = true))
        assertNull("高度超 3208 丢弃", ProfileRepository.sanitizeTexture(3209, isHeight = true))
        assertNull("非正值丢弃", ProfileRepository.sanitizeTexture(0, isHeight = false))
        assertNull("默认 -1 丢弃", ProfileRepository.sanitizeTexture(-1, isHeight = true))
    }

    // ---- sanitizeLevel（BUG-040：0..4） ----

    @Test
    fun levelDomain() {
        assertEquals(0, ProfileRepository.sanitizeLevel(0))
        assertEquals(4, ProfileRepository.sanitizeLevel(4))
        assertNull("超上限丢弃", ProfileRepository.sanitizeLevel(5))
        assertNull("99 丢弃", ProfileRepository.sanitizeLevel(99))
        assertNull("负值丢弃", ProfileRepository.sanitizeLevel(-1))
    }
}
