@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.lotuswallet.core.network.chain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * 链的**声明式**配置。
 *
 * 这是「新增链零代码」这条硬性要求的落点：一条链在这里只是一个数据对象，
 * 同族链共用同一份业务逻辑（派生、地址、签名、RPC），差异全部收敛到配置字段里。
 *
 * 序列化时用 `family` 作为类型判别字段，因此 `chains.json` 长这样：
 * ```json
 * { "family": "evm", "id": "bsc", "name": "BNB Smart Chain", "chainId": 56, ... }
 * ```
 *
 * ## 三种来源
 * ```
 * 内置（随包）  <  远端清单（GitHub，可覆盖同 id、可新增）  <  用户自定义（本机）
 * ```
 * 用户自定义同样序列化成这个格式，因此「用户加一条链」与「仓库加一条链」走同一条代码路径。
 */
@Serializable
@JsonClassDiscriminator("family")
sealed class ChainConfig {

    /** 稳定标识（小写、连字符），用于数据库外键，一经发布不可更改。 */
    abstract val id: String

    /** 展示名称。 */
    abstract val name: String

    /** 原生币符号。 */
    abstract val symbol: String

    /** 原生币精度。 */
    abstract val decimals: Int

    /** 是否在客户端启用。 */
    abstract val enabled: Boolean

    /** 是否测试网。 */
    abstract val testnet: Boolean

    /** 浏览器交易链接前缀，例如 `https://etherscan.io/tx/`。 */
    abstract val explorerTxUrl: String?

    /** 浏览器地址链接前缀。 */
    abstract val explorerAddressUrl: String?

    /** 图标地址（可空，客户端回退为生成式图标）。 */
    abstract val iconUrl: String?

    /**
     * 只读公共端点列表，按顺序做故障转移。
     *
     * 刻意是**列表**而不是单个 URL：公共节点随时会挂、会限流、会被墙，
     * 只配一个等于把可用性交给运气。用户也可以在「链管理」里自行增删。
     */
    abstract val rpcUrls: List<String>

    /** SLIP-44 币种编号，决定 BIP-44 派生路径的第二级。 */
    abstract val coinType: Int

    val family: Family
        get() = when (this) {
            is EvmChainConfig -> Family.EVM
            is UtxoChainConfig -> Family.UTXO
            is TronChainConfig -> Family.TRON
            is Ed25519ChainConfig -> Family.ED25519
            is FilecoinChainConfig -> Family.FILECOIN
            is ChiaChainConfig -> Family.CHIA
        }

    /** 交易浏览器链接；未配置时返回 null。 */
    fun txUrl(hash: String): String? = explorerTxUrl?.let { it + hash }

    fun addressUrl(address: String): String? = explorerAddressUrl?.let { it + address }

    enum class Family { EVM, UTXO, TRON, ED25519, FILECOIN, CHIA }
}

/**
 * 该链能否由助记词 / 私钥派生出账户。
 *
 * Chia 的地址是 `xch1…`，其 puzzle hash 需要 BLS12-381 与标准 puzzle 的
 * 字节级常量；本版本尚未内置该派生，因此 Chia 只能以**观察地址**的方式加入。
 * 其余链族全部支持派生。
 */
val ChainConfig.supportsKeyDerivation: Boolean
    get() = this !is ChiaChainConfig

/** 该链能否在 App 内构造并广播交易（当前只有 BTC 系 / EVM / TRON 已实现签名）。 */
val ChainConfig.supportsSending: Boolean
    get() = when (this) {
        is EvmChainConfig, is UtxoChainConfig, is TronChainConfig -> true
        else -> false
    }

