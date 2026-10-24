package com.example.questtuner.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SelfUpdater.versionGreaterThan：语义化版本比较（数字段逐位比较，非数字段按 0）。 */
class VersionCompareTest {

    @Test
    fun patchLevel() {
        assertTrue(SelfUpdater.versionGreaterThan("0.3.0", "0.2.9"))
        assertFalse(SelfUpdater.versionGreaterThan("0.2.9", "0.3.0"))
    }

    @Test
    fun equalVersions_isNotGreater() {
        assertFalse(SelfUpdater.versionGreaterThan("0.2.0", "0.2.0"))
    }

    @Test
    fun differentLengths() {
        assertTrue(SelfUpdater.versionGreaterThan("1.0", "0.9.9"))
        assertTrue(SelfUpdater.versionGreaterThan("0.2.1", "0.2"))
        assertFalse(SelfUpdater.versionGreaterThan("0.2", "0.2.1"))
    }

    @Test
    fun nonNumericSegments_treatedAsZero() {
        // "beta" 按非数字段作 0 处理：0.3.1 > 0.3.beta（1 > 0）
        assertTrue(SelfUpdater.versionGreaterThan("0.3.1", "0.3.beta"))
        // 0.3.x 与 0.3.0 相等（x 按 0），不视为更高
        assertFalse(SelfUpdater.versionGreaterThan("0.3.x", "0.3.0"))
    }

    @Test
    fun vPrefixIsHandledByCaller() {
        // 调用方负责 removePrefix("v")；此处验证纯比较逻辑不受影响
        assertTrue(SelfUpdater.versionGreaterThan("1.0.0", "0.9.0"))
    }
}
