package com.lotuswallet.chain.bitcoin

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Secp256k1
import com.lotuswallet.core.network.chain.UtxoAddressType
import com.lotuswallet.core.network.chain.UtxoChainConfig
import org.junit.Test

class BitcoinAddressTest {

    /** TREZOR / BIP 官方测试向量统一使用的助记词。 */
    private val mnemonic = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    private val master = Bip32.fromMnemonic(mnemonic)

    private fun btc(addressType: UtxoAddressType) = UtxoChainConfig(
        id = "bitcoin",
        name = "Bitcoin",
        symbol = "BTC",
        coinType = 0,
        rpcUrls = listOf("https://mempool.space/api"),
        addressType = addressType,
        hrp = "bc",
        p2pkhVersion = 0,
        p2shVersion = 5,
    )

    @Test
    fun `BIP-44 P2PKH 官方向量`() {
        val address = BitcoinDerivation.addressOf(master, btc(UtxoAddressType.LEGACY))
        assertThat(address).isEqualTo("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA")
    }

    @Test
    fun `BIP-84 P2WPKH 官方向量`() {
        val config = btc(UtxoAddressType.NATIVE_SEGWIT)
        val privateKey = BitcoinDerivation.privateKeyOf(master, config)
        val pubKey = Secp256k1.publicKeyCompressed(privateKey)
        assertThat(Bytes.toHex(pubKey))
            .isEqualTo("0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c")
        assertThat(BitcoinDerivation.addressOf(master, config))
            .isEqualTo("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
    }

    /**
     * BIP-86 官方向量。这个用例一次性验证了：
     * 1. `m/86'/0'/0'/0/0` 的 BIP-32 派生；
     * 2. Taproot 输出键的 tweak 计算（`Q = P + t·G`）；
     * 3. bech32m 编码（BIP-350）。
     */
    @Test
    fun `BIP-86 Taproot 官方向量`() {
        val config = btc(UtxoAddressType.TAPROOT)
        val privateKey = BitcoinDerivation.privateKeyOf(master, config)
        assertThat(Bytes.toHex(privateKey))
            .isEqualTo("41f41d69260df4cf277826a9b65a3717e4eeddbeedf637f212ca096576479361")

        val internalKey = Schnorr.publicKey(privateKey)
        // 内部公钥（x-only）与压缩公钥的 x 部分一致
        assertThat(Bytes.toHex(internalKey)).isEqualTo(
            Bytes.toHex(Secp256k1.publicKeyCompressed(privateKey)).substring(2),
        )

        val outputKey = Taproot.outputKey(privateKey)
        assertThat(Bytes.toHex(outputKey))
            .isEqualTo("a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c")

        val script = Script.p2trScriptPubKey(outputKey)
        assertThat(Bytes.toHex(script)).isEqualTo(
            "5120a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c",
        )

        assertThat(BitcoinDerivation.addressOf(master, config))
            .isEqualTo("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr")
    }

    @Test
    fun `BIP-44 系列路径随地址类型变化`() {
        assertThat(BitcoinDerivation.path(UtxoAddressType.LEGACY, 0)).isEqualTo("m/44'/0'/0'/0/0")
        assertThat(BitcoinDerivation.path(UtxoAddressType.NESTED_SEGWIT, 0)).isEqualTo("m/49'/0'/0'/0/0")
        assertThat(BitcoinDerivation.path(UtxoAddressType.NATIVE_SEGWIT, 0)).isEqualTo("m/84'/0'/0'/0/0")
        assertThat(BitcoinDerivation.path(UtxoAddressType.TAPROOT, 0)).isEqualTo("m/86'/0'/0'/0/0")
        // 找零地址走 change = 1
        assertThat(BitcoinDerivation.path(UtxoAddressType.NATIVE_SEGWIT, 0, change = 1))
            .isEqualTo("m/84'/0'/0'/1/0")
    }

    @Test
    fun `切换地址类型会得到不同地址`() {
        val addresses = UtxoAddressType.entries.map { BitcoinDerivation.addressOf(master, btc(it)) }
        assertThat(addresses.toSet()).hasSize(UtxoAddressType.entries.size)
        assertThat(addresses[0]).startsWith("1")
        assertThat(addresses[1]).startsWith("3")
        assertThat(addresses[2]).startsWith("bc1q")
        assertThat(addresses[3]).startsWith("bc1p")
    }

    @Test
    fun `莱特币使用自己的前缀`() {
        val ltc = UtxoChainConfig(
            id = "litecoin",
            name = "Litecoin",
            symbol = "LTC",
            coinType = 2,
            rpcUrls = listOf("https://litecoinspace.org/api"),
            addressType = UtxoAddressType.NATIVE_SEGWIT,
            hrp = "ltc",
            p2pkhVersion = 48,
            p2shVersion = 50,
        )
        assertThat(BitcoinDerivation.addressOf(master, ltc)).startsWith("ltc1q")
    }

    @Test
    fun `地址可以反解出正确的脚本类型`() {
        val config = btc(UtxoAddressType.NATIVE_SEGWIT)
        val p2wpkh = BitcoinDerivation.addressOf(master, config)
        val script = BitcoinAddress.scriptPubKeyFor(p2wpkh, config)
        assertThat(script).isNotNull()
        assertThat(Script.isP2wpkh(script!!)).isTrue()

        val p2tr = BitcoinDerivation.addressOf(master, btc(UtxoAddressType.TAPROOT))
        val trScript = BitcoinAddress.scriptPubKeyFor(p2tr, config)
        assertThat(trScript).isNotNull()
        assertThat(Script.isP2tr(trScript!!)).isTrue()

        val p2pkh = BitcoinDerivation.addressOf(master, btc(UtxoAddressType.LEGACY))
        val p2pkhScript = BitcoinAddress.scriptPubKeyFor(p2pkh, config)
        assertThat(p2pkhScript).isEqualTo(
            Script.p2pkhScriptPubKey(Script.pubKeyHash160(Secp256k1.publicKeyCompressed(
                BitcoinDerivation.privateKeyOf(master, btc(UtxoAddressType.LEGACY)),
            ))),
        )
    }

    @Test
    fun `非法或跨链地址被拒绝`() {
        val config = btc(UtxoAddressType.NATIVE_SEGWIT)
        assertThat(BitcoinAddress.isProbablyValid("", config)).isFalse()
        assertThat(BitcoinAddress.isProbablyValid("not-an-address", config)).isFalse()
        // 以太坊地址不是比特币地址
        assertThat(BitcoinAddress.isProbablyValid("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", config)).isFalse()
        // 改一位校验和应当被发现
        val valid = BitcoinDerivation.addressOf(master, config)
        val tampered = valid.dropLast(2) + "xx"
        assertThat(BitcoinAddress.isProbablyValid(tampered, config)).isFalse()
    }

    @Test
    fun `脚本构建符合标准模板`() {
        val hash160 = ByteArray(20) { 0x11 }
        assertThat(Bytes.toHex(Script.p2pkhScriptPubKey(hash160)))
            .isEqualTo("76a914" + "11".repeat(20) + "88ac")
        assertThat(Bytes.toHex(Script.p2wpkhScriptPubKey(hash160)))
            .isEqualTo("0014" + "11".repeat(20))
        assertThat(Bytes.toHex(Script.p2shScriptPubKey(hash160)))
            .isEqualTo("a914" + "11".repeat(20) + "87")
        assertThat(Bytes.toHex(Script.p2trScriptPubKey(ByteArray(32) { 0x22 })))
            .isEqualTo("5120" + "22".repeat(32))
        // BIP-143 的 scriptCode 是「裸脚本」，长度前缀由签名原像负责添加
        assertThat(Bytes.toHex(Script.p2wpkhScriptCode(hash160)))
            .isEqualTo("76a914" + "11".repeat(20) + "88ac")
    }

    @Test
    fun `最小 push 编码`() {
        assertThat(Bytes.toHex(Script.pushData(ByteArray(0)))).isEqualTo("00")
        assertThat(Bytes.toHex(Script.pushData(byteArrayOf(0x01)))).isEqualTo("0101")
        assertThat(Bytes.toHex(Script.pushData(ByteArray(75) { 0x01 })))
            .isEqualTo("4b" + "01".repeat(75))
        assertThat(Bytes.toHex(Script.pushData(ByteArray(76) { 0x01 })).substring(0, 4))
            .isEqualTo("4c4c")
    }
}