/** 把公共字段复制到新对象上的便捷工具（编辑链配置、启用/停用时都用它）。 */
fun ChainConfig.commonCopy(
    id: String = this.id,
    name: String = this.name,
    symbol: String = this.symbol,
    decimals: Int = this.decimals,
    enabled: Boolean = this.enabled,
    testnet: Boolean = this.testnet,
    explorerTxUrl: String? = this.explorerTxUrl,
    explorerAddressUrl: String? = this.explorerAddressUrl,
    iconUrl: String? = this.iconUrl,
    rpcUrls: List<String> = this.rpcUrls,
    coinType: Int = this.coinType,
): ChainConfig = when (this) {
    is EvmChainConfig -> copy(
        id = id, name = name, symbol = symbol, decimals = decimals, enabled = enabled,
        testnet = testnet, explorerTxUrl = explorerTxUrl, explorerAddressUrl = explorerAddressUrl,
        iconUrl = iconUrl, rpcUrls = rpcUrls,
    )

    is UtxoChainConfig -> copy(
        id = id, name = name, symbol = symbol, decimals = decimals, enabled = enabled,
        testnet = testnet, explorerTxUrl = explorerTxUrl, explorerAddressUrl = explorerAddressUrl,
        iconUrl = iconUrl, rpcUrls = rpcUrls, coinType = coinType,
    )

    is TronChainConfig -> copy(
        id = id, name = name, symbol = symbol, decimals = decimals, enabled = enabled,
        testnet = testnet, explorerTxUrl = explorerTxUrl, explorerAddressUrl = explorerAddressUrl,
        iconUrl = iconUrl, rpcUrls = rpcUrls,
    )

    is Ed25519ChainConfig -> copy(
        id = id, name = name, symbol = symbol, decimals = decimals, enabled = enabled,
        testnet = testnet, explorerTxUrl = explorerTxUrl, explorerAddressUrl = explorerAddressUrl,
        iconUrl = iconUrl, rpcUrls = rpcUrls, coinType = coinType,
    )

    is FilecoinChainConfig -> copy(
        id = id, name = name, symbol = symbol, decimals = decimals, enabled = enabled,
        testnet = testnet, explorerTxUrl = explorerTxUrl, explorerAddressUrl = explorerAddressUrl,
        iconUrl = iconUrl, rpcUrls = rpcUrls, coinType = coinType,
    )

    is ChiaChainConfig -> copy(
        id = id, name = name, symbol = symbol, decimals = decimals, enabled = enabled,
        testnet = testnet, explorerTxUrl = explorerTxUrl, explorerAddressUrl = explorerAddressUrl,
        iconUrl = iconUrl, rpcUrls = rpcUrls, coinType = coinType,
    )
}

/**
 * EVM 系链（以太坊及所有兼容链）。
 *
 * 新增一条链 = 在 `chains.json` 里加一段。**地址派生天然复用**：所有 EVM 链都用
 * `m/44'/60'/0'/0/i`，因此同一助记词在所有 EVM 链上地址完全相同，
 * 加链不需要任何新的密钥推导逻辑。
 */
@Serializable
@SerialName("evm")
data class EvmChainConfig(
    override val id: String,
    override val name: String,
    override val symbol: String,
    val chainId: Long,
    override val rpcUrls: List<String>,
    override val decimals: Int = 18,
    override val coinType: Int = 60,
    override val enabled: Boolean = true,
    override val testnet: Boolean = false,
    override val explorerTxUrl: String? = null,
    override val explorerAddressUrl: String? = null,
    override val iconUrl: String? = null,
    /** 是否支持 EIP-1559 费用市场（决定用 type 0x2 还是 legacy 交易）。 */
    val supportsEip1559: Boolean = true,
    /** 建议的优先费（Gwei）。为空时由钱包在报价时动态估算。 */
    val suggestedPriorityFeeGwei: Double? = null,
) : ChainConfig()

/**
 * UTXO 系链（比特币、莱特币、达世币等）。
 *
 * `coinType` 与三个版本字节决定地址形态，因此同一条链可以配出不同的地址族。
 */
