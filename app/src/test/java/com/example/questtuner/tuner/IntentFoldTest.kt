package com.example.questtuner.tuner

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BUG-019 意图折叠：worker 消费时 tryReceive 把积压的中间意图折叠成最后一个。
 * 用真实 Channel 验证折叠行为本身（ForegroundProfileService.startWorker 的核心机制）。
 */
class IntentFoldTest {

    @Test
    fun foldedKeepsOnlyLatest() {
        val q = Channel<String>(capacity = 16)
        // 模拟快速 A→B→桌面 三事件入队
        q.trySend("apply:A")
        q.trySend("apply:B")
        q.trySend("leave:B")

        // worker 侧折叠：取一个意图后，把队列剩余全部折叠
        val first = q.tryReceive().getOrNull()!!
        var current = first
        while (true) {
            val next = q.tryReceive().getOrNull() ?: break
            current = next
        }
        // 只应保留最新意图（桌面态 leave）
        assertEquals("leave:B", current)
    }

    @Test
    fun singleIntentUnfolded() {
        val q = Channel<String>(capacity = 16)
        q.trySend("apply:A")
        val current = q.tryReceive().getOrNull()!!
        assertEquals("apply:A", current)
        // 队列空：无折叠发生
        assertEquals(null, q.tryReceive().getOrNull())
    }

    @Test
    fun emptyQueueYieldsNull() {
        val q = Channel<String>(capacity = 16)
        assertEquals(null, q.tryReceive().getOrNull())
    }
}
