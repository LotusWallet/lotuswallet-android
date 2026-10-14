package com.lotuswallet.app.data

import com.lotuswallet.chain.alt.ChiaAddress
import com.lotuswallet.chain.alt.FilecoinAddress
import com.lotuswallet.chain.alt.IcpAddress
import com.lotuswallet.chain.alt.MoneroAddress
import com.lotuswallet.chain.alt.SolanaAddress
import com.lotuswallet.chain.bitcoin.BitcoinAddress
import com.lotuswallet.chain.bitcoin.BitcoinDerivation
import com.lotuswallet.chain.evm.EvmDerivation
import com.lotuswallet.chain.evm.EvmAddress
import com.lotuswallet.chain.tron.TronAddress
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Bip39
import com.lotuswallet.core.crypto.Ed25519
import com.lotuswallet.core.crypto.Secp256k1
import com.lotuswallet.core.crypto.Slip10
import com.lotuswallet.core.database.entity.WalletSecret
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChiaChainConfig
import com.lotuswallet.core.network.chain.Ed25519ChainConfig
import com.lotuswallet.core.network.chain.Ed25519Derivation
import com.lotuswallet.core.network.chain.Ed25519Variant
import com.lotuswallet.core.network.chain.EvmChainConfig
import com.lotuswallet.core.network.chain.FilecoinChainConfig
import com.lotuswallet.core.network.chain.TronChainConfig
import com.lotuswallet.core.network.chain.UtxoChainConfig

/**
 * 地址派生 —— 纯函数，没有 IO，便于单测。
 *
 * ## 为什么集中在一个地方
 * 旧版把「链 → 派生规则」的分支散落在多个 Presenter 里，
 * 结果是新增一条链要改好几处，而且很容易漏掉某一处的地址校验。
 * 这里把「链家族 → 派生算法」收敛成一个 `when`，新增 EVM 链完全不需要动它
 * （因为所有 EVM 链共用 `m/44'/60'/0'/0/i`，地址在任何 EVM 链上都一样）。
 *
 * ## 两类曲线
 * - **secp256k1**：BTC 系 / EVM / TRON / Filecoin。地址由 secp256k1 公钥编码而来。
 * - **ed25519**：Solana / ICP / Monero。私钥是 32 字节「种子」或「标量」，
 *   走 SLIP-0010 或 BIP-32 派生，公钥是不压缩的 32 字节点。
 *
 * Chia 使用 BLS12-381，本版本尚未实现派生（只支持观察地址）。
 */
object AccountDeriver {

    /** 派生结果。 */
    data class Derived(
        val address: String,
        val derivationPath: String,
        val publicKeyHex: String?,
    )

    /**
     * 由钱包密钥材料派生出某条链上第 [index] 个地址。
     *
     * @throws AppException 观察钱包无法派生（只能由用户提供地址）
     */
    fun derive(
        walletType: WalletType,
        secret: WalletSecret,
        chain: ChainConfig,
        index: Int = 0,
    ): Derived = when (walletType) {
        WalletType.MNEMONIC -> deriveFromMnemonic(secret, chain, index)
        WalletType.PRIVATE_KEY -> deriveFromPrivateKey(secret, chain, index)
        WalletType.WATCH_ONLY -> throw AppException(
            AppError.InvalidInput("观察钱包不能派生新地址"),
        )
    }

    /**
     * 由钱包密钥材料派生出某条链上第 [index] 个地址的**私钥**（签名用）。
     *
     * 与 [derive] 分开是因为：地址随便看，私钥只有在用户点了「发送 / 导出」时才需要，
     * 两个入口分开能避免「只想要地址却顺手把私钥也取出来了」。
     */
    fun privateKey(
        walletType: WalletType,
        secret: WalletSecret,
        chain: ChainConfig,
        index: Int = 0,
    ): ByteArray = when (walletType) {
        WalletType.MNEMONIC -> {
            val mnemonic = secret.mnemonic
                ?: throw AppException(AppError.InvalidInput("钱包缺少助记词"))
            val master = Bip32.fromMnemonic(mnemonic, secret.passphrase.orEmpty())
            when (chain) {
                is EvmChainConfig -> EvmDerivation.privateKeyOf(master, index)
                is UtxoChainConfig -> BitcoinDerivation.privateKeyOf(master, chain, index)
                is TronChainConfig -> TronAddress.privateKeyOf(master, index)
                is FilecoinChainConfig -> master.derive(pathFor(chain, index)).privateKey
                else -> throw AppException(
                    AppError.InvalidInput("${chain.name} 暂不支持在 App 内签名，因此不导出私钥"),
                )
            }
        }

        WalletType.PRIVATE_KEY -> {
            require(index == 0) { "单私钥钱包只有第 0 个地址" }
            val hex = secret.privateKeyHex
                ?: throw AppException(AppError.InvalidInput("钱包缺少私钥"))
            Bytes.fromHex(hex).also {
                if (it.size != 32) throw AppException(AppError.InvalidInput("私钥必须是 32 字节"))
            }
        }

        WalletType.WATCH_ONLY -> throw AppException(AppError.InvalidInput("观察钱包没有私钥，无法签名"))
    }

