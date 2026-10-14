package com.lotuswallet.core.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 哈希原语集合。
 *
 * 全部为无状态纯函数，输出长度固定，便于在链实现中共用。
 */
object Hash {

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    /** SHA-256(SHA-256(x))，比特币的常见组合。 */
    fun doubleSha256(data: ByteArray): ByteArray = sha256(sha256(data))

    fun ripemd160(data: ByteArray): ByteArray {
        val digest = RIPEMD160Digest()
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /** Keccak-256（以太坊使用的变体，不是 NIST 的 SHA3-256）。 */
    fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /** RIPEMD160(SHA256(x))，比特币地址使用的 hash160。 */
    fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))

    fun sha256d(data: ByteArray): ByteArray = doubleSha256(data)

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray = hmac("HmacSHA512", key, data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = hmac("HmacSHA256", key, data)

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = hmac("HmacSHA1", key, data)

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    /** PBKDF2-HMAC-SHA512，用于 BIP39 助记词 → 种子。 */
    fun pbkdf2HmacSha512(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, iterations, keyLengthBytes * 8)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    /**
     * SHA-224。
     *
     * Android 的系统 Provider 并不保证提供 `SHA-224`，因此直接用 BouncyCastle 的实现，
     * 保证「真机」与「JVM 单元测试」跑的是同一段代码。
     */
    fun sha224(data: ByteArray): ByteArray {
        val digest = SHA224Digest()
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /**
     * BLAKE2b。
     *
     * 用于 Filecoin 地址的哈希与校验和，以及将来 Filecoin 的交易签名。
     *
     * @param digestBytes 输出长度（字节）。Filecoin 用 20（地址负载）与 4（校验和）。
     */
    fun blake2b(data: ByteArray, digestBytes: Int = 32): ByteArray {
        val digest = Blake2bDigest(digestBytes * 8)
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /** CRC-32（IEEE 802.3，与 zlib / Rust `crc32fast` 一致）。ICP 的 principal 校验和用它。 */
    fun crc32(data: ByteArray): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    /** HKDF-SHA256（RFC 5869），用于 BLS12-381 的 EIP-2333 密钥派生。 */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        lengthBytes: Int,
    ): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < lengthBytes) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(lengthBytes)
    }
}
