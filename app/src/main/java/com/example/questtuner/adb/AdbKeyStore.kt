package com.example.questtuner.adb

import android.content.Context
import android.os.Build
import java.io.File
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
        if (privFile.exists() && pubFile.exists()) {
            privateKeyEncoded = Base64.getDecoder().decode(privFile.readText().trim())
            publicKey = parsePublicKeyFile(pubFile.readText())
        } else {
            val pair = AdbKey.generate()
            privateKeyEncoded = pair.private.encoded
            publicKey = pair.public as RSAPublicKey
            privFile.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
            pubFile.writeText(AdbKey.encodePublic(pair.public as RSAPublicKey, deviceName()))
        }
        // 写一份 "name@host" 格式的公钥副本，便于用户拿到 PC adb 使用（可选）
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