@Serializable
@SerialName("utxo")
data class UtxoChainConfig(
    override val id: String,
    override val name: String,
    override val symbol: String,
    override val coinType: Int,
    override val rpcUrls: List<String>,
    override val decimals: Int = 8,
    override val enabled: Boolean = true,
    override val testnet: Boolean = false,
    override val explorerTxUrl: String? = null,
    override val explorerAddressUrl: String? = null,
    override val iconUrl: String? = null,
    /** 默认地址类型。 */
    val addressType: UtxoAddressType = UtxoAddressType.NATIVE_SEGWIT,
    /** bech32 前缀，如 `bc` / `ltc`；地址类型非 SegWit 时可为空。 */
    val hrp: String? = null,
    /** P2PKH 的 Base58 版本字节。 */
    val p2pkhVersion: Int = 0,
    /** P2SH 的 Base58 版本字节。 */
    val p2shVersion: Int = 5,
    /** 单笔交易的最小找零阈值（聪），低于该值并入矿工费。 */
    val dustThreshold: Long = 546,
    /** 后端 API 形态。 */
    val api: UtxoApiKind = UtxoApiKind.ESPLORA,
    /** 建议每字节费率（聪/vB），为空时由钱包估算。 */
    val suggestedFeePerVByte: Long? = null,
) : ChainConfig()

/** 波场。 */
@Serializable
@SerialName("tron")
data class TronChainConfig(
    override val id: String,
    override val name: String,
    override val symbol: String,
    override val rpcUrls: List<String>,
    override val decimals: Int = 6,
    override val coinType: Int = 195,
    override val enabled: Boolean = true,
    override val testnet: Boolean = false,
    override val explorerTxUrl: String? = null,
    override val explorerAddressUrl: String? = null,
    override val iconUrl: String? = null,
) : ChainConfig()

/**
 * Ed25519 系链（Solana / ICP / Monero）。
 *
 * 三者共用同一条曲线、同一套 SLIP-0010 派生，差别只在**地址编码**与路径，
 * 因此收敛成一个配置类型 + [Ed25519Variant]，而不是三个几乎一样的类。
 */
@Serializable
@SerialName("ed25519")
data class Ed25519ChainConfig(
    override val id: String,
    override val name: String,
    override val symbol: String,
    override val coinType: Int,
    override val rpcUrls: List<String>,
    /** 地址编码方式。 */
    val variant: Ed25519Variant,
    /** 派生算法。Solana/Monero 用 SLIP-0010，ICP 用 BIP-32（dfx 的约定）。 */
    val derivation: Ed25519Derivation = Ed25519Derivation.SLIP10,
    /** 派生路径（SLIP-0010 下所有层必须是硬化）。 */
    val path: String,
    override val decimals: Int = 9,
    override val enabled: Boolean = true,
    override val testnet: Boolean = false,
    override val explorerTxUrl: String? = null,
    override val explorerAddressUrl: String? = null,
    override val iconUrl: String? = null,
    /** Monero 等需要网络前缀字节的链：主网标准地址前缀。 */
    val addressPrefix: Int? = null,
    /** 账户级公钥（观察钱包用），一般用不到，留作扩展。 */
    val accountXpubVersion: Int? = null,
) : ChainConfig()

/** Ed25519 系链的地址编码方式。 */
enum class Ed25519Variant {
    /** Solana：地址 = Base58(32 字节 ed25519 公钥)。 */
    SOLANA,

    /** ICP：地址 = 主体标识（principal）的文本形式。 */
    ICP,

    /** Monero：地址 = 网络前缀 + 花费公钥 + 查看公钥 + Keccak 校验和。 */
    MONERO,
}

/**
 * Ed25519 系链的派生算法。
 *
 * 两者都会产出一支 32 字节、随后被当作 ed25519 **种子**使用；区别在中间过程：
 * - [SLIP10]：HMAC key 为 `"ed25519 seed"`，只允许硬化层。Solana / Monero 采用。
 * - [BIP32]：标准的 secp256k1 BIP-32（HMAC key 为 `"Bitcoin seed"`），
 *   允许非硬化层。ICP 的官方工具 `dfx` 采用这一套。
 */
enum class Ed25519Derivation {
    SLIP10,
    BIP32,
}

