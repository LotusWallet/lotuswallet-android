package com.lotuswallet.core.common

import javax.inject.Qualifier

/** 标注 IO 调度器（磁盘、数据库、网络）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** 标注 CPU 密集调度器（密钥派生、签名）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** 标注主线程调度器。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/** 应用级调度器提供者，便于测试替换。 */
interface DispatcherProvider {
    val io: kotlinx.coroutines.CoroutineDispatcher
    val default: kotlinx.coroutines.CoroutineDispatcher
    val main: kotlinx.coroutines.CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val io = kotlinx.coroutines.Dispatchers.IO
    override val default = kotlinx.coroutines.Dispatchers.Default
    override val main = kotlinx.coroutines.Dispatchers.Main
}
