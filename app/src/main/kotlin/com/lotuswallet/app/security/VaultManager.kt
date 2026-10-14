package com.lotuswallet.app.security

import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.common.DefaultDispatcher
import com.lotuswallet.core.common.IoDispatcher
import com.lotuswallet.core.crypto.AndroidKeyStoreGate
import com.lotuswallet.core.crypto.KeyDerivation
import com.lotuswallet.core.crypto.VaultCrypto
import com.lotuswallet.core.datastore.BiometricRecord
import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.datastore.VaultEnvelopeRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保险库生命周期管理：创建、解锁、改密码、生物识别包裹、锁定。
 *
 * 所有涉及 Argon2id 的操作都跑在 [DefaultDispatcher]（CPU 密集）上，
 * 避免 64 MiB 内存 + 3 轮迭代把主线程卡住。
 */
@Singleton
class VaultManager @Inject constructor(
    private val settings: SettingsRepository,
    private val session: SessionKeyStore,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 是否已经建立过保险库（存在密钥信封）。 */
    suspend fun isInitialized(): Boolean = settings.current().vaultInitialized

    /** 生物识别通道是否可用（已配置且硬件密钥仍在）。 */
    suspend fun biometricAvailable(): Boolean {
        val record = settings.biometricRecord() ?: return false
        val enabled = settings.current().biometricEnabled
        return enabled && AndroidKeyStoreGate.containsKey(record.keyAlias)
    }

    /**
     * 创建全新保险库。
     *
     * @param lowEnd 为低配设备选择更轻的 Argon2id 参数（32 MiB / 2 轮）。
     */
    suspend fun create(password: CharArray, lowEnd: Boolean = false) {
        val params = if (lowEnd) KeyDerivation.lowEndParams() else KeyDerivation.Params()
        val (envelope, dek) = withContext(cpu) { VaultCrypto.create(password, params) }
        withContext(io) {
            settings.saveVaultEnvelope(
                VaultEnvelopeRecord(envelope.kdfParams, envelope.wrappedDek, envelope.version),
            )
            settings.setOnboardingCompleted(true)
        }
        session.put(dek)
        dek.fill(0)
    }

    /** 用主密码解锁。返回 false 表示密码错误。 */
    suspend fun unlock(password: CharArray): Boolean {
        val record = settings.vaultEnvelope() ?: return false
        val dek = try {
            withContext(cpu) { VaultCrypto.unlock(password, record.toEnvelope()) }
        } catch (_: AppException) {
            return false
        }
        session.put(dek)
        dek.fill(0)
        return true
    }

    /**
     * 修改主密码。
     *
     * 只重新包裹 DEK —— 数据库里已有的密文一条都不用动，
     * 因此改密码是 O(1) 的，不会因为数据量大而中途失败留下半新半旧的状态。
     */
    suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
        val record = settings.vaultEnvelope() ?: return false
        val envelope = try {
            withContext(cpu) {
                VaultCrypto.changePassword(oldPassword, newPassword, record.toEnvelope())
            }
        } catch (_: AppException) {
            return false
        }
        withContext(io) {
            settings.saveVaultEnvelope(
                VaultEnvelopeRecord(envelope.kdfParams, envelope.wrappedDek, envelope.version),
            )
            // 旧密码包裹的 DEK 已失效，生物识别通道必须重建
            settings.clearBiometricRecord()
        }
        return true
    }

    // ------------------------------------------------------------ 生物识别

    /**
     * 把当前 DEK 用硬件密钥重新包裹后落盘。
     *
     * 必须在 [androidx.biometric.BiometricPrompt] 授权成功、拿到已授权的 Cipher 之后调用。
     */
    suspend fun saveBiometricWrapping(cipher: javax.crypto.Cipher) {
        val dek = session.requireDek()
        val alias = BIOMETRIC_ALIAS
        val payload = AndroidKeyStoreGate.finishEncrypt(cipher, dek)
        withContext(io) {
            settings.saveBiometricRecord(BiometricRecord(wrappedDek = payload, keyAlias = alias))
            settings.setBiometricEnabled(true)
        }
    }

    /** 取出已保存的硬件包裹记录，供 UI 构造待授权的 Cipher。 */
    suspend fun biometricRecord(): BiometricRecord? = settings.biometricRecord()

    /** 用一个已授权的解密 Cipher 解出 DEK 并进入解锁状态。 */
    suspend fun unlockWithBiometric(cipher: javax.crypto.Cipher, payloadHex: String) {
        val dek = withContext(cpu) { AndroidKeyStoreGate.finishDecrypt(cipher, payloadHex) }
        if (dek.size != KeyDerivation.KEY_LENGTH) {
            throw AppException(AppError.IntegrityCheckFailed)
        }
        session.put(dek)
        dek.fill(0)
    }

    suspend fun disableBiometric() {
        withContext(io) {
            settings.clearBiometricRecord()
            settings.setBiometricEnabled(false)
        }
    }

    // ------------------------------------------------------------ 备份 / 锁定

    /** 供备份使用：读取当前密钥信封（密文）。 */
    suspend fun envelopeRecord(): VaultEnvelopeRecord? = settings.vaultEnvelope()

    /** 从备份恢复后写回密钥信封，并用主密码验证可通过。 */
    suspend fun restoreEnvelope(record: VaultEnvelopeRecord, password: CharArray): Boolean {
        val dek = try {
            withContext(cpu) { VaultCrypto.unlock(password, record.toEnvelope()) }
        } catch (_: AppException) {
            return false
        }
        withContext(io) {
            // 落盘信封的同时会把「已初始化」标记置位
            settings.saveVaultEnvelope(record)
        }
        session.put(dek)
        dek.fill(0)
        return true
    }

    fun lock() = session.lock()

    companion object {
        /** 与 [AndroidKeyStoreGate] 的默认别名保持一致。 */
        const val BIOMETRIC_ALIAS = "lotuswallet.dek.gate.v1"
    }
}

internal fun VaultEnvelopeRecord.toEnvelope(): VaultCrypto.Envelope =
    VaultCrypto.Envelope(kdfParams = kdfParams, wrappedDek = wrappedDek, version = version)

/** 供 UI 展示用的十六进制截断（不涉及安全，仅排版）。 */
internal fun String.shortHex(head: Int = 8, tail: Int = 6): String =
    if (length <= head + tail + 1) this else "${take(head)}…${takeLast(tail)}"

internal fun ByteArray.hex(): String = Bytes.toHex(this)
