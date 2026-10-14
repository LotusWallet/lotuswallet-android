package com.lotuswallet.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.Bytes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android 硬件密钥库（Keystore）网关。
 *
 * 用途：为「生物识别 / 设备凭证免密解锁」提供一层**不可导出**的硬件密钥。
 * DEK 被该密钥包裹后落盘，解锁时必须通过 [android.hardware.biometrics.BiometricPrompt]
 * 传入对应的 [Cipher] 对象，系统才会放行解密 —— 即使用户的 App 数据目录被完整拷走，也无法解密。
 *
 * 与旧版差异：旧版指纹只用来做「登录开关」，密钥仍由服务端 token 派生，
 * 属于「假安全」。这里指纹/面容真正参与密钥解封。
 */
object AndroidKeyStoreGate {

    private const val PROVIDER = "AndroidKeyStore"
    private const val DEFAULT_ALIAS = "lotuswallet.dek.gate.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    /** Keystore 中是否已经存在该密钥。 */
    fun containsKey(alias: String = DEFAULT_ALIAS): Boolean = runCatching {
        keyStore().containsAlias(alias)
    }.getOrDefault(false)

    /**
     * 创建（或获取）用于包裹 DEK 的硬件密钥。
     *
     * @param requireUserAuth 是否要求用户每次认证。true 时解密必须配合 BiometricPrompt。
     */
    fun getOrCreateKey(
        alias: String = DEFAULT_ALIAS,
        requireUserAuth: Boolean = true,
        authValiditySeconds: Int = -1,
    ): SecretKey {
        val existing = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(requireUserAuth)

        if (requireUserAuth) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val authTypes = when {
                    authValiditySeconds > 0 -> KeyProperties.AUTH_DEVICE_CREDENTIAL
                    else -> KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                }
                builder.setUserAuthenticationParameters(
                    if (authValiditySeconds > 0) authValiditySeconds else 0,
                    authTypes,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(
                    if (authValiditySeconds > 0) authValiditySeconds else -1,
                )
            }
            builder.setInvalidatedByBiometricEnrollment(true)
        }

        generator.init(builder.build())
        return generator.generateKey()
    }

    /**
     * 提供用于加密的 [Cipher]（尚未 init 完成时由 BiometricPrompt 完成授权）。
     * 免密（`requireUserAuth=false`）场景可直接 [encrypt]。
     */
    fun encryptionCipher(alias: String = DEFAULT_ALIAS): Cipher {
        val key = getOrCreateKey(alias)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    /**
     * 提供用于解密的 [Cipher]。返回的 Cipher 必须是「已 init 但未授权」状态，
     * 交给 BiometricPrompt 的 `CryptoObject` 完成授权后再调用 [finishDecrypt]。
     */
    fun decryptionCipher(alias: String, iv: ByteArray): Cipher {
        val key = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()
            ?: throw AppException(AppError.Unsupported("硬件密钥不存在，可能因生物识别变更被系统失效"))
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    /** 用已授权的 Cipher 完成加密，输出 `iv || ciphertext`。 */
    fun finishEncrypt(cipher: Cipher, plaintext: ByteArray): String {
        val ciphertext = cipher.doFinal(plaintext)
        return Bytes.toHex(Bytes.concat(cipher.iv, ciphertext))
    }

    /** 用已授权的 Cipher 完成解密，输入为 `iv || ciphertext`。 */
    fun finishDecrypt(cipher: Cipher, payloadHex: String): ByteArray = try {
        cipher.doFinal(Bytes.fromHex(payloadHex).copyOfRange(GCM_IV_BYTES, Bytes.fromHex(payloadHex).size))
    } catch (_: Exception) {
        throw AppException(AppError.IntegrityCheckFailed)
    }

    /** 免密场景下的便捷加密（要求密钥创建时 `requireUserAuth=false`）。 */
    fun encrypt(plaintext: ByteArray, alias: String = DEFAULT_ALIAS): String =
        finishEncrypt(encryptionCipher(alias), plaintext)

    /** 免密场景下的便捷解密。 */
    fun decrypt(payloadHex: String, alias: String = DEFAULT_ALIAS): ByteArray {
        val payload = Bytes.fromHex(payloadHex)
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        return finishDecrypt(decryptionCipher(alias, iv), payloadHex)
    }

    /** 删除硬件密钥（会同时让所有被它包裹的数据不可解）。 */
    fun deleteKey(alias: String = DEFAULT_ALIAS) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
}
