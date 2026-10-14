package com.lotuswallet.chain.evm

import com.lotuswallet.core.crypto.Bip32

/**
 * EVM 系密钥派生。
 *
 * **所有 EVM 链共用同一条路径与同一份实现** —— 因为地址只由私钥决定，
 * 与 chainId 无关。这正是「新增 EVM 链零代码」能够成立的根本原因：
 * 加一条链不需要任何新的派生逻辑，只需要在 `chains.json` 里加一段配置。
 */
object EvmDerivation {

    const val COIN_TYPE = 60

    /** 账户级路径 `m/44'/60'/account'`。 */
    fun accountPath(account: Int = 0): String = "m/44'/$COIN_TYPE'/$account'"

    /** 地址级路径 `m/44'/60'/account'/0/index`。 */
    fun path(index: Int, account: Int = 0): String = "m/44'/$COIN_TYPE'/$account'/0/$index"

    /** 派生地址对应的私钥。 */
    fun privateKeyOf(master: Bip32.ExtendedPrivateKey, index: Int, account: Int = 0): ByteArray =
        master.derive(path(index, account)).privateKey

    /** 派生地址（EIP-55 校验和）。 */
    fun addressOf(master: Bip32.ExtendedPrivateKey, index: Int, account: Int = 0): String =
        EvmAddress.fromPrivateKey(privateKeyOf(master, index, account))

    /** 由私钥直接得到地址。 */
    fun addressOf(privateKey: ByteArray): String = EvmAddress.fromPrivateKey(privateKey)

    /** 账户级 xpub，用于「观察钱包」只读导入。 */
    fun accountXpub(master: Bip32.ExtendedPrivateKey, account: Int = 0): String =
        master.derive(accountPath(account)).serializePublic()
}
