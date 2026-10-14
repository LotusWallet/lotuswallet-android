package com.lotuswallet.core.network.chain

/**
 * 「链管理」编辑表单的可变草稿。
 *
 * ## 为什么要有这么一层
 * [ChainConfig] 是密封类，且各族的字段差异很大。UI 需要的是一个**扁平的、
 * 全是字符串的**中间态（输入框天然是字符串），提交时再一次性转换 + 校验。
 * 把它放在 network 模块而不是 UI 里，是为了能对「草稿 → 配置」的转换写单测 ——
 * 这一层出错会直接写坏用户的链配置。
 */
data class ChainDraft(
    val id: String = "",
    val family: ChainConfig.Family = ChainConfig.Family.EVM,
    val name: String = "",
    val symbol: String = "",
    val decimals: String = "18",
    val coinType: String = "60",
    val rpcUrls: List<String> = listOf(""),
    val explorerTxUrl: String = "",
    val explorerAddressUrl: String = "",
    val iconUrl: String = "",
    val testnet: Boolean = false,
    val enabled: Boolean = true,

    // EVM
    val chainId: String = "",
    val supportsEip1559: Boolean = true,

    // UTXO
    val addressType: UtxoAddressType = UtxoAddressType.NATIVE_SEGWIT,
    val hrp: String = "",
    val p2pkhVersion: String = "0",
    val p2shVersion: String = "5",
    val dustThreshold: String = "546",

    // Ed25519
    val variant: Ed25519Variant = Ed25519Variant.SOLANA,
    val derivation: Ed25519Derivation = Ed25519Derivation.SLIP10,
    val path: String = "",
    val addressPrefix: String = "",

    // Filecoin
    val network: String = "f",

    // Chia
    val chiaHrp: String = "xch",
) {

    /** 是否是一条尚不存在的链（新增）。 */
    val isNew: Boolean get() = id.isBlank()

    /**
     * 校验并生成配置。返回 null 表示校验不通过，错误信息见 [validationError]。
     */
    fun build(): ChainConfig? = buildResult().getOrNull()

    /**
     * 唯一校验入口：`build()` 与 [validationError] 共用同一段判定。
     *
     * 两者必须同源。曾经它们是两套手写条件，结果「精度越界」「节点地址没有 http 头」
     * 这类情况 [build] 会失败而 [validationError] 却返回「没问题」——
     * 于是保存按钮可点、点下去什么都没发生，用户完全不知道为什么。
     */
    fun buildResult(): Result<ChainConfig> = runCatching {
        require(name.isNotBlank()) { "名称不能为空" }
        require(symbol.isNotBlank()) { "符号不能为空" }
        val decimalsValue = decimals.toIntOrNull() ?: error("精度必须是数字")
        require(decimalsValue in 0..36) { "精度应在 0..36 之间" }
        val coinTypeValue = coinType.toIntOrNull() ?: error("币种编号必须是数字")
        require(coinTypeValue >= 0) { "币种编号不能为负" }
        val urls = rpcUrls.map { it.trim() }.filter { it.isNotEmpty() }
        require(urls.isNotEmpty()) { "至少需要一条节点地址" }
        require(urls.all { it.startsWith("https://") || it.startsWith("http://") }) {
            "节点地址必须以 http:// 或 https:// 开头"
        }

        val common = CommonFields(
            id = id.trim().lowercase(),
            name = name.trim(),
            symbol = symbol.trim().uppercase(),
            decimals = decimalsValue,
            coinType = coinTypeValue,
            rpcUrls = urls,
            explorerTxUrl = explorerTxUrl.trim().ifBlank { null },
            explorerAddressUrl = explorerAddressUrl.trim().ifBlank { null },
            iconUrl = iconUrl.trim().ifBlank { null },
            testnet = testnet,
            enabled = enabled,
        )

        when (family) {
            ChainConfig.Family.EVM -> buildEvm(common)
            ChainConfig.Family.UTXO -> buildUtxo(common)
            ChainConfig.Family.TRON -> buildTron(common)
            ChainConfig.Family.ED25519 -> buildEd25519(common)
            ChainConfig.Family.FILECOIN -> buildFilecoin(common)
            ChainConfig.Family.CHIA -> buildChia(common)
        }
    }

    /** 校验错误信息；通过时返回 null。由 [buildResult] 派生，保证与 [build] 一致。 */
    fun validationError(): String? = buildResult().exceptionOrNull()?.message

    private data class CommonFields(
        val id: String,
        val name: String,
        val symbol: String,
        val decimals: Int,
        val coinType: Int,
        val rpcUrls: List<String>,
        val explorerTxUrl: String?,
        val explorerAddressUrl: String?,
        val iconUrl: String?,
        val testnet: Boolean,
        val enabled: Boolean,
    )

    private fun buildEvm(c: CommonFields): EvmChainConfig {
        val chainIdValue = chainId.toLongOrNull() ?: error("链 ID 必须是数字")
        require(chainIdValue > 0) { "链 ID 必须大于 0" }
        return EvmChainConfig(
            id = c.id.ifBlank { "evm-${chainIdValue}" },
            name = c.name,
            symbol = c.symbol,
            chainId = chainIdValue,
            rpcUrls = c.rpcUrls,
            decimals = c.decimals,
            coinType = c.coinType,
            enabled = c.enabled,
            testnet = c.testnet,
            explorerTxUrl = c.explorerTxUrl,
            explorerAddressUrl = c.explorerAddressUrl,
            iconUrl = c.iconUrl,
            supportsEip1559 = supportsEip1559,
        )
    }

    private fun buildUtxo(c: CommonFields): UtxoChainConfig {
        val hrpValue = hrp.trim().ifBlank { null }
        if (addressType == UtxoAddressType.NATIVE_SEGWIT || addressType == UtxoAddressType.TAPROOT) {
            require(hrpValue != null) { "该地址类型必须填写 bech32 前缀（如 bc / ltc）" }
        }
        return UtxoChainConfig(
            id = c.id.ifBlank { "utxo-${c.coinType}" },
            name = c.name,
            symbol = c.symbol,
            coinType = c.coinType,
            rpcUrls = c.rpcUrls,
            decimals = c.decimals,
            enabled = c.enabled,
            testnet = c.testnet,
            explorerTxUrl = c.explorerTxUrl,
            explorerAddressUrl = c.explorerAddressUrl,
            iconUrl = c.iconUrl,
            addressType = addressType,
            hrp = hrpValue,
            p2pkhVersion = p2pkhVersion.toIntOrNull() ?: 0,
            p2shVersion = p2shVersion.toIntOrNull() ?: 5,
            dustThreshold = dustThreshold.toLongOrNull() ?: 546L,
        )
    }

    private fun buildTron(c: CommonFields): TronChainConfig = TronChainConfig(
        id = c.id.ifBlank { "tron" },
        name = c.name,
        symbol = c.symbol,
        rpcUrls = c.rpcUrls,
        decimals = c.decimals,
        coinType = c.coinType,
        enabled = c.enabled,
        testnet = c.testnet,
        explorerTxUrl = c.explorerTxUrl,
        explorerAddressUrl = c.explorerAddressUrl,
        iconUrl = c.iconUrl,
    )

    private fun buildEd25519(c: CommonFields): Ed25519ChainConfig {
        require(path.isNotBlank()) { "派生路径不能为空" }
        return Ed25519ChainConfig(
            id = c.id.ifBlank { "ed25519-${c.coinType}" },
            name = c.name,
            symbol = c.symbol,
            coinType = c.coinType,
            rpcUrls = c.rpcUrls,
            variant = variant,
            derivation = derivation,
            path = path.trim(),
            decimals = c.decimals,
            enabled = c.enabled,
            testnet = c.testnet,
            explorerTxUrl = c.explorerTxUrl,
            explorerAddressUrl = c.explorerAddressUrl,
            iconUrl = c.iconUrl,
            addressPrefix = addressPrefix.toIntOrNull(),
        )
    }

    private fun buildFilecoin(c: CommonFields): FilecoinChainConfig = FilecoinChainConfig(
        id = c.id.ifBlank { "filecoin" },
        name = c.name,
        symbol = c.symbol,
        coinType = c.coinType,
        rpcUrls = c.rpcUrls,
        path = path.trim().ifBlank { "m/44'/461'/0'/0/0" },
        addressPrefix = addressPrefix.toIntOrNull() ?: 1,
        network = network.trim().ifBlank { "f" },
        decimals = c.decimals,
        enabled = c.enabled,
        testnet = c.testnet,
        explorerTxUrl = c.explorerTxUrl,
        explorerAddressUrl = c.explorerAddressUrl,
        iconUrl = c.iconUrl,
    )

    private fun buildChia(c: CommonFields): ChiaChainConfig = ChiaChainConfig(
        id = c.id.ifBlank { "chia" },
        name = c.name,
        symbol = c.symbol,
        coinType = c.coinType,
        rpcUrls = c.rpcUrls,
        path = path.trim().ifBlank { "m/12381/8444/2/0" },
        hrp = chiaHrp.trim().ifBlank { "xch" },
        decimals = c.decimals,
        enabled = c.enabled,
        testnet = c.testnet,
        explorerTxUrl = c.explorerTxUrl,
        explorerAddressUrl = c.explorerAddressUrl,
        iconUrl = c.iconUrl,
    )

    companion object {

        /** 由已有配置生成草稿（编辑场景）。 */
        fun from(config: ChainConfig): ChainDraft = ChainDraft(
            id = config.id,
            family = config.family,
            name = config.name,
            symbol = config.symbol,
            decimals = config.decimals.toString(),
            coinType = config.coinType.toString(),
            rpcUrls = config.rpcUrls.ifEmpty { listOf("") },
            explorerTxUrl = config.explorerTxUrl.orEmpty(),
            explorerAddressUrl = config.explorerAddressUrl.orEmpty(),
            iconUrl = config.iconUrl.orEmpty(),
            testnet = config.testnet,
            enabled = config.enabled,
            chainId = (config as? EvmChainConfig)?.chainId?.toString().orEmpty(),
            supportsEip1559 = (config as? EvmChainConfig)?.supportsEip1559 ?: true,
            addressType = (config as? UtxoChainConfig)?.addressType ?: UtxoAddressType.NATIVE_SEGWIT,
            hrp = (config as? UtxoChainConfig)?.hrp.orEmpty(),
            p2pkhVersion = (config as? UtxoChainConfig)?.p2pkhVersion?.toString().orEmpty().ifBlank { "0" },
            p2shVersion = (config as? UtxoChainConfig)?.p2shVersion?.toString().orEmpty().ifBlank { "5" },
            dustThreshold = (config as? UtxoChainConfig)?.dustThreshold?.toString().orEmpty().ifBlank { "546" },
            variant = (config as? Ed25519ChainConfig)?.variant ?: Ed25519Variant.SOLANA,
            derivation = (config as? Ed25519ChainConfig)?.derivation ?: Ed25519Derivation.SLIP10,
            path = when (config) {
                is Ed25519ChainConfig -> config.path
                is FilecoinChainConfig -> config.path
                is ChiaChainConfig -> config.path
                else -> ""
            },
            addressPrefix = when (config) {
                is Ed25519ChainConfig -> config.addressPrefix?.toString().orEmpty()
                is FilecoinChainConfig -> config.addressPrefix.toString()
                else -> ""
            },
            network = (config as? FilecoinChainConfig)?.network ?: "f",
            chiaHrp = (config as? ChiaChainConfig)?.hrp ?: "xch",
        )

        /** 新建链的默认草稿（按家族给一套合理初值，减少用户的输入量）。 */
        fun empty(family: ChainConfig.Family): ChainDraft = when (family) {
            ChainConfig.Family.EVM -> ChainDraft(
                family = family, decimals = "18", coinType = "60", chainId = "",
            )

            ChainConfig.Family.UTXO -> ChainDraft(
                family = family, decimals = "8", coinType = "0",
                hrp = "bc", p2pkhVersion = "0", p2shVersion = "5", dustThreshold = "546",
            )

            ChainConfig.Family.TRON -> ChainDraft(family = family, decimals = "6", coinType = "195")

            ChainConfig.Family.ED25519 -> ChainDraft(
                family = family, decimals = "9", coinType = "501",
                variant = Ed25519Variant.SOLANA, derivation = Ed25519Derivation.SLIP10,
                path = "m/44'/501'/0'/0'",
            )

            ChainConfig.Family.FILECOIN -> ChainDraft(
                family = family, decimals = "18", coinType = "461",
                path = "m/44'/461'/0'/0/0", addressPrefix = "1", network = "f",
            )

            ChainConfig.Family.CHIA -> ChainDraft(
                family = family, decimals = "12", coinType = "8444",
                path = "m/12381/8444/2/0", chiaHrp = "xch",
            )
        }
    }
}
