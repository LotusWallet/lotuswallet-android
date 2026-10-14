package com.lotuswallet.app.ui.lock

import androidx.lifecycle.ViewModel
import com.lotuswallet.app.security.VaultManager
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.AndroidKeyStoreGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * 解锁相关动作。
 *
 * 生物识别的两步（准备 Cipher → 交给 BiometricPrompt 授权 → 用授权后的 Cipher 解密）
 * 必须跨「ViewModel 调用」与「系统弹窗回调」两个阶段，所以这里缓存了
 * 本次待解的密文 —— 用户取消后会清空，不会残留。
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val vaultManager: VaultManager,
) : ViewModel() {

    private var pendingPayloadHex: String? = null

    /**
     * 准备一个「待授权」的解密 Cipher。
     *
     * 返回 null 表示：没有配置生物识别、硬件密钥已失效（例如用户重新录入指纹），
     * 或设备不支持。这三种情况都应当退回主密码解锁。
     */
    suspend fun prepareDecryptCipher(): Cipher? {
        val record = vaultManager.biometricRecord() ?: return null
        pendingPayloadHex = record.wrappedDek
        val payload = runCatching { Bytes.fromHex(record.wrappedDek) }.getOrNull()
        if (payload == null || payload.size <= GCM_IV_BYTES) {
            pendingPayloadHex = null
            return null
        }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        return runCatching { AndroidKeyStoreGate.decryptionCipher(record.keyAlias, iv) }.getOrNull()
    }

    /** 用已授权的 Cipher 完成解锁。 */
    suspend fun completeBiometricUnlock(cipher: Cipher): Boolean {
        val payload = pendingPayloadHex ?: return false
        val ok = runCatching { vaultManager.unlockWithBiometric(cipher, payload) }.isSuccess
        if (ok) pendingPayloadHex = null
        return ok
    }

    fun cancelBiometric() {
        pendingPayloadHex = null
    }

    /** 开启生物识别：返回待授权的加密 Cipher。 */
    suspend fun prepareEncryptCipher(): Cipher? =
        runCatching { AndroidKeyStoreGate.encryptionCipher(VaultManager.BIOMETRIC_ALIAS) }.getOrNull()

    /** 用已授权的 Cipher 把当前 DEK 用硬件密钥包裹后落盘。 */
    suspend fun enableBiometric(cipher: Cipher): Boolean =
        runCatching { vaultManager.saveBiometricWrapping(cipher) }.isSuccess

    suspend fun disableBiometric() = vaultManager.disableBiometric()

    private companion object {
        const val GCM_IV_BYTES = 12
    }
}
