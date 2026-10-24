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
        // 追加退出码探针：stdout 末尾出现 "EXIT:0" 才算命令成功
        val r = adbMutex.withLock { conn.shell("$cmd; echo EXIT:$?") }
        interpretShellResult(conn, r)
    }

    suspend fun runCommands(cmds: List<String>): Boolean = withContext(Dispatchers.IO) {
        adbMutex.withLock {
            val conn = connection ?: return@withLock false
            cmds.all { interpretShellResult(conn, conn.shell("$it; echo EXIT:$?")) != null }
        }
    }

    /** 解析 shell 结果：剥离 EXIT 探针、校验退出码；IOException 视为连接已死并清理。必须持有 adbMutex。 */
    private fun interpretShellResult(conn: AdbConnection, r: Result<String>): String? {
        if (r.isFailure) {
            val e = r.exceptionOrNull()
            lastError = e?.message
            if (e is java.io.IOException) {
                conn.close()
                if (connection === conn) connection = null
            }
            return null
        }
        val out = r.getOrNull() ?: return null
        // 取最后一次出现的 "EXIT:" 行
        val exitMark = out.lastIndexOf("EXIT:")
        if (exitMark < 0) {
            lastError = "no exit marker; output: ${out.take(200)}"
            return null
        }
        val code = out.substring(exitMark + 5).trim().toIntOrNull()
        if (code != 0) {
            lastError = "exit=$code: ${out.substring(0, exitMark).take(300)}"
            return null
        }
        return out.substring(0, exitMark).trimEnd('\n')
    }

    fun disconnect() {
        connection?.close()
        connection = null
    }
}
