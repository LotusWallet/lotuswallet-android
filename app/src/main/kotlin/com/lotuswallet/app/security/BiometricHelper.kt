package com.lotuswallet.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * 生物识别 / 设备凭证解锁。
 *
 * ## 与「假的生物识别」的区别
 * 很多应用的做法是：指纹验证通过 → 用一个存在本地（或服务端）的固定口令解密数据。
 * 那等于没加密 —— 口令能被 dump 出来。
 *
 * 这里的做法是：
 * 1. DEK 被 Android Keystore 里**不可导出**的硬件密钥包裹后落盘；
 * 2. 该密钥设置了 `setUserAuthenticationRequired(true)`，系统只有在
 *    一次真实的用户认证之后才会放行 `Cipher.doFinal`；
 * 3. 因此**指纹/面容真正参与了解密**，把应用数据目录整体拷走也没用。
 *
 * 代价是：用户重新录入指纹后系统会使旧密钥失效（`setInvalidatedByBiometricEnrollment(true)`），
 * 此时必须用主密码解锁并重新开启生物识别 —— [BiometricManager] 的能力检查会提前发现这种情况。
 */
object BiometricHelper {

    /** 硬件支持的认证方式。用 STRONG 是因为 Keystore 密钥要求强生物识别。 */
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG

    /** 设备能否使用生物识别。 */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * 发起一次「解密」认证。
     *
     * @param cipher 已 init 但**尚未授权**的 Cipher（由 [AndroidKeyStoreGate.decryptionCipher] 提供）
     */
    fun authenticateForDecrypt(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeText: String,
        onSuccess: (Cipher) -> Unit,
        onFailure: (String) -> Unit,
    ) = authenticate(activity, cipher, title, subtitle, negativeText, onSuccess, onFailure)

    /** 发起一次「加密」认证（首次开启生物识别时调用）。 */
    fun authenticateForEncrypt(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeText: String,
        onSuccess: (Cipher) -> Unit,
        onFailure: (String) -> Unit,
    ) = authenticate(activity, cipher, title, subtitle, negativeText, onSuccess, onFailure)

    private fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeText: String,
        onSuccess: (Cipher) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized == null) {
                        onFailure("认证成功但未返回可用的密钥句柄")
                    } else {
                        onSuccess(authorized)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 用户主动取消不算错误，不该弹提示
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        return
                    }
                    onFailure(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // 单次不匹配（如指纹没放正），系统会自动重试，这里刻意不做任何事，
                    // 避免频繁弹提示打断用户。真正的失败会走 onAuthenticationError。
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