/**
 * Filecoin。
 *
 * 地址是 `f`/`t` + 协议字节 + Base32（小写、无填充）。
 * 本钱包实现 `f1`（secp256k1 地址，20 字节负载 + Blake2b 校验和）；
 * `f3`（BLS）需要 BLS12-381，本版本不生成 —— 但**能识别并展示**已有地址。
 */
@Serializable
@SerialName("filecoin")
data class FilecoinChainConfig(
    override val id: String,
    override val name: String,
    override val symbol: String,
    override val coinType: Int = 461,
    override val rpcUrls: List<String>,
    /** 派生路径，标准为 `m/44'/461'/0'/0/0`。 */
    val path: String = "m/44'/461'/0'/0/0",
    /** 地址协议号：1 = secp256k1（f1），3 = BLS（f3）。 */
    val addressPrefix: Int = 1,
    /** 网络前缀：`f` 主网 / `t` 测试网。 */
    val network: String = "f",
    override val decimals: Int = 18,
    override val enabled: Boolean = true,
    override val testnet: Boolean = false,
    override val explorerTxUrl: String? = null,
    override val explorerAddressUrl: String? = null,
    override val iconUrl: String? = null,
) : ChainConfig()

/**
 * Chia。
 *
 * 密钥是 BLS12-381（不是 secp256k1 / ed25519），派生路径 `m/12381/8444/2/0`，
 * 地址是 `xch1…`（bech32m 编码的 puzzle hash）。
 */
@Serializable
@SerialName("chia")
data class ChiaChainConfig(
    override val id: String,
    override val name: String,
    override val symbol: String,
    override val coinType: Int = 8444,
    override val rpcUrls: List<String>,
    /** 标准钱包路径：`m/12381/8444/2/0`（前两级是 BLS 专用 purpose 与币种）。 */
    val path: String = "m/12381/8444/2/0",
    /** bech32m 前缀：`xch` 主网 / `txch` 测试网。 */
    val hrp: String = "xch",
    override val decimals: Int = 12,
    override val enabled: Boolean = true,
    override val testnet: Boolean = false,
    override val explorerTxUrl: String? = null,
    override val explorerAddressUrl: String? = null,
    override val iconUrl: String? = null,
) : ChainConfig()

/** UTXO 链的地址类型。 */
enum class UtxoAddressType {
    /** 传统 P2PKH（`1...`），即 BIP-44。 */
    LEGACY,

    /** 嵌套 SegWit，P2SH-P2WPKH（`3...`），即 BIP-49。 */
    NESTED_SEGWIT,

    /** 原生 SegWit，P2WPKH（`bc1q...`），即 BIP-84（默认）。 */
    NATIVE_SEGWIT,

    /** Taproot，P2TR（`bc1p...`），即 BIP-86。 */
    TAPROOT;

    /** 该地址类型对应的 BIP-44 purpose。 */
    val purpose: Int
        get() = when (this) {
            LEGACY -> 44
            NESTED_SEGWIT -> 49
            NATIVE_SEGWIT -> 84
            TAPROOT -> 86
        }
}

/** UTXO 链的后端接口形态。 */
enum class UtxoApiKind {
    /** Esplora 兼容 API（blockstream.info / mempool.space / litecoinspace 等）。 */
    ESPLORA,
}

/**
 * 用户对本机链清单的覆盖。
 *
 * 只需要一个列表：**「新增」与「修改」是同一件事** —— 都是「按 id 覆盖」。
 * 用户的覆盖优先级最高，且只存在于本机，不会上传到任何地方。
 *
 * 删除/停用同理：对用户自建的链是真正移除覆盖项；对内置/远端链则写入
 * 一条 `enabled = false` 的覆盖（否则下次刷新远端它又回来了）。
 */
@Serializable
data class UserChainOverrides(
    val version: Int = 1,
    /** 用户新增或修改过的链配置；按 id 覆盖内置/远端同名项。 */
    val modified: List<ChainConfig> = emptyList(),
)
