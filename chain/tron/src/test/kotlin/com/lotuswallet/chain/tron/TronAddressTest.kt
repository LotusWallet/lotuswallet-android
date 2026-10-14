package com.lotuswallet.chain.tron

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1
import org.junit.Test

class TronAddressTest {

    private val mnemonic = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    private val master = Bip32.fromMnemonic(mnemonic)

    /**
     * 官方测试向量（TRON Ledger 应用与 TronWeb 生态通用）：
     * 一次性验证 `m/44'/195'/0'/0/0` 的派生、keccak256 地址计算与 Base58Check 编码。
     */
    @Test
    fun `官方测试助记词的 TRON 地址向量`() {
        val privateKey = TronAddress.privateKeyOf(master, index = 0)
        assertThat(Bytes.toHex(privateKey))
            .isEqualTo("b5a4cea271ff424d7c31dc12a3e43e401df7a40d7412a15750f3f0b6b5449a28")

        val uncompressed = Secp256k1.publicKeyUncompressed(privateKey)
        assertThat(Bytes.toHex(uncompressed)).isEqualTo(
            "04ff21f8e64d3a3c0198edfbb7afdc79be959432e92e2f8a1984bb436a414b8edc" +
                "ec0345aad0c1bf7da04fd036dd7f9f617e30669224283d950fab9dd84831dc83",
        )

        assertThat(TronAddress.toHex41(TronAddress.fromPrivateKey(privateKey)))
            .isEqualTo("41c8599111f29c1e1e061265b4af93ea1f274ad78a")

        assertThat(TronAddress.addressOf(master, index = 0))
            .isEqualTo("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH")
    }

    /**
     * 跨链不变量：同一个私钥在 TRON 与以太坊上的**地址主体（20 字节）完全相同**，
     * 因为两者都使用 `keccak256(uncompressedPubKey[1..])[12..32]`。
     *
     * 这比任何单链向量都更能说明地址计算实现是正确的 —— 只要有一处（例如误用了
     * SHA3-256 而不是 Keccak-256）出错，这个等式就不成立。
     */
    @Test
    fun `同一私钥在 TRON 与以太坊上的地址主体一致`() {
        val privateKey = TronAddress.privateKeyOf(master, index = 0)
        val tronBody = TronAddress.toBodyHex(TronAddress.fromPrivateKey(privateKey))

        val uncompressed = Secp256k1.publicKeyUncompressed(privateKey)
        val ethBody = Bytes.toHex(Hash.keccak256(uncompressed.copyOfRange(1, 65)).copyOfRange(12, 32))

        assertThat(tronBody).isEqualTo(ethBody)
    }

    @Test
    fun `路径与索引`() {
        assertThat(TronAddress.path(0)).isEqualTo("m/44'/195'/0'/0/0")
        assertThat(TronAddress.path(3)).isEqualTo("m/44'/195'/0'/0/3")
        // 不同索引必须得到不同地址
        assertThat(TronAddress.addressOf(master, 0)).isNotEqualTo(TronAddress.addressOf(master, 1))
    }

    @Test
    fun `十六进制与 Base58 地址互转`() {
        val address = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
        assertThat(TronAddress.toHex41(address)).isEqualTo("41c8599111f29c1e1e061265b4af93ea1f274ad78a")
        assertThat(TronAddress.fromHex("41c8599111f29c1e1e061265b4af93ea1f274ad78a")).isEqualTo(address)
        // 允许传不带 41 前缀的 20 字节主体
        assertThat(TronAddress.fromHex("c8599111f29c1e1e061265b4af93ea1f274ad78a")).isEqualTo(address)
    }

    @Test
    fun `地址校验能识别格式错误`() {
        assertThat(TronAddress.isValid("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH")).isTrue()
        assertThat(TronAddress.isValid("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdG")).isFalse()
        assertThat(TronAddress.isValid("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")).isFalse()
        assertThat(TronAddress.isValid("")).isFalse()
        assertThat(TronAddress.isValid("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA")).isFalse()
    }

    @Test
    fun `缩短显示`() {
        assertThat(TronAddress.shorten("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"))
            .isEqualTo("TUEZSd…GWYdH")
    }
}
