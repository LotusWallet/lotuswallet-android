package com.lotuswallet.core.common

/**
 * 轻量结果类型。用于跨层传递「成功值 / 可解释错误」。
 *
 * 有意不使用 kotlin.Result：它无法携带结构化的 [AppError]。
 */
sealed interface LotusResult<out T> {

    data class Success<out T>(val value: T) : LotusResult<T>

    data class Failure(val error: AppError) : LotusResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> LotusResult<T>.map(transform: (T) -> R): LotusResult<R> = when (this) {
    is LotusResult.Success -> LotusResult.Success(transform(value))
    is LotusResult.Failure -> this
}

inline fun <T> LotusResult<T>.onSuccess(action: (T) -> Unit): LotusResult<T> {
    if (this is LotusResult.Success) action(value)
    return this
}

inline fun <T> LotusResult<T>.onFailure(action: (AppError) -> Unit): LotusResult<T> {
    if (this is LotusResult.Failure) action(error)
    return this
}

/**
 * 把可能抛异常的代码块包成 [LotusResult]。
 */
inline fun <T> runCatchingLotus(block: () -> T): LotusResult<T> = try {
    LotusResult.Success(block())
} catch (t: Throwable) {
    LotusResult.Failure(t.toAppError())
}

/**
 * 异常 → [AppError] 的兜底映射。各层可在自身边界做更精确的映射。
 */
fun Throwable.toAppError(): AppError = when (this) {
    is AppException -> error
    is java.io.IOException -> AppError.Network(this)
    is SecurityException -> AppError.Local(this)
    else -> AppError.Unknown(this)
}

/** 允许在任意层抛出携带结构化错误的异常。 */
class AppException(val error: AppError) : Exception(error.cause)
