package com.lotuswallet.core.common

/**
 * 全应用统一的错误类型。
 *
 * 设计原则：错误必须可分类、可本地化、可向用户解释，禁止把原始异常直接抛到 UI 层。
 */
sealed class AppError(open val cause: Throwable? = null) {

    /** 本地数据/数据库错误 */
    data class Local(override val cause: Throwable? = null) : AppError(cause)

    /** 网络与远端服务错误 */
    data class Network(override val cause: Throwable? = null, val code: Int? = null) : AppError(cause)

    /** 用户输入非法 */
    data class InvalidInput(val reason: String) : AppError()

    /** 密码/口令错误 */
    data object WrongPassword : AppError()

    /** 运行环境不支持（例如设备无硬件密钥库） */
    data class Unsupported(val reason: String) : AppError()

    /** 数据完整性校验失败（AEAD tag 不匹配等） */
    data object IntegrityCheckFailed : AppError()

    /** 未分类错误 */
    data class Unknown(override val cause: Throwable? = null) : AppError(cause)

    /** 用于日志与诊断的稳定标识 */
    val key: String
        get() = when (this) {
            is Local -> "local"
            is Network -> "network"
            is InvalidInput -> "invalid_input:$reason"
            is Unsupported -> "unsupported:$reason"
            WrongPassword -> "wrong_password"
            IntegrityCheckFailed -> "integrity_failed"
            is Unknown -> "unknown"
        }
}
