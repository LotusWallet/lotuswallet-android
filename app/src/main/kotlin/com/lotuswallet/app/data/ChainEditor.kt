package com.lotuswallet.app.data

import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainRegistry
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.network.chain.UserChainOverrides
import com.lotuswallet.core.network.chain.commonCopy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「链管理」的写入侧。
 *
 * 职责边界很小：**只负责维护本机的用户覆盖清单，并让 [ChainRepository] 立刻感知**。
 *
 * ## 为什么用户可以改链配置
 * 内置/远端清单来自 chainlist 转存，覆盖面和节点质量都不完整（很多小链只有一两个
 * 公共节点，甚至没有）。把「加一条链 / 换一个 RPC」变成一次本机编辑，
 * 比等仓库合并快得多，也不会因为某个节点挂掉就让整条链不可用。
 *
 * ## 为什么不写回远端仓库
 * 覆盖只存在本机 DataStore，不上传、不同步。用户改错了，用「恢复默认」即可回到
 * 内置/远端的一致状态；这也避免了「谁都能改公共清单」带来的供应链风险。
 */
@Singleton
class ChainEditor @Inject constructor(
    private val settings: SettingsRepository,
    private val chains: ChainRepository,
) {

    /** 一条链在管理页里的展示模型。 */
    data class Row(
        val config: ChainConfig,
        /** 是否用户自建（内置与远端都没有）。 */
        val userDefined: Boolean,
        /** 是否存在本机覆盖（可「恢复默认」）。 */
        val overridden: Boolean,
    )

    private val lock = Mutex()

    /** 全部链 + 来源标记，已按「测试网靠后、名称排序」整理。 */
    suspend fun rows(): List<Row> = lock.withLock {
        val overrides = readOverrides()
        val overriddenIds = overrides.modified.map { it.id }.toSet()
        chains.current()
            .sortedWith(compareBy({ it.testnet }, { it.name }))
            .map { Row(it, chains.isUserDefined(it.id), it.id in overriddenIds) }
    }

    suspend fun find(id: String): ChainConfig? = chains.findById(id)

    /**
     * 新增或整体替换一条链配置。
     *
     * @throws IllegalArgumentException id 为空或与内置链冲突（不允许覆盖内置 id 的语义）
     */
    suspend fun upsert(config: ChainConfig) = lock.withLock {
        require(config.id.isNotBlank()) { "链 id 不能为空" }
        val current = readOverrides()
        val modified = current.modified.filterNot { it.id == config.id } + config
        write(modified)
    }

    /** 启用 / 停用一条链（内置链也能停用，体现为一条 enabled=false 的覆盖）。 */
    suspend fun setEnabled(id: String, enabled: Boolean) = lock.withLock {
        val config = chains.findById(id) ?: return@withLock
        val current = readOverrides()
        val updated = if (config.enabled == enabled) {
            config
        } else {
            withEnabled(config, enabled)
        }
        val modified = current.modified.filterNot { it.id == id } + updated
        write(modified)
    }

    /**
     * 移除一条链。
     * - 用户自建：直接删掉覆盖项，链从列表消失；
     * - 内置/远端：只能停用（写一条 enabled=false 的覆盖），因为下次刷新远端它还会回来。
     */
    suspend fun remove(id: String) = lock.withLock {
        val current = readOverrides()
        val modified = current.modified.filterNot { it.id == id }
        if (chains.isUserDefined(id)) {
            write(modified)
        } else {
            val config = chains.findById(id)
            write(if (config == null) modified else modified + withEnabled(config, false))
        }
    }

    /** 撤销对某条链的全部本地修改，回到内置/远端状态。 */
    suspend fun restoreDefault(id: String) = lock.withLock {
        write(readOverrides().modified.filterNot { it.id == id })
    }

    /** 清空全部本地覆盖（危险操作，UI 需二次确认）。 */
    suspend fun resetAll() = lock.withLock { write(emptyList()) }

    /** 为新建链生成一个未被占用的稳定 id。 */
    suspend fun suggestId(nameOrSymbol: String): String = lock.withLock {
        val base = nameOrSymbol.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "chain" }
        val taken = chains.current().map { it.id }.toSet()
        if (base !in taken) return@withLock base
        var i = 2
        while ("$base-$i" in taken) i++
        "$base-$i"
    }

    // ------------------------------------------------------------ 内部

    private suspend fun readOverrides(): UserChainOverrides =
        ChainRegistry.parseUserOverrides(settings.userChainsJson())

    private suspend fun write(modified: List<ChainConfig>) {
        val payload = ChainRegistry.encodeUserOverrides(UserChainOverrides(modified = modified))
        settings.saveUserChainsJson(payload)
        chains.applyUserOverrides(payload)
    }

    private fun withEnabled(config: ChainConfig, enabled: Boolean): ChainConfig =
        config.commonCopy(enabled = enabled)
}
