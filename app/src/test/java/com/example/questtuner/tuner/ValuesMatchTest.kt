package com.example.questtuner.tuner

import com.example.questtuner.tuner.ForegroundProfileService.Companion.valuesMatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-020 valuesMatch：profile 期望值与 statusCommand 输出分段的比对表。 */
class ValuesMatchTest {

    private fun parts(vararg v: String): List<String> = v.toList()

    @Test
    fun allMatch() {
        val p = AppProfile(
            packageName = "com.a", refreshRate = 90,
            textureWidth = 1680, textureHeight = 1760, cpuLevel = 4, gpuLevel = 3,
        )
        assertTrue(valuesMatch(p, parts("90", "1680", "1760", "4", "3", "")))
    }

    @Test
    fun partialProfile_onlySetFieldsCompared() {
        // profile 只设 refreshRate：其余分段不比对（未设置的项不参与判断）
        val p = AppProfile(packageName = "com.a", refreshRate = 120)
        assertTrue(valuesMatch(p, parts("120", "999", "999", "9", "9", "1")))
    }

    @Test
    fun mismatchedValue_isNotOurs() {
        val p = AppProfile(packageName = "com.a", refreshRate = 90)
        assertFalse(valuesMatch(p, parts("120", "", "", "", "", "")))
    }

    @Test
    fun insufficientSegments_isNotOurs() {
        val p = AppProfile(packageName = "com.a", refreshRate = 90)
        assertFalse(valuesMatch(p, parts("90", "", "")))
    }

    @Test
    fun emptyValues_whenProfileEmpty_isOurs() {
        // profile 无参数（理论场景）：全空分段视为仍属我们
        val p = AppProfile(packageName = "com.a")
        assertTrue(valuesMatch(p, parts("", "", "", "", "", "")))
    }
}
