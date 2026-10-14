package com.lotuswallet.core.network.market

import com.lotuswallet.core.network.chain.RemoteEndpoints
import com.lotuswallet.core.network.rpc.RestClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DApp 市场远端清单模型。
 *
 * 这是替代旧版「服务端 DApp 接口」的方案：清单是一份**静态只读 JSON**，
 * 放在 GitHub 仓库里，客户端拉取后本地缓存，离线可用。
 * 好处：零后端、可审计（仓库有提交历史）、可按需灰度。
 */
@Serializable
data class MarketManifest(
    val version: Int = 1,
    /** 清单生成时间（ISO-8601），用于展示与缓存判断。 */
    val updatedAt: String? = null,
    val categories: List<MarketCategory> = emptyList(),
    val dapps: List<MarketDapp> = emptyList(),
)

/** 分类（交易 / 借贷 / 衍生品 / 挖矿 / 基金 / 保险 / NFT / 预测市场 …）。 */
@Serializable
data class MarketCategory(
    val id: String,
    val name: String,
    val iconUrl: String? = null,
    val order: Int = 0,
)

/** 单个 DApp 条目。 */
@Serializable
data class MarketDapp(
    val id: String,
    val name: String,
    /** 目标地址（通常是 https，也允许自定义 scheme 唤起钱包）。 */
    val url: String,
    val category: String,
    val description: String? = null,
    val iconUrl: String? = null,
    /** 支持的链 id 列表，与 [com.lotuswallet.core.network.chain.ChainConfig.id] 对应。 */
    val chains: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** 是否经过团队试用（旧版承诺「团队试用方可上架」，保留该字段）。 */
    val reviewed: Boolean = false,
    val featured: Boolean = false,
    val order: Int = 0,
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return name.lowercase().contains(q) ||
            (description?.lowercase()?.contains(q) == true) ||
            tags.any { it.lowercase().contains(q) }
    }
}

/**
 * 市场清单仓库。远端不可用时不报错 —— 返回内置的精选清单，
 * 保证「市场」Tab 永远有内容可看。
 */
class MarketRepository(
    private val rest: RestClient,
    private val remoteUrlsProvider: () -> List<String> = { RemoteEndpoints.MARKET_MANIFESTS },
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cache: MarketManifest? = null

    /**
     * 最近一次**从网络**成功拉取到的清单原文（不含持久化兜底）。
     * 上层据此决定是否要把它写回本地缓存、并刷新缓存时间戳。
     */
    @Volatile
    var fetchedRemoteJson: String? = null
        private set

    fun current(): MarketManifest = cache ?: BuiltinMarket.manifest

    /**
     * 拉取远端清单并解析；成功则更新缓存。失败返回 null（调用方继续用缓存）。
     *
     * 与 [com.lotuswallet.core.network.chain.ChainRepository.refresh] 同样是
     * **先联网、失败才退回持久化数据** —— 反过来的话，第一次成功之后就再也不更新了。
     *
     * @param persisted 上次成功拉取并落盘的原文，仅作网络失败时的兜底
     */
    suspend fun refresh(persisted: String? = null): MarketManifest? {
        val fetched = remoteUrlsProvider().takeIf { it.isNotEmpty() }?.let { urls ->
            runCatching { rest.getText(urls) }.getOrNull()
        }
        val raw = fetched ?: persisted ?: return null
        val parsed = runCatching { json.decodeFromString(MarketManifest.serializer(), raw) }.getOrNull()
            ?: return null
        cache = parsed
        if (fetched != null) fetchedRemoteJson = fetched
        return parsed
    }

    fun initialize(persisted: String?) {
        if (persisted.isNullOrBlank()) return
        cache = runCatching { json.decodeFromString(MarketManifest.serializer(), persisted) }.getOrNull()
    }

    fun search(query: String): List<MarketDapp> = current().dapps.filter { it.matches(query) }

    fun byCategory(categoryId: String): List<MarketDapp> =
        current().dapps.filter { it.category == categoryId }.sortedBy { it.order }
}

/**
 * 内置兜底清单。远端仓库就绪前，「市场」Tab 用这份数据。
 * 这些条目都是公开、成熟的 DeFi 入口，仅作导航用途，不涉及任何代币与资金托管。
 */
object BuiltinMarket {

    val manifest: MarketManifest = MarketManifest(
        version = 0,
        updatedAt = null,
        categories = listOf(
            MarketCategory("dex", "交易 / DEX", order = 0),
            MarketCategory("lending", "借贷", order = 1),
            MarketCategory("staking", "理财 / 质押", order = 2),
            MarketCategory("bridge", "跨链桥", order = 3),
            MarketCategory("nft", "NFT", order = 4),
            MarketCategory("tools", "工具", order = 5),
        ),
        dapps = listOf(
            MarketDapp(
                id = "uniswap",
                name = "Uniswap",
                url = "https://app.uniswap.org",
                category = "dex",
                description = "以太坊上最主流的去中心化交易协议。",
                chains = listOf("ethereum", "polygon", "arbitrum", "optimism", "base"),
                tags = listOf("swap", "liquidity"),
                featured = true,
            ),
            MarketDapp(
                id = "pancakeswap",
                name = "PancakeSwap",
                url = "https://pancakeswap.finance",
                category = "dex",
                description = "BNB Chain 生态的交易与流动性平台。",
                chains = listOf("bsc"),
                tags = listOf("swap", "farm"),
            ),
            MarketDapp(
                id = "aave",
                name = "Aave",
                url = "https://app.aave.com",
                category = "lending",
                description = "多链借贷协议，支持存款生息与抵押借币。",
                chains = listOf("ethereum", "polygon", "arbitrum", "optimism", "base", "avalanche"),
                tags = listOf("lending"),
                featured = true,
            ),
            MarketDapp(
                id = "lido",
                name = "Lido",
                url = "https://stake.lido.fi",
                category = "staking",
                description = "以太坊流动性质押。",
                chains = listOf("ethereum"),
                tags = listOf("staking", "eth2"),
            ),
            MarketDapp(
                id = "jupiter",
                name = "Jupiter",
                url = "https://jup.ag",
                category = "dex",
                description = "Solana 上的聚合交易入口。",
                chains = listOf(),
                tags = listOf("solana", "aggregator"),
            ),
            MarketDapp(
                id = "opensea",
                name = "OpenSea",
                url = "https://opensea.io",
                category = "nft",
                description = "最大的 NFT 交易市场之一。",
                chains = listOf("ethereum", "polygon", "arbitrum", "optimism", "base"),
                tags = listOf("nft"),
            ),
            MarketDapp(
                id = "revoke",
                name = "Revoke.cash",
                url = "https://revoke.cash",
                category = "tools",
                description = "检查并撤销代币授权，钱包安全必备。",
                chains = listOf("ethereum", "bsc", "polygon", "arbitrum", "optimism", "base"),
                tags = listOf("security", "approval"),
                featured = true,
            ),
        ),
    )
}
