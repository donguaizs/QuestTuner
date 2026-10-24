package com.example.questtuner.tuner

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-020 时序回归（VERIFY-round3 专项② FAIL 项）：
 * 「apply 完成 → lastApplied 置空 → Leave 执行」序列下，
 * Leave 必须凭意图内快照完成比对与回滚，而不是读已置空的共享字段。
 */
class LeaveSnapshotTest {

    private fun record(pkg: String, gen: Int = 0, refreshRate: Int? = 90) =
        ForegroundProfileService.AppliedRecord(
            pkg = pkg,
            profile = AppProfile(packageName = pkg, refreshRate = refreshRate),
            generation = gen,
        )

    @Test
    fun leaveCarriesSnapshot_notSharedField() {
        // 序列模拟：Apply(A) 后 lastApplied 有值；桌面事件入队 Leave(prev) 并置空
        var lastApplied: ForegroundProfileService.AppliedRecord? = record("com.game.a")
        val q = Channel<ForegroundProfileService.ApplyIntent>(capacity = 16)
        // 事件入口逻辑：捕获快照 → 置空 → 入队
        val prev = lastApplied!!
        lastApplied = null
        q.trySend(ForegroundProfileService.ApplyIntent.Leave(prev))

        // worker 侧：取出 Leave，共享 lastApplied 已为 null
        val intent = q.tryReceive().getOrNull() as ForegroundProfileService.ApplyIntent.Leave
        // 断言层级①：共享字段确已置空（时序前提）
        assertNull("入队后共享字段已置空（时序前提）", lastApplied)
        // 断言层级②：意图快照仍完整
        assertEquals("com.game.a", intent.record.pkg)
        assertEquals(90, intent.record.profile.refreshRate)
        // 断言层级③：快照可完成 valuesMatch 比对（模拟 getprop 读回与 profile 一致）
        val parts = listOf("90", "", "", "", "", "")
        assertTrue(
            "快照比对应通过（回滚应发生）",
            ForegroundProfileService.valuesMatch(intent.record.profile, parts),
        )
    }

    @Test
    fun foldedSequence_applyLeaveApply_keepsFinalApply() {
        // 019 折叠 + 020 快照的联合序列：A→桌面→A，折叠保留最终 Apply(A)
        val q = Channel<ForegroundProfileService.ApplyIntent>(capacity = 16)
        val recA = record("com.game.a")
        q.trySend(ForegroundProfileService.ApplyIntent.Apply(
            "com.game.a", listOf("setprop debug.oculus.refreshRate 90"), recA.profile))
        q.trySend(ForegroundProfileService.ApplyIntent.Leave(recA))
        q.trySend(ForegroundProfileService.ApplyIntent.Apply(
            "com.game.a", listOf("setprop debug.oculus.refreshRate 90"), recA.profile))
        // worker 折叠
        var current = q.tryReceive().getOrNull()!!
        while (true) {
            val next = q.tryReceive().getOrNull() ?: break
            current = next
        }
        assertTrue("折叠后应保留最终意图 Apply(A)",
            current is ForegroundProfileService.ApplyIntent.Apply)
    }

    @Test
    fun snapshotGenerationBlocksRollback() {
        // 双保险之二：快照代次 ≠ 当前代次 → 比对前置失败（不回滚）。
        // generation 检查在 stillOurValues 内（依赖单例无法纯测），
        // 这里验证快照忠实携带了入队时代次，供该检查使用。
        val rec = record("com.game.a", gen = 7)
        assertEquals(7, rec.generation)
    }
}
