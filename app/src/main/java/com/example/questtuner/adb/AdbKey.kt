package com.example.questtuner.adb

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * ADB 公钥编码器 + RSA 密钥对管理。
 *
 * 实测 adb 34.x 的 ANDROID_pubkey 格式（524 字节，与老版 268 字节不同）：
 *   uint32 len      = 64 (LE, word 数)
 *   uint32 n0inv    = -1/n0 mod 2^32 (LE, n0 = 模数最低 32 位，即 n mod 2^32)
 *   byte[256] modulus  (2048-bit RSA)
 *   byte[256] rr      = R^2 mod N, R = 2^2048
 *   uint32 exponent = 65537 (LE)
 * 最后 base64，追加 " name@host" 注释。
 */
object AdbKey {

    fun generate(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    fun encodePublic(pub: RSAPublicKey, name: String): String {
        val n = pub.modulus
        val modulus = bigToBytes(n, 256)
        // LE 序的 256 字节 modulus（wire 格式）
        val modulusLe = modulus.reversedArray()
        // n0 = 模数最低 32 位（n mod 2^32，Montgomery 常数由最低 word 决定）
        val n0 = n.mod(BigInteger.TWO.pow(32))
        // n0inv = -1/n0 mod 2^32
        val n0inv = n0.modInverse(BigInteger.TWO.pow(32)).negate().mod(BigInteger.TWO.pow(32))
        // rr = R^2 mod N, R = 2^2048
        val rr = BigInteger.TWO.pow(2048).modPow(BigInteger.TWO, n)
        // rr 同样按 LE 写入 wire
        val rrBytes = bigToBytes(rr, 256).reversedArray()

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv.toInt())
        // modulus/rr 用 LE 序写入
        buf.put(modulusLe)
        buf.put(rrBytes)
        buf.putInt(pub.publicExponent.toInt())
        require(!buf.hasRemaining())
        return Base64.getEncoder().encodeToString(buf.array()) + " " + name
    }

    fun sign(privEncoded: ByteArray, data: ByteArray, algorithm: String = "SHA256withRSA"): ByteArray {
        val signature = Signature.getInstance(algorithm)
        signature.initSign(
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privEncoded))
        )
        signature.update(data)
        return signature.sign()
    }

    fun fingerprint(pub: RSAPublicKey): String {
        val md = MessageDigest.getInstance("MD5")
        // adb 的指纹格式：base64(pubkey-structure) 的 MD5，冒号分隔
        val struct = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        // 简化：直接对 modulus||exponent 做 MD5（仅展示用，不参与协议）
        md.update(bigToBytes(pub.modulus, 256))
        md.update(bigToBytes(pub.publicExponent, 4))
        return md.digest().joinToString(":") { "%02x".format(it) }
    }

    private fun bigToBytes(v: BigInteger, len: Int): ByteArray {
        val b = v.toByteArray()   // 可能带前导 0 或不足长度
        return when {
            b.size == len -> b
            b.size > len -> b.copyOfRange(b.size - len, b.size)
            else -> ByteArray(len).also { System.arraycopy(b, 0, it, len - b.size, b.size) }
        }
    }
}
