package com.example.questtuner.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-021/022：下载 host 白名单 + tag 净化。 */
class UpdateSafetyTest {

    // ---- checkHost（BUG-021） ----

    @Test
    fun allowedHosts() {
        assertTrue(SelfUpdater.checkHost("https://github.com/donguaizs/QuestTuner/releases/download/v0.3.0/a.apk"))
        assertTrue(SelfUpdater.checkHost("https://objects.githubusercontent.com/x"))
        assertTrue(SelfUpdater.checkHost("https://release-assets.githubusercontent.com/x"))
    }

    @Test
    fun disallowedHosts() {
        assertFalse(SelfUpdater.checkHost("http://evil.com/a.apk"))
        assertFalse(SelfUpdater.checkHost("https://evil.example.com/a.apk"))
        // 子域伪装：host 精确匹配，前缀拼接不放行
        assertFalse(SelfUpdater.checkHost("https://github.com.evil.io/a.apk"))
        assertFalse(SelfUpdater.checkHost("not a url"))
        assertFalse(SelfUpdater.checkHost(""))
        // scheme 校验（round3b）：白名单 host 但明文/非 http 协议也拒绝
        assertFalse(SelfUpdater.checkHost("http://github.com/donguaizs/QuestTuner/releases/download/v0.3.0/a.apk"))
        assertFalse(SelfUpdater.checkHost("ftp://github.com/x.apk"))
    }

    // ---- tag 净化（BUG-022） ----

    @Test
    fun safeTags() {
        for (tag in listOf("v0.3.0", "0.3.0", "0.3.0-beta.1", "v1.0.0-rc.2", "V2.10.3")) {
            assertTrue("tag 应合法: $tag", SelfUpdater.isTagSafe(tag))
        }
    }

    @Test
    fun pathTraversalTags_rejected() {
        for (tag in listOf("../../x", "a/../../b", "a/b", "..", "a\\b", "a b", "a;b", "")) {
            assertFalse("tag 应被拒绝: $tag", SelfUpdater.isTagSafe(tag))
        }
    }

    // ---- selectApkAsset（BUG-049） ----

    @Test
    fun selectApkAsset_prefersRelease() {
        // v0.2.2 实测顺序：debug 在前（20MB）→ 必须选中 release
        assertEquals("QuestTuner-v0.2.2-release.apk", SelfUpdater.selectApkAsset(
            listOf("QuestTuner-v0.2.2-debug.apk", "QuestTuner-v0.2.2-release.apk")))
    }

    @Test
    fun selectApkAsset_fallsBackToAnyApk() {
        assertEquals("QuestTuner-v0.3.0.apk", SelfUpdater.selectApkAsset(
            listOf("QuestTuner-v0.3.0.apk", "QuestTuner-v0.3.0-sources.jar")))
        assertEquals("a.apk", SelfUpdater.selectApkAsset(listOf("README.md", "a.apk")))
    }

    @Test
    fun selectApkAsset_skipsSourcesAndMapping_andNull() {
        assertEquals("QuestTuner-v0.3.0-release.apk", SelfUpdater.selectApkAsset(
            listOf("x-mapping.txt", "QuestTuner-v0.3.0-release.apk", "y-sources.jar")))
        assertNull(SelfUpdater.selectApkAsset(listOf("x-sources.jar", "y-mapping.txt", "notes.md")))
        assertNull(SelfUpdater.selectApkAsset(emptyList()))
    }
}