    /**
     * UTXO 链的找零地址：固定走 `change = 1` 路径（BIP-44 的隐私要求 ——
     * 找零地址与收款地址混用会让第三方轻松关联出一整条资金链）。
     */
    fun changeAddress(secret: WalletSecret, chain: UtxoChainConfig, account: Int = 0, index: Int = 0): String {
        val mnemonic = secret.mnemonic
            ?: throw AppException(AppError.InvalidInput("观察钱包或单私钥钱包无法生成找零地址"))
        val master = Bip32.fromMnemonic(mnemonic, secret.passphrase.orEmpty())
        return BitcoinDerivation.changeAddressOf(master, chain, index = index, account = account)
    }

    /** 该链上第 [index] 个账户的派生路径（用于展示与记录）。 */
    fun pathFor(chain: ChainConfig, index: Int): String = when (chain) {
        is EvmChainConfig -> EvmDerivation.path(index)
        is UtxoChainConfig -> BitcoinDerivation.path(chain.addressType, chain.coinType, index = index)
        is TronChainConfig -> TronAddress.path(index)
        is Ed25519ChainConfig -> if (index == 0) chain.path else ed25519Path(chain, index)
        is FilecoinChainConfig -> if (index == 0) chain.path else FilecoinAddress.pathFor(index, chain.coinType)
        is ChiaChainConfig -> chain.path
    }

    /** ed25519 系的账户级路径：只有第一条用配置里的默认路径，其余按币种编号生成。 */
    private fun ed25519Path(chain: Ed25519ChainConfig, index: Int): String = when (chain.variant) {
        Ed25519Variant.SOLANA -> SolanaAddress.pathFor(index, chain.coinType)
        Ed25519Variant.ICP -> IcpAddress.pathFor(index, chain.coinType)
        Ed25519Variant.MONERO -> MoneroAddress.pathFor(index, chain.coinType)
    }

    private fun deriveFromMnemonic(secret: WalletSecret, chain: ChainConfig, index: Int): Derived {
        val mnemonic = secret.mnemonic
            ?: throw AppException(AppError.InvalidInput("钱包缺少助记词"))
        val seed = Bip39.mnemonicToSeed(mnemonic, secret.passphrase.orEmpty())

        return when (chain) {
            is EvmChainConfig -> {
                val master = Bip32.fromSeed(seed)
                val privateKey = EvmDerivation.privateKeyOf(master, index)
                Derived(
                    address = EvmDerivation.addressOf(master, index),
                    derivationPath = EvmDerivation.path(index),
                    publicKeyHex = Bytes.toHex(Secp256k1.publicKeyCompressed(privateKey)),
                )
            }

            is UtxoChainConfig -> {
                val master = Bip32.fromSeed(seed)
                val privateKey = BitcoinDerivation.privateKeyOf(master, chain, index)
                Derived(
                    address = BitcoinDerivation.addressOf(master, chain, index),
                    derivationPath = BitcoinDerivation.path(chain.addressType, chain.coinType, index = index),
                    publicKeyHex = Bytes.toHex(Secp256k1.publicKeyCompressed(privateKey)),
                )
            }

            is TronChainConfig -> {
                val master = Bip32.fromSeed(seed)
                val privateKey = TronAddress.privateKeyOf(master, index)
                Derived(
                    address = TronAddress.addressOf(master, index),
                    derivationPath = TronAddress.path(index),
                    publicKeyHex = Bytes.toHex(Secp256k1.publicKeyCompressed(privateKey)),
                )
            }

            is Ed25519ChainConfig -> {
                val path = pathFor(chain, index)
                when (chain.variant) {
                    Ed25519Variant.SOLANA -> Derived(
                        address = SolanaAddress.address(seed, path),
                        derivationPath = path,
                        publicKeyHex = Bytes.toHex(
                            Ed25519.publicKey(
                                com.lotuswallet.chain.alt.AltKeyDerivation.keyMaterial(
                                    seed, path, Ed25519Derivation.SLIP10,
                                ),
                            ),
                        ),
                    )

                    Ed25519Variant.ICP -> Derived(
                        address = IcpAddress.address(seed, path),
                        derivationPath = path,
                        publicKeyHex = Bytes.toHex(
                            Ed25519.publicKey(
                                com.lotuswallet.chain.alt.AltKeyDerivation.keyMaterial(
                                    seed, path, Ed25519Derivation.BIP32,
                                ),
                            ),
                        ),
                    )

                    Ed25519Variant.MONERO -> {
                        // Monero 的地址由「花费公钥 + 查看公钥」两把钥匙组成，
                        // 因此公钥字段记录花费公钥，与其它链保持同样的语义。
                        val material = com.lotuswallet.chain.alt.AltKeyDerivation.keyMaterial(
                            seed, path, Ed25519Derivation.SLIP10,
                        )
                        val spendSecret = com.lotuswallet.core.crypto.Ed25519Group.scReduce32(material)
                        Derived(
                            address = MoneroAddress.address(seed, path, chain.addressPrefix ?: MoneroAddress.MAINNET_PREFIX),
                            derivationPath = path,
                            publicKeyHex = Bytes.toHex(
                                com.lotuswallet.core.crypto.Ed25519Group.scalarMultBase(spendSecret),
                            ),
                        )
                    }
                }
            }

            is FilecoinChainConfig -> {
                val path = pathFor(chain, index)
                val privateKey = Bip32.fromSeed(seed).derive(path).privateKey
                Derived(
                    address = FilecoinAddress.address(seed, path, chain.network),
                    derivationPath = path,
                    publicKeyHex = Bytes.toHex(Secp256k1.publicKeyUncompressed(privateKey)),
                )
            }

            is ChiaChainConfig -> throw AppException(
                AppError.InvalidInput("Chia 暂不支持助记词派生，请以「观察地址」导入 xch1 地址"),
            )
        }
    }

