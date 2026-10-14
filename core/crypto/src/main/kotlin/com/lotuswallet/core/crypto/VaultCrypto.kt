package com.lotuswallet.core.crypto

import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException

/**
 * 保险库密钥体系 —— serverless 改造的核心。
 *
 * ## 为什么要有这一层
 * 旧版把「加密数据用的密钥」派生自服务端下发的 `token` + 用户邮箱。
 * 一旦后端下线，所有本地密文永久无法解密。要彻底去掉服务端，密钥必须完全由本地产生。
 *
 * ## 采用的模型（信封加密 / Envelope Encryption）
 * ```
 *   用户主密码 ──Argon2id(随机盐)──▶ KEK（密钥加密密钥，不落盘）
 *                                      │  AES-256-GCM 包裹
 *                                      ▼
 *   随机生成 32 字节 ──────────────▶ DEK（数据加密密钥）──▶ 加密所有条目
 * ```
 * 好处：
 * - 数据只被 DEK 加密，**改密码只需重新包裹 DEK**，无需重新加密全部数据；
 * - DEK 可以额外用 Android Keystore + 生物识别再包一层，实现「免输密码解锁」；
 * - KEK 从不落盘，拿到数据库文件但没有主密码也无法解密。
 */
object VaultCrypto {

    /** 落盘的密钥信封。除了这些字段之外，设备上不保存任何可用于解密的信息。 */
    data class Envelope(
        val kdfParams: String,
        val wrappedDek: String,
        val version: Int = CURRENT_VERSION,
    ) {
        companion object {
            const val CURRENT_VERSION = 1
        }
    }

    /** 创建全新保险库：生成随机 DEK 并用主密码包裹。 */
    fun create(password: CharArray, params: KeyDerivation.Params = KeyDerivation.Params()): Pair<Envelope, ByteArray> {
        val dek = SecureRandom.bytes(KeyDerivation.KEY_LENGTH)
        val effectiveParams = params.withFreshSalt()
        val kek = KeyDerivation.deriveKey(password, effectiveParams)
        val wrapped = Aead.encrypt(kek, dek, associatedData(effectiveParams))
        return Envelope(
            effectiveParams.encode(),
            com.lotuswallet.core.common.Bytes.toHex(wrapped),
        ) to dek
    }

    /** 用主密码解锁，返回 DEK。密码错误时抛出 [AppError.IntegrityCheckFailed]。 */
    fun unlock(password: CharArray, envelope: Envelope): ByteArray {
        val params = KeyDerivation.Params.decode(envelope.kdfParams)
            ?: throw AppException(AppError.Local(IllegalStateException("密钥信封损坏：无法解析 KDF 参数")))
        val kek = KeyDerivation.deriveKey(password, params)
        return try {
            Aead.decrypt(kek, com.lotuswallet.core.common.Bytes.fromHex(envelope.wrappedDek), associatedData(params))
        } catch (e: AppException) {
            // 对用户统一表述为「密码错误」：GCM 无法区分密钥错误与密文被篡改
            throw AppException(AppError.WrongPassword)
        }
    }

    /** 校验主密码是否正确。 */
    fun verify(password: CharArray, envelope: Envelope): Boolean = try {
        unlock(password, envelope)
        true
    } catch (_: AppException) {
        false
    }

    /**
     * 修改主密码：只重新包裹 DEK，数据密文保持不变。
     *
     * @param newDek 可选。若提供（例如从生物识别路径拿到），则用其替换当前 DEK；
     *               为 null 时沿用旧密码解出的 DEK。
     */
    fun changePassword(
        oldPassword: CharArray,
        newPassword: CharArray,
        envelope: Envelope,
        newParams: KeyDerivation.Params = KeyDerivation.Params(),
        newDek: ByteArray? = null,
    ): Envelope {
        val dek = newDek ?: unlock(oldPassword, envelope)
        val effectiveParams = newParams.withFreshSalt()
        val kek = KeyDerivation.deriveKey(newPassword, effectiveParams)
        val wrapped = Aead.encrypt(kek, dek, associatedData(effectiveParams))
        return Envelope(effectiveParams.encode(), com.lotuswallet.core.common.Bytes.toHex(wrapped))
    }

    /**
     * 用外部密钥（如 Android Keystore 中的硬件密钥）包裹 DEK，
     * 用于「生物识别免密解锁」路径。该密钥由 Keystore 提供，无法导出。
     */
    fun wrapWithExternalKey(dek: ByteArray, wrappingKey: ByteArray): String =
        com.lotuswallet.core.common.Bytes.toHex(
            Aead.encrypt(wrappingKey, dek, EXTERNAL_AAD),
        )

    fun unwrapWithExternalKey(wrapped: String, wrappingKey: ByteArray): ByteArray =
        Aead.decrypt(wrappingKey, com.lotuswallet.core.common.Bytes.fromHex(wrapped), EXTERNAL_AAD)

    /** 旧版数据迁移辅助：把旧版（服务端派生）密钥加密的数据重新加密到新密钥下。 */
    fun reencrypt(legacyKey: ByteArray, newKey: ByteArray, legacyPayload: ByteArray): ByteArray =
        Aead.encrypt(newKey, Aead.decrypt(legacyKey, legacyPayload))

    private val EXTERNAL_AAD = "lotuswallet.external.v1".toByteArray(Charsets.US_ASCII)

    private fun associatedData(params: KeyDerivation.Params): ByteArray =
        params.encode().toByteArray(Charsets.US_ASCII)
}
