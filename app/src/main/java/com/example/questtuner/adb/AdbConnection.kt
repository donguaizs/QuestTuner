package com.example.questtuner.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/**
 * One TCP connection to adbd (127.0.0.1:5555 from inside the headset).
 * CNXN/AUTH handshake + single shell stream.
 */
class AdbConnection(
    private val host: String,
    private val port: Int = 5555,
    private val connectTimeoutMs: Int = 5000,
    private val readTimeoutMs: Int = 30_000,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val shellMutex = ReentrantLock()
    private val nextLocalId = AtomicInteger(1)

    /** CNXN handshake; returns device info string on success. */
    fun connect(keyStore: AdbKeyStore): Result<String> {
        // BUG-036：同实例二次 connect 防御——旧连接未关闭即重连是调用方错误，快速失败优于静默泄漏
        check(socket == null) { "connect called on already-connected instance" }
        val info: String = runCatching {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
            // 读超时：adbd 等待用户在头显上确认授权期间不回包，无 soTimeout 会永久阻塞
            sock.soTimeout = readTimeoutMs
            socket = sock
            input = DataInputStream(sock.getInputStream().buffered())
            output = DataOutputStream(sock.getOutputStream().buffered())
            sendPacket(AdbProtocol.CMD_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, CNXN_PAYLOAD)
            var tokenRounds = 0
            var rounds = 0
            var deviceInfo: String? = null
            while (deviceInfo == null) {
                if (++rounds > 8) throw IOException("auth rounds exceeded")
                val p = readPacket()
                when (p.cmd) {
                    AdbProtocol.CMD_CNXN -> deviceInfo = String(p.payload, Charsets.UTF_8)
                    AdbProtocol.CMD_AUTH -> when (p.arg1) {
                        AdbProtocol.AUTH_TOKEN -> {
                            // R5-O4：tokenRounds≥3 时 adbd 仍在发 TOKEN = 未接受签名、
                            // 等待用户在头显确认 pubkey 授权——每次重发同一 pubkey（幂等），
                            // rounds 总闸 8 防无限轮换
                            tokenRounds++
                            when (tokenRounds) {
                                1 -> sendPacket(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_SIGNATURE, 0,
                                    keyStore.sign(p.payload, "SHA256withRSA"))
                                2 -> sendPacket(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_SIGNATURE, 0,
                                    keyStore.sign(p.payload, "SHA1withRSA"))
                                else -> sendPacket(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0,
                                    keyStore.publicKeyPayload())
                            }
                            Unit
                        }
                        else -> throw IOException("unexpected AUTH subtype ${p.arg1}")
                    }
                    else -> throw IOException("unexpected packet ${AdbProtocol.cmdName(p.cmd)}")
                }
            }
            deviceInfo
        }.getOrElse { e ->
            // BUG-036：失败自清理——socket/流字段残留是泄漏面，类自身契约「失败也自己收拾」
            close()
            throw e
        }
        return Result.success(info)
    }

    /** Blocking shell; collect output until CLSE. Call from background thread. */
    fun shell(command: String): Result<String> = runCatching {
        // 字节累积 + 一次性 UTF-8 解码，避免多字节字符跨 WRTE 包截断
        val bytes = java.io.ByteArrayOutputStream()
        // BUG-035：累计上限（单包 1MB 检查不覆盖总量）——异常大输出按 IOException 失败，
        // 走既有失败链（interpretShellResult 对 IOException 清连接；不为超限开特例：
        // 残留未读数据会破坏后续流解析，关连接是唯一干净处理），防 OOM
        var total = 0L
        shellStreaming(command) { chunk ->
            total += chunk.size
            if (total > MAX_SHELL_OUTPUT) throw IOException("shell output too large (>8MB): $command")
            bytes.write(chunk)
        }
        bytes.toString("UTF-8")
    }

    fun shellStreaming(command: String, onChunk: (ByteArray) -> Unit) {
        // 串行化整个 shell 会话：单连接复用，并发 OPEN 会流 ID 错配、输出串包
        shellMutex.withLock {
            val localId = nextLocalId.getAndIncrement()
            sendPacket(AdbProtocol.CMD_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
            val first = readPacket()
            if (first.cmd != AdbProtocol.CMD_OKAY) {
                throw IOException("OPEN not acked: ${AdbProtocol.cmdName(first.cmd)}")
            }
            while (true) {
                val p = readPacket()
                when (p.cmd) {
                    AdbProtocol.CMD_WRTE -> {
                        if (p.payload.isNotEmpty()) onChunk(p.payload)
                        sendPacket(AdbProtocol.CMD_OKAY, localId, p.arg0)
                    }
                    AdbProtocol.CMD_CLSE -> return@withLock
                    else -> { /* ignore */ }
                }
            }
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
        output!!.write(AdbProtocol.encode(cmd, arg0, arg1, payload))
        output!!.flush()
    }

    private fun readPacket(): AdbProtocol.Packet {
        val header = ByteArray(24)
        input!!.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        val crc = bb.int
        val magic = bb.int
        if (len < 0 || len > (1 shl 20)) throw IOException("bad payload length $len")
        val payload = ByteArray(len)
        input!!.readFully(payload)
        if (AdbProtocol.crc32(payload) != crc) throw IOException("CRC mismatch")
        if (magic != cmd.xor(-1)) throw IOException("magic mismatch")
        return AdbProtocol.Packet(cmd, arg0, arg1, payload)
    }

    companion object {
        private val CNXN_PAYLOAD = "host::\u0000".toByteArray(Charsets.UTF_8)

        /** BUG-035：shell 输出累计上限——常规输出 <10KB，dumpsys 全量数 MB 仍可用，防异常大输出 OOM */
        private const val MAX_SHELL_OUTPUT = 8L * 1024 * 1024
    }
}
