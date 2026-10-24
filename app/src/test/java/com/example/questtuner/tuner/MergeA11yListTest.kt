package com.example.questtuner.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SecureAutomation.mergeA11yList：追加式合并，绝不覆盖既有服务列表。 */
class MergeA11yListTest {

    @Test
    fun emptyCurrent_returnsOurs() {
        assertEquals("com.example.questtuner/.tuner.ForegroundProfileService",
            SecureAutomation.mergeA11yList("", "com.example.questtuner/.tuner.ForegroundProfileService"))
    }

    @Test
    fun blankCurrent_returnsOurs() {
        assertEquals("ours",
            SecureAutomation.mergeA11yList("   ", "ours"))
    }

    @Test
    fun existingServices_appendsOurs() {
        assertEquals("svc1:svc2:ours",
            SecureAutomation.mergeA11yList("svc1:svc2", "ours"))
    }

    @Test
    fun singleExistingService_appendsOurs() {
        assertEquals("svc1:ours",
            SecureAutomation.mergeA11yList("svc1", "ours"))
    }
}
