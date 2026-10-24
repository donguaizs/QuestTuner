package com.example.questtuner.adb

import android.content.Context
import android.os.Build
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Loads or creates the local adb RSA keypair, stored in app-private storage
 * as base64(PKCS8-DER) + our own pubkey text file.
 */
class AdbKeyStore(context: Context) {

    private val dir: File = context.filesDir
    private var publicKey: RSAPublicKey? = null
    private var privateKeyEncoded: ByteArray? = null

    @Synchronized
    fun loadOrGenerate() {
        if (publicKey != null) return
        val privFile = File(dir, "adbkey")
        val pubFile = File(dir, "adbkey.pub")
        val loaded = privFile.exists() && pubFile.exists() && runCatching {
            privateKeyEncoded = Base64.getDecoder().decode(privFile.readText().trim())   // 可抛（非法 base64）
            publicKey = parsePublicKeyFile(pubFile.readText())                            // 可抛（损坏/旧格式）
            // 旧格式检测：n0inv 与按新公式计算不一致 → 视为 BUG-001 修复前的旧编码，自愈重建
            check(wireN0Inv(pubFile.readText()) == expectedN0Inv(publicKey!!.modulus)) { "legacy pubkey format" }
            privateKeyEncoded != null && publicKey != null
        }.getOrDefault(false)
        if (!loaded) {
            // 自愈：加载失败/旧格式 → 删除旧文件，走生成分支重建
            privFile.delete()
            pubFile.delete()
            val pair = AdbKey.generate()
            privateKeyEncoded = pair.private.encoded
            publicKey = pair.public as RSAPublicKey
            privFile.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
            pubFile.writeText(AdbKey.encodePublic(pair.public as RSAPublicKey, deviceName()))
        }
        // 写一份 "name@host" 格式的公钥副本，便于用户拿到 PC adb 使用（可选）
    }

    /** 从 pub 文本解析出 wire 中的 n0inv 字段 */
    private fun wireN0Inv(text: String): Long {
        val bytes = Base64.getDecoder().decode(text.trim().split(" ")[0])
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.int; return bb.int.toLong() and 0xffffffffL
    }

    private fun expectedN0Inv(n: BigInteger): Long {
        val m = BigInteger.TWO.pow(32)
        return n.mod(m).modInverse(m).negate().mod(m).toLong()
    }

    fun sign(token: ByteArray, algorithm: String = "SHA256withRSA"): ByteArray {
        val priv = privateKeyEncoded ?: throw IllegalStateException("key not loaded")
        return AdbKey.sign(priv, token, algorithm)
    }

    /** A_AUTH RSAPUBLICKEY payload: base64 pubkey + " name" + NUL. */
    fun publicKeyPayload(): ByteArray {
        val pub = publicKey ?: throw IllegalStateException("key not loaded")
        return (AdbKey.encodePublic(pub, deviceName()) + "\u0000").toByteArray(Charsets.UTF_8)
    }

    fun publicKeyText(): String {
        val pub = publicKey ?: throw IllegalStateException("key not loaded")
        return AdbKey.encodePublic(pub, deviceName())
    }

    private fun deviceName(): String = (Build.MODEL ?: "QuestTuner").replace(' ', '_')

    private fun parsePublicKeyFile(text: String): RSAPublicKey {
        val b64 = text.trim().split(" ")[0]
        val bytes = Base64.getDecoder().decode(b64)
        require(bytes.size == 524) { "pubkey must be 524 bytes" }  // 先校验长度
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.int // word count（=64）
        bb.int // n0inv
        val m = ByteArray(256)
        bb.get(m)
        val rr = ByteArray(256)
        bb.get(rr) // R^2 mod N, not needed for JDK RSA
        val e = bb.int
        // m 是 LE 序，反转后构造 BigInteger（BE）
        val modulus = java.math.BigInteger(1, m.reversedArray())
        val exponent = java.math.BigInteger.valueOf(e.toLong() and 0xffffffffL)
        return KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
    }
}
