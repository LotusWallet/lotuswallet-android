package com.lotuswallet.core.network.chain

import com.lotuswallet.core.network.rpc.RestClient

/**
 * 链配置仓库：内置清单为底，远端清单与用户自定义逐层覆盖。
 *
 * 远端清单是 `LotusWallet/assets` 仓库里的一个**静态只读 JSON**
 * （由 chainlist 每日转存生成），客户端拉取后本地缓存；
 * 拉取失败时静默使用上一次成功的缓存或内置清单。
 *
 * 这意味着：**新增一条 EVM 链不需要发版** —— 仓库改一条数据，全量用户下次刷新即生效。
 * 仓库里没有的链，用户也可以在「链管理」里自己加：那份覆盖只存在本机。
 */
class ChainRepository(
    private val rest: RestClient,
    private val remoteUrlsProvider: () -> List<String> = { RemoteEndpoints.CHAINS_MANIFESTS },
) {

    @Volatile
    private var cache: List<ChainConfig>? = null

    @Volatile
    private var lastGoodRemote: String? = null

    @Volatile
    private var userOverridesRaw: String? = null

    @Volatile
    private var remoteRaw: String? = null

    /** 最近一次成功拉取的远端清单原文，供上层持久化。 */
    val cachedRemoteJson: String? get() = lastGoodRemote

    /** 已加载的链清单（未加载时返回内置）。 */
    fun current(): List<ChainConfig> = cache ?: ChainRegistry.builtin()

    val enabled: List<ChainConfig> get() = current().filter { it.enabled }

    fun findById(id: String): ChainConfig? = current().firstOrNull { it.id == id }

    fun findEvm(chainId: Long): EvmChainConfig? =
        current().filterIsInstance<EvmChainConfig>().firstOrNull { it.chainId == chainId }

    /** 是否为用户自建的链（内置与远端都没有的 id）。 */
    fun isUserDefined(id: String): Boolean {
        val builtin = ChainRegistry.builtinIds()
        val remoteIds = ChainRegistry.parseOrNull(remoteRaw)?.map { it.id }?.toSet().orEmpty()
        return id !in builtin && id !in remoteIds
    }

    /** 用户覆盖里是否包含该链（用于「恢复默认」按钮的可用性判断）。 */
    fun hasUserOverride(id: String): Boolean =
        ChainRegistry.parseUserOverrides(userOverridesRaw).modified.any { it.id == id }

    suspend fun refresh(force: Boolean = false, persistedRemote: String? = null): List<ChainConfig> {
        if (!force && cache != null) return cache!!
        // force 时**先联网**，持久化的旧清单只当网络失败时的兜底。
        // 早期版本把 persistedRemote 放在最前面，结果是第一次同步成功之后永远不会再联网
        // —— 远端加了新链也永远拉不到，"从远端加链不用发版"这条设计就废了。
        val remote = if (force) {
            fetchRemote() ?: persistedRemote ?: lastGoodRemote
        } else {
            persistedRemote ?: lastGoodRemote ?: fetchRemote()
        }
        remoteRaw = remote
        val merged = ChainRegistry.merge(remote, userOverridesRaw)
        cache = merged
        return merged
    }

    /** 首次启动时的初始化：先用持久化的远端清单与用户覆盖组装，再异步尝试刷新。 */
    fun initialize(persistedRemote: String?, userOverrides: String? = null) {
        remoteRaw = persistedRemote
        userOverridesRaw = userOverrides
        cache = ChainRegistry.merge(persistedRemote, userOverrides)
    }

    /**
     * 用户在「链管理」里改完清单后调用：立刻让内存态生效（无需重启、无需联网）。
     */
    fun applyUserOverrides(raw: String?) {
        userOverridesRaw = raw
        cache = ChainRegistry.merge(remoteRaw ?: lastGoodRemote, raw)
    }

    private suspend fun fetchRemote(): String? {
        val urls = remoteUrlsProvider()
        if (urls.isEmpty()) return null
        return runCatching { rest.getText(urls) }
            .onSuccess { lastGoodRemote = it }
            .getOrNull()
    }
}

/**
 * 远端只读资源的地址。
 *
 * 全部指向 LotusWallet 自己的两个 GitHub 仓库（serverless：没有后端，只有静态文件）：
 * ```
 * LotusWallet/assets     chains/chains.json   链清单（由 chainlist 每日转存生成）
 *                        market/dapps.json    DApp 市场清单
 * LotusWallet/download   versions.json        版本清单（App 的「检查更新」读它）
 * ```
 *
 * 每份资源都给出两个等价地址，**按顺序失败转移**：
 *  1. `raw.githubusercontent.com` —— GitHub 官方源，内容一定是最新的；
 *  2. `cdn.jsdelivr.net/gh/...`   —— 第三方 CDN 镜像，国内可达性通常更好，
 *     代价是默认有约 12 小时缓存（对链清单/市场清单这类低频变动的数据可以接受）。
 *
 * 顺序刻意是「官方优先」：能连上官方源时拿到的永远是最新内容，
 * 只有官方源不可达（超时/被墙）才会退到 CDN。若将来实测国内多数用户都走 CDN，
 * 把这两个常量换个位置即可，其余代码不用动。
 */
object RemoteEndpoints {

    private const val ASSETS_RAW =
        "https://raw.githubusercontent.com/LotusWallet/assets/main"
    private const val ASSETS_CDN =
        "https://cdn.jsdelivr.net/gh/LotusWallet/assets@main"

    private const val DOWNLOAD_RAW =
        "https://raw.githubusercontent.com/LotusWallet/download/main"
    private const val DOWNLOAD_CDN =
        "https://cdn.jsdelivr.net/gh/LotusWallet/download@main"

    private fun assets(path: String): List<String> = listOf("$ASSETS_RAW/$path", "$ASSETS_CDN/$path")

    private fun download(path: String): List<String> = listOf("$DOWNLOAD_RAW/$path", "$DOWNLOAD_CDN/$path")

    /** 链配置清单（可覆盖内置链、新增 EVM 链，无需发版）。 */
    val CHAINS_MANIFESTS: List<String> = assets("chains/chains.json")

    /** DApp 市场清单。 */
    val MARKET_MANIFESTS: List<String> = assets("market/dapps.json")

    /** 版本清单。App 用它判断有没有新版本。 */
    val UPDATE_MANIFESTS: List<String> = download("versions.json")

    /** 面向用户展示的下载页（在应用内点「前往下载」时打开）。 */
    const val DOWNLOAD_PAGE = "https://github.com/LotusWallet/download/releases/latest"
}
