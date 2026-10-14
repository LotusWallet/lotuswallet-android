package com.lotuswallet.app.util

import com.lotuswallet.core.common.AppError

/**
 * 错误 → 用户可读文案。
 *
 * 原则：**不把原始异常信息暴露给用户**（既难懂又可能泄露内部结构），
 * 只在「网络」这一类里保留一小段原因，便于用户自行判断要不要换网络。
 */
fun AppError.toMessage(): String = when (this) {
    is AppError.Local -> "本地数据读取失败，请重试"
    is AppError.Network -> {
        val hint = when (code) {
            401, 403 -> "（节点拒绝访问）"
            429 -> "（请求过于频繁，请稍后再试）"
            in 500..599 -> "（节点暂时不可用）"
            else -> ""
        }
        "网络请求失败$hint，请检查网络后重试"
    }
    is AppError.InvalidInput -> reason
    AppError.WrongPassword -> "密码错误"
    is AppError.Unsupported -> reason
    AppError.IntegrityCheckFailed -> "数据完整性校验失败，可能是密钥或数据被改动"
    is AppError.Unknown -> "出现未知错误，请重试"
}

/** 把任意异常转成可展示文案。 */
fun Throwable.toUserMessage(): String = when (this) {
    is com.lotuswallet.core.common.AppException -> error.toMessage()
    else -> message?.takeIf { it.isNotBlank() }?.take(160) ?: "操作失败，请重试"
}