    private fun deriveFromPrivateKey(secret: WalletSecret, chain: ChainConfig, index: Int): Derived {
        require(index == 0) { "单私钥钱包只有第 0 个地址" }
        val hex = secret.privateKeyHex
            ?: throw AppException(AppError.InvalidInput("钱包缺少私钥"))
        val privateKey = Bytes.fromHex(hex)
        if (privateKey.size != 32) {
            throw AppException(AppError.InvalidInput("私钥必须是 32 字节"))
        }

        return when (chain) {
            is EvmChainConfig -> Derived(
                address = EvmAddress.fromPrivateKey(privateKey),
                derivationPath = "导入私钥（无派生路径）",
                publicKeyHex = Bytes.toHex(Secp256k1.publicKeyCompressed(privateKey)),
            )

            is UtxoChainConfig -> {
                val pubKey = Secp256k1.publicKeyCompressed(privateKey)
                Derived(
                    address = BitcoinAddress.encode(
                        pubKeyCompressed = pubKey,
                        privateKey = privateKey,
                        type = chain.addressType,
                        config = chain,
                    ),
                    derivationPath = "导入私钥（无派生路径）",
                    publicKeyHex = Bytes.toHex(pubKey),
                )
            }

            is TronChainConfig -> Derived(
                address = TronAddress.fromPrivateKey(privateKey),
                derivationPath = "导入私钥（无派生路径）",
                publicKeyHex = Bytes.toHex(Secp256k1.publicKeyCompressed(privateKey)),
            )

            is FilecoinChainConfig -> {
                val payload = com.lotuswallet.core.crypto.Hash.blake2b(
                    Secp256k1.publicKeyUncompressed(privateKey), 20,
                )
                Derived(
                    address = FilecoinAddress.encode(chain.network, FilecoinAddress.PROTOCOL_SECP256K1, payload),
                    derivationPath = "导入私钥（无派生路径）",
                    publicKeyHex = Bytes.toHex(Secp256k1.publicKeyUncompressed(privateKey)),
                )
            }

            is Ed25519ChainConfig, is ChiaChainConfig -> throw AppException(
                AppError.InvalidInput(
                    "${chain.name} 使用的不是 secp256k1 私钥，无法用 32 字节私钥导入。" +
                        "请改用助记词导入。",
                ),
            )
        }
    }

    /** 便于 UI 做即时校验：这个地址像不像该链的地址。 */
    fun isPlausibleAddress(chain: ChainConfig, address: String): Boolean = when (chain) {
        is EvmChainConfig -> EvmAddress.isValid(address)
        is UtxoChainConfig -> BitcoinAddress.isProbablyValid(address, chain)
        is TronChainConfig -> TronAddress.isValid(address)
        is Ed25519ChainConfig -> when (chain.variant) {
            Ed25519Variant.SOLANA -> SolanaAddress.isValid(address)
            Ed25519Variant.ICP -> IcpAddress.isValid(address)
            Ed25519Variant.MONERO -> MoneroAddress.isValid(address, chain.addressPrefix ?: MoneroAddress.MAINNET_PREFIX)
        }

        is FilecoinChainConfig -> FilecoinAddress.isValid(address, chain.network)
        is ChiaChainConfig -> ChiaAddress.isValid(address, chain.hrp)
    }

    /** SLIP-0010 的调试辅助（测试用）。 */
    internal fun slip10Key(seed: ByteArray, path: String): ByteArray = Slip10.derive(seed, path).key
}
