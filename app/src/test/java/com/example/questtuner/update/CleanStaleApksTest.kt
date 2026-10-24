package com.example.questtuner.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R4-O1：update/ 目录陈旧 APK 清理（纯文件操作，临时目录验证）。 */
class CleanStaleApksTest {

    @Test
    fun keepsOnlyCurrentTag() {
        val dir = File(System.getProperty("java.io.tmpdir"), "qt-clean-test-${System.currentTimeMillis()}")
        dir.mkdirs()
        try {
            val keep = File(dir, "questtuner-v0.3.0.apk")
            val stale1 = File(dir, "questtuner-v0.2.0.apk")
            val stale2 = File(dir, "questtuner-v0.1.0-beta.1.apk")
            listOf(keep, stale1, stale2).forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }

            SelfUpdater.cleanStaleApks(TestContext(dir), "v0.3.0")

            assertTrue("当前 tag 保留", keep.exists())
            assertFalse("旧版本清除", stale1.exists())
            assertFalse("旧 beta 清除", stale2.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyOrMissingDir_noError() {
        // 目录不存在 → runCatching 吞掉，不抛
        SelfUpdater.cleanStaleApks(TestContext(File("/nonexistent/qt-dir")), "v0.3.0")
    }

    /** 最小 Context stub：仅 getExternalFilesDir 返回测试目录 */
    private class TestContext(private val dir: File) : android.content.Context() {
        override fun getExternalFilesDir(type: String?): File? = dir
    }
}
