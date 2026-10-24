package com.example.questtuner.tuner

import android.content.Context
import com.example.questtuner.adb.AdbConnection
import com.example.questtuner.adb.AdbKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * 最近一次连接失败是否为 TCP 拒绝（adbd 未监听）——BUG-026 重试分类用（A11）；
     * 其他失败（AUTH 超时/链路异常）不重试。分类失败（无法判断）按可重试处理，不劣化现状。
     */
    @Volatile private var lastFailureWasRefused: Boolean = false

    fun lastErrorIsRefused(): Boolean = lastFailureWasRefused

    /** 连接实态（服务侧重连/意外断开后 UI 同步用，BUG-027 / O10 根因） */
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    /**
     * ADB 会话语义（BUG-027）：
     * true  = 用户显式连接过且未显式断开 → autoApply 服务可以工作/自行恢复连接
     * false = 用户从未连接或已显式断开 → 服务 no-op（不重连、不重开无线调试）
     * 意外断开（runCommand 失败清理）不改此标志——服务可重连自愈。
     */
    @Volatile var adbSessionActive: Boolean = false
        private set

    fun markSessionActive() { adbSessionActive = true }
    fun markSessionInactive() { adbSessionActive = false }

    /** 手动应用代次（BUG-020）：UI 每次手动应用/恢复默认时递增；服务比对快照代次判断是否已被手动覆盖 */
    @Volatile var manualGeneration: Int = 0
        private set

    fun bumpManualGeneration() { manualGeneration++ }

    /** UI 侧跨函数竞争防线（applyProfile 中 refreshStatus 等）；AdbConnection 内层锁是最终防线 */
    private val adbMutex = Mutex()

    /**
     * 纯连接，不触碰无线调试开关——自动开启属 SecureAutomation.ensureWifiAdbThenConnect
     * （用户显式 connect 流程专用），服务/接收器侧自愈只可用本函数（BUG-027 会话语义）。
     */
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
            _connectionState.value = true
            lastError = null
            lastFailureWasRefused = false
            true
        } else {
            val e = result.exceptionOrNull()
            lastError = e?.message ?: "connect failed"
            // A11：拒连=adbd 未就绪（可重试）；超时/IOException=AUTH 等待或链路问题（不重试）
            lastFailureWasRefused = e is java.net.ConnectException
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
                if (connection === conn) {
                    connection = null
                    _connectionState.value = false
                }
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
        _connectionState.value = false
    }
}
