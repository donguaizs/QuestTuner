package com.example.questtuner.tuner

import android.content.Context
import com.example.questtuner.adb.AdbConnection
import com.example.questtuner.adb.AdbKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 调参执行器：管理 ADB 连接生命周期，串行执行调参命令。
 * 单例：整个应用共享一条 adbd 连接。
 */
object TuningExecutor {

    @Volatile private var connection: AdbConnection? = null
    @Volatile var lastError: String? = null
        private set

    /** UI 侧跨函数竞争防线（applyProfile 中 refreshStatus 等）；AdbConnection 内层锁是最终防线 */
    private val adbMutex = Mutex()

    suspend fun ensureConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (connection != null) return@withContext true
        val keyStore = AdbKeyStore(context)
        runCatching { keyStore.loadOrGenerate() }.onFailure {
            lastError = "密钥生成失败: ${it.message}"
            return@withContext false
        }
        val conn = AdbConnection("127.0.0.1", 5555)
        // 略大于 socket soTimeout（30s），双保险；取消/超时/失败路径都关闭 socket
        val result = runCatching {
            adbMutex.withLock {
                withTimeout(35_000L) {
                    conn.connect(keyStore).getOrThrow()
                }
            }
        }
        if (result.isSuccess) {
            connection = conn
            lastError = null
            true
        } else {
            lastError = result.exceptionOrNull()?.message ?: "connect failed"
            conn.close()
            false
        }
    }

    suspend fun runCommand(cmd: String): String? = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext null
        val r = adbMutex.withLock { conn.shell(cmd) }
        if (r.isSuccess) r.getOrNull() else {
            lastError = r.exceptionOrNull()?.message
            // shell 失败大概率是连接已死（IOException）——关闭并置空，允许重连
            if (r.exceptionOrNull() is java.io.IOException) {
                conn.close()
                connection = null
            }
            null
        }
    }

    suspend fun runCommands(cmds: List<String>): Boolean = withContext(Dispatchers.IO) {
        adbMutex.withLock {
            cmds.all { runCommandLocked(conn = connection ?: return@withLock false, cmd = it) != null }
        }
    }

    /** 必须在持有 adbMutex 时调用 */
    private fun runCommandLocked(conn: AdbConnection, cmd: String): String? {
        val r = conn.shell(cmd)
        if (r.isSuccess) return r.getOrNull()
        lastError = r.exceptionOrNull()?.message
        // 连接级失败（IOException）时清理死连接，允许重连
        if (r.exceptionOrNull() is java.io.IOException) {
            conn.close()
            if (connection === conn) connection = null
        }
        return null
    }

    fun disconnect() {
        connection?.close()
        connection = null
    }
}
