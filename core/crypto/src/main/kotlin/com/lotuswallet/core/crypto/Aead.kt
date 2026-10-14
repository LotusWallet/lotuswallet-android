package com.lotuswallet.core.crypto

import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.Bytes
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 认证加密（AEAD）：AES-256-GCM。
 *
 * 这是替换旧版 [AESUtil] 的核心改动。旧版存在三处严重缺陷，全部在此修正：
 * 1. **IV 等于密钥** —— 密钥复用为 IV，且同一密钥下 IV 恒定，GCM/CBC 下都是灾难性错误；
 *    这里每次加密都从 [SecureRandom] 取 12 字节随机 nonce。
 * 2. **无完整性保护** —— CBC 不做认证，密文可被篡改。GCM 自带 128 bit 认证标签。
 * 3. **密钥直接取字符串字节** —— 长度不受控。这里强制 32 字节密钥。
 *
 * 密文格式（自描述，便于未来平滑升级参数）：
 * ```
 * [1 字节版本][12 字节 nonce][n 字节密文 + 16 字节 tag]
 * ```
 */
object Aead {

    private const val VERSION_V1: Byte = 0x01
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    const val KEY_LENGTH = 32

    /**
     * 加密。
     *
     * @param key 32 字节密钥
     * @param plaintext 明文
     * @param associatedData 附加认证数据（不加密但参与完整性校验，例如条目标识）
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_LENGTH) { "AES-256-GCM 密钥必须为 $KEY_LENGTH 字节，实际 ${key.size}" }
        val nonce = SecureRandom.bytes(NONCE_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LENGTH_BITS, nonce),
        )
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return Bytes.concat(byteArrayOf(VERSION_V1), nonce, ciphertext)
    }

    /**
     * 解密，并校验完整性。
     *
     * @throws com.lotuswallet.core.common.AppException 当密文被篡改或密钥错误时抛出
     *         [AppError.IntegrityCheckFailed]（统一为「完整性校验失败」，不区分「密钥错」与「被篡改」，
     *         避免向攻击者泄露信息）。
     */
    fun decrypt(key: ByteArray, payload: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_LENGTH) { "AES-256-GCM 密钥必须为 $KEY_LENGTH 字节，实际 ${key.size}" }
        require(payload.size > 1 + NONCE_LENGTH) { "密文长度非法" }

        val version = payload[0]
        require(version == VERSION_V1) { "不支持的密文版本: $version" }

        val nonce = payload.copyOfRange(1, 1 + NONCE_LENGTH)
        val ciphertext = payload.copyOfRange(1 + NONCE_LENGTH, payload.size)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, nonce),
            )
            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw com.lotuswallet.core.common.AppException(AppError.IntegrityCheckFailed)
        }
    }

    /** 便捷方法：加密 UTF-8 文本。 */
    fun encryptString(key: ByteArray, plaintext: String, associatedData: ByteArray = ByteArray(0)): String =
        Bytes.toHex(encrypt(key, plaintext.toByteArray(Charsets.UTF_8), associatedData))

    /** 便捷方法：解密为 UTF-8 文本。 */
    fun decryptString(key: ByteArray, payloadHex: String, associatedData: ByteArray = ByteArray(0)): String =
        String(decrypt(key, Bytes.fromHex(payloadHex), associatedData), Charsets.UTF_8)
}
