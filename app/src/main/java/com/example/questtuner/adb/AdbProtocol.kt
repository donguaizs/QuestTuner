package com.example.questtuner.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB wire protocol packet codec (AOSP system/core/adb/protocol.txt).
 * Header is 24 bytes, little-endian:
 *   command(4) arg0(4) arg1(4) data_length(4) data_crc(4) magic(4)
 * magic = command ^ 0xFFFFFFFF
 */
object AdbProtocol {
    const val A_VERSION = 0x01000000
    const val MAX_PAYLOAD = 4096

    const val CMD_CNXN = 0x434e584e
    const val CMD_AUTH = 0x41555448
    const val CMD_OPEN = 0x4f50454e
    const val CMD_OKAY = 0x4f4b4159
    const val CMD_WRTE = 0x57525445
    const val CMD_CLSE = 0x434c5345

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    data class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray, val crc: Int = 0)

    fun encode(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(crc32(payload))
        buf.putInt(cmd.xor(-1))
        buf.put(payload)
        return buf.array()
    }

    fun decodeHeader(header: ByteArray): Packet {
        require(header.size == 24) { "header must be 24 bytes" }
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val a0 = bb.int
        val a1 = bb.int
        val len = bb.int
        val crc = bb.int
        val magic = bb.int
        require(len in 0..(1 shl 20)) { "bad payload length $len" }
        require(magic == cmd.xor(-1)) { "magic mismatch" }
        return Packet(cmd, a0, a1, ByteArray(len).also { /* crc filled by caller */ }, crc)
    }

    fun verifyCrc(p: Packet, payload: ByteArray = p.payload): Boolean = crc32(payload) == p.crc

    fun decodeFull(header: ByteArray, payload: ByteArray): Packet {
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val a0 = bb.int
        val a1 = bb.int
        val len = bb.int
        val crc = bb.int
        val magic = bb.int
        require(len == payload.size) { "length mismatch header=$len actual=${payload.size}" }
        require(magic == cmd.xor(-1)) { "magic mismatch" }
        require(crc32(payload) == crc) { "CRC mismatch" }
        return Packet(cmd, a0, a1, payload)
    }

    fun cmdName(cmd: Int): String = when (cmd) {
        CMD_CNXN -> "CNXN"
        CMD_AUTH -> "AUTH"
        CMD_OPEN -> "OPEN"
        CMD_OKAY -> "OKAY"
        CMD_WRTE -> "WRTE"
        CMD_CLSE -> "CLSE"
        else -> "0x%08x".format(cmd)
    }

    fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc xor -1
    }
}
