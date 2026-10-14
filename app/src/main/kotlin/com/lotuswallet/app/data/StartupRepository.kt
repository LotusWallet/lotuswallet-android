package com.lotuswallet.app.data

import com.lotuswallet.core.common.IoDispatcher
import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.network.market.MarketRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动引导。
 *
 * ## Serverless 的启动顺序
 * ```
 * 1. 读本地缓存的远端清单（同步、零延迟）→ 立即可用
 * 2. 若缓存不存在或已超过 24 小时 → 后台拉取最新清单 → 成功则覆盖并写回缓存
 * ```
 * 这个顺序保证：**没有网络、没有远端仓库时，App 依然完整可用**。
 * 远端只是「让新增 EVM 链 / DApp 不必发版」的加分项，而不是可用性的前提。
 *
 * ## 为什么要有 24 小时有效期
 * 每次都联网会让冷启动变慢、也更容易在弱网下卡住市场页；永不联网则远端更新形同虚设。
 * 折中：**先展示缓存，超过 24 小时才重新抓取**。用户主动点「刷新」时走
 * [refreshRemote] 的 `force = true`，忽略有效期。
 */
@Singleton
class StartupRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val chains: ChainRepository,
    private val market: MarketRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile
    private var primed = false

    /**
     * 刷新互斥锁。
     *
     * 根 ViewModel 与市场页都会在启动时尝试刷新，若不串行化，弱网下会同时发出
     * 两轮完全相同的请求。加锁之后第二次进入会重新做一次时效判断，
     * 从而直接跳过（因为第一轮已经把时间戳刷新了）。
     */
    private val refreshLock = Mutex()

    /** 用缓存初始化（不联网）。幂等，可重复调用。 */
    suspend fun primeFromCache() {
        if (primed) return
        withContext(io) {
            chains.initialize(settings.lastChainsJson(), settings.userChainsJson())
            market.initialize(settings.lastMarketJson())
        }
        primed = true
    }

    /**
     * 刷新远端清单。
     *
     * 任何失败都静默 —— 用户不该因为一个可选的远端仓库而看到错误提示。
     *
     * @param force true 时忽略 24 小时有效期，强制联网（用于用户手动下拉/点刷新）
     */
    suspend fun refreshRemote(force: Boolean = false) = refreshLock.withLock {
        withContext(io) {
            val now = System.currentTimeMillis()

            if (force || isStale(settings.lastChainsCachedAt(), now)) {
                runCatching {
                    chains.refresh(force = true, persistedRemote = settings.lastChainsJson())
                    // 只有真的拉到远端内容时才写回（refresh 内部已做合并与降级）
                    chains.cachedRemoteJson?.let { settings.saveChainsJson(it, now) }
                }
            }

            if (force || isStale(settings.lastMarketCachedAt(), now)) {
                runCatching {
                    market.refresh(settings.lastMarketJson())
                    market.fetchedRemoteJson?.let { settings.saveMarketJson(it, now) }
                }
            }
        }
    }

    /** 当前可用的链清单。 */
    fun chains() = chains.current()

    fun enabledChains() = chains.enabled

    /**
     * 缓存是否已过期。**从未缓存过（0）同样算过期**，
     * 于是「首次启动即从 GitHub 抓取」与「超过 24 小时重新抓取」共用同一条判断。
     */
    private fun isStale(cachedAt: Long, now: Long): Boolean =
        cachedAt <= 0L || now - cachedAt >= CACHE_TTL_MILLIS

    companion object {
        /** 远端清单缓存有效期：24 小时。 */
        const val CACHE_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}
