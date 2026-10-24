package com.example.questtuner.tuner

import android.content.Context
import com.example.questtuner.adb.AdbConnection
import com.example.questtuner.adb.AdbKeyStore
import kotlinx.coroutines.Dispatchers
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
            withTimeout(35_000L) {
                conn.connect(keyStore).getOrThrow()
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
        val r = conn.shell(cmd)
        if (r.isSuccess) r.getOrNull() else {
            lastError = r.exceptionOrNull()?.message
            null
        }
    }

    suspend fun runCommands(cmds: List<String>): Boolean = withContext(Dispatchers.IO) {
        cmds.all { runCommand(it) != null }
    }

    fun disconnect() {
        connection?.close()
        connection = null
    }
}
