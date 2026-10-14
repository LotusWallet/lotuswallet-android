package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Secp256k1
import com.lotuswallet.core.network.chain.UtxoAddressType
import com.lotuswallet.core.network.chain.UtxoChainConfig

/**
 * 比特币系密钥与地址派生。
 *
 * 路径按 BIP-44 系列规范随地址类型变化：
 * - P2PKH → `m/44'/coin'/account'/0/index`
 * - P2SH-P2WPKH → `m/49'/coin'/account'/0/index`
 * - P2WPKH → `m/84'/coin'/account'/0/index`（默认）
 * - P2TR → `m/86'/coin'/account'/0/index`
 *
 * 因此**同一条链切换地址类型时地址会变** —— 与 EVM 不同。
 * 钱包在首次创建账户时就把地址类型固定下来并记录在 `chainId` 上（如 `bitcoin#p2wpkh`），
 * 避免用户误以为「资产丢了」。
 */
object BitcoinDerivation {

    fun path(
        addressType: UtxoAddressType,
        coinType: Int,
        account: Int = 0,
        index: Int = 0,
        change: Int = 0,
    ): String = "m/${addressType.purpose}'/$coinType'/$account'/$change/$index"

    /** 用于展示/记录的链内账户标识：`bitcoin` + 地址类型。 */
    fun variantChainId(chainId: String, addressType: UtxoAddressType): String =
        "$chainId#${addressType.name.lowercase()}"

    fun privateKeyOf(
        master: Bip32.ExtendedPrivateKey,
        config: UtxoChainConfig,
        index: Int = 0,
        account: Int = 0,
        change: Int = 0,
    ): ByteArray = master.derive(path(config.addressType, config.coinType, account, index, change)).privateKey

    fun publicKeyOf(
        master: Bip32.ExtendedPrivateKey,
        config: UtxoChainConfig,
        index: Int = 0,
        account: Int = 0,
        change: Int = 0,
    ): ByteArray = Secp256k1.publicKeyCompressed(privateKeyOf(master, config, index, account, change))

    fun addressOf(
        master: Bip32.ExtendedPrivateKey,
        config: UtxoChainConfig,
        index: Int = 0,
        account: Int = 0,
        change: Int = 0,
    ): String {
        val privateKey = privateKeyOf(master, config, index, account, change)
        return BitcoinAddress.encode(
            pubKeyCompressed = Secp256k1.publicKeyCompressed(privateKey),
            privateKey = privateKey,
            type = config.addressType,
            config = config,
        )
    }

    /** 找零地址固定使用 change = 1，与外部地址分离（BIP-44 的隐私要求）。 */
    fun changeAddressOf(
        master: Bip32.ExtendedPrivateKey,
        config: UtxoChainConfig,
        index: Int = 0,
        account: Int = 0,
    ): String = addressOf(master, config, index = index, account = account, change = 1)

    /** 观察钱包用：账户级 xpub（`m/84'/coin'/account'`）。 */
    fun accountXpub(
        master: Bip32.ExtendedPrivateKey,
        config: UtxoChainConfig,
        account: Int = 0,
    ): String = master.derive("m/${config.addressType.purpose}'/${config.coinType}'/$account'").serializePublic()
}
