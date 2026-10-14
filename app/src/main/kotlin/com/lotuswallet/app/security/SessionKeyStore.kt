package com.lotuswallet.app.security

import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话密钥保管器 —— 全应用唯一持有 DEK 明文的地方。
 *
 * ## 设计要点
 * - DEK（数据加密密钥）**只存在于内存**，进程被杀即消失；
 * - 主密码 → KEK 的过程每次解锁都重算，KEK 从不驻留；
 * - [lock] 会先把字节清零再丢弃引用，降低内存被 dump 后残留密钥的风险；
 * - [put] 会复制一份，避免调用方的数组在别处被复用或清零导致状态错乱。
 *
 * 所有读写密文的仓库都必须通过 [requireDek] 取密钥：一旦锁定，
 * 它们会立刻失败而不是悄悄用到旧密钥。
 */
@Singleton
class SessionKeyStore @Inject constructor() {

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    @Volatile
    private var dek: ByteArray? = null

    val isUnlocked: Boolean get() = dek != null

    /** 取得 DEK；未解锁时抛 [AppError.Local]，由上层映射为「保险库已锁定」。 */
    fun requireDek(): ByteArray =
        dek ?: throw AppException(AppError.Local(IllegalStateException("vault locked")))

    fun put(key: ByteArray) {
        require(key.isNotEmpty()) { "DEK 不能为空" }
        lock()
        dek = key.copyOf()
        _unlocked.value = true
    }

    /** 立即锁定并清零内存中的密钥。 */
    fun lock() {
        dek?.fill(0)
        dek = null
        _unlocked.value = false
    }
}
