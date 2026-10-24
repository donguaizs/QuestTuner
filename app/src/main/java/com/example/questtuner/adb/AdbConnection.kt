package com.example.questtuner.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One TCP connection to adbd (127.0.0.1:5555 from inside the headset).
 * CNXN/AUTH handshake + single shell stream.
 */
class AdbConnection(
    private val host: String,
    private val port: Int = 5555,
    private val connectTimeoutMs: Int = 5000,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    /** CNXN handshake; returns device info string on success. */
    fun connect(keyStore: AdbKeyStore): Result<String> {
        val info: String = runCatching {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
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
        }.getOrThrow()
        return Result.success(info)
    }

    /** Blocking shell; collect output until CLSE. Call from background thread. */
    fun shell(command: String): Result<String> = runCatching {
        val sb = StringBuilder()
        shellStreaming(command) { chunk -> sb.append(String(chunk, Charsets.UTF_8)) }
        sb.toString()
    }

    fun shellStreaming(command: String, onChunk: (ByteArray) -> Unit) {
        val localId = 1
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
                AdbProtocol.CMD_CLSE -> return
                else -> { /* ignore */ }
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
    }
}
