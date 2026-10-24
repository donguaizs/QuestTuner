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
        assertTrue(SelfUpdater.versionGreaterThan("0.3.0", "0.3.beta"))
        assertFalse(SelfUpdater.versionGreaterThan("0.3.x", "0.3.0"))
    }

    @Test
    fun vPrefixIsHandledByCaller() {
        // 调用方负责 removePrefix("v")；此处验证纯比较逻辑不受影响
        assertTrue(SelfUpdater.versionGreaterThan("1.0.0", "0.9.0"))
    }
}
