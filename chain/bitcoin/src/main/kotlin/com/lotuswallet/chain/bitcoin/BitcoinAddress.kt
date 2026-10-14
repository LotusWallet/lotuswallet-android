package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Base58
import com.lotuswallet.core.crypto.Bech32
import com.lotuswallet.core.crypto.Secp256k1
import com.lotuswallet.core.network.chain.UtxoAddressType
import com.lotuswallet.core.network.chain.UtxoChainConfig

/**
 * 比特币（及兼容链）地址编解码。
 *
 * 支持四种标准地址类型：
 * | 类型 | 前缀 | BIP | 说明 |
 * |---|---|---|---|
 * | P2PKH | `1...` | BIP-44 | 传统地址，体积最大 |
 * | P2SH-P2WPKH | `3...` | BIP-49 | 嵌套隔离见证，兼容性最好的省费方案 |
 * | P2WPKH | `bc1q...` | BIP-84 | 原生隔离见证，**默认** |
 * | P2TR | `bc1p...` | BIP-86 | Taproot，体积最小、隐私最好 |
 */
object BitcoinAddress {

    /** P2PKH 地址。 */
    fun p2pkh(pubKeyCompressed: ByteArray, version: Int): String =
        Base58.encodeChecked(byteArrayOf(version.toByte()) + Script.pubKeyHash160(pubKeyCompressed))

    /** P2SH-P2WPKH 地址（redeemScript 为 P2WPKH 脚本）。 */
    fun p2shP2wpkh(pubKeyCompressed: ByteArray, version: Int): String {
        val redeemScript = Script.p2shP2wpkhRedeemScript(Script.pubKeyHash160(pubKeyCompressed))
        return Base58.encodeChecked(byteArrayOf(version.toByte()) + Script.scriptHash160(redeemScript))
    }

    /** P2WPKH 地址。 */
    fun p2wpkh(pubKeyCompressed: ByteArray, hrp: String): String =
        Bech32.encodeSegwit(hrp, 0, Script.pubKeyHash160(pubKeyCompressed))

    /** P2TR 地址（由输出键编码）。 */
    fun p2tr(outputKey: ByteArray, hrp: String): String =
        Bech32.encodeSegwit(hrp, 1, outputKey)

    /**
     * 按链配置与地址类型给出地址。
     *
     * @param privateKey 仅 Taproot 需要（要计算调整后的输出键）
     */
    fun encode(
        pubKeyCompressed: ByteArray,
        privateKey: ByteArray,
        type: UtxoAddressType,
        config: UtxoChainConfig,
    ): String = when (type) {
        UtxoAddressType.LEGACY -> p2pkh(pubKeyCompressed, config.p2pkhVersion)
        UtxoAddressType.NESTED_SEGWIT -> p2shP2wpkh(pubKeyCompressed, config.p2shVersion)
        UtxoAddressType.NATIVE_SEGWIT -> p2wpkh(pubKeyCompressed, requireHrp(config))
        UtxoAddressType.TAPROOT -> p2tr(Taproot.outputKey(privateKey), requireHrp(config))
    }

    /**
     * 由地址反解出 scriptPubKey。
     *
     * 用于「转账到任意地址」与「由地址计算找零脚本」，两个场景都必须精确。
     * 无法识别时返回 null（而不是抛异常），便于上层给出友好提示。
     */
    fun scriptPubKeyFor(address: String, config: UtxoChainConfig): ByteArray? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null

        // Base58 形态：P2PKH / P2SH
        if (trimmed[0] == '1' || trimmed[0] == '2' || trimmed[0] == '3' || trimmed[0] == 'm' ||
            trimmed[0] == 'n' || trimmed[0] == 'L' || trimmed[0] == 'M'
        ) {
            val payload = runCatching { Base58.decodeChecked(trimmed) }.getOrNull() ?: return null
            if (payload.size != 21) return null
            val version = payload[0].toInt() and 0xFF
            val hash = payload.copyOfRange(1, 21)
            return when (version) {
                config.p2pkhVersion -> Script.p2pkhScriptPubKey(hash)
                config.p2shVersion -> Script.p2shScriptPubKey(hash)
                else -> null
            }
        }

        // bech32 / bech32m：SegWit
        val decoded = runCatching { Bech32.decode(trimmed) }.getOrNull() ?: return null
        val expectedHrp = config.hrp ?: return null
        if (decoded.hrp != expectedHrp) return null
        if (decoded.data.isEmpty()) return null

        val version = decoded.data[0].toInt() and 0x1F
        val program = runCatching {
            Bech32.convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false)
        }.getOrNull() ?: return null

        return when {
            version == 0 && program.size == 20 && decoded.variant == Bech32.Variant.BECH32 ->
                Script.p2wpkhScriptPubKey(program)

            version == 0 && program.size == 32 && decoded.variant == Bech32.Variant.BECH32 ->
                // P2WSH：钱包不主动生成，但应当能识别并转账进去
                byteArrayOf(Script.OP_0, 0x20) + program

            version == 1 && program.size == 32 && decoded.variant == Bech32.Variant.BECH32M ->
                Script.p2trScriptPubKey(program)

            else -> null
        }
    }

    /** 粗略判断地址是否属于该链（用于转账前的即时校验）。 */
    fun isProbablyValid(address: String, config: UtxoChainConfig): Boolean =
        scriptPubKeyFor(address, config) != null

    private fun requireHrp(config: UtxoChainConfig): String =
        config.hrp ?: throw IllegalArgumentException("链 ${config.id} 未配置 bech32 前缀（hrp）")

    /** 便于展示：由压缩公钥算出各类型地址，供「地址类型切换」预览。 */
    fun previewAll(pubKeyCompressed: ByteArray, privateKey: ByteArray, config: UtxoChainConfig): Map<UtxoAddressType, String> =
        UtxoAddressType.entries.associateWith { type ->
            if (type == UtxoAddressType.TAPROOT && config.hrp == null) "" else encode(pubKeyCompressed, privateKey, type, config)
        }

    internal fun hash160Hex(pubKeyCompressed: ByteArray): String =
        Bytes.toHex(Script.pubKeyHash160(pubKeyCompressed))

    internal fun compressedPublicKey(privateKey: ByteArray): ByteArray =
        Secp256k1.publicKeyCompressed(privateKey)
}
