package com.lotuswallet.chain.evm

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.crypto.Bip32
import org.junit.Test

class EvmAddressTest {

    /** EIP-55 官方给出的校验和测试用例。 */
    @Test
    fun `EIP-55 官方校验和向量`() {
        val vectors = listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        )
        for (expected in vectors) {
            assertThat(EvmAddress.checksum(expected)).isEqualTo(expected)
            assertThat(EvmAddress.isValid(expected)).isTrue()
        }
    }

    @Test
    fun `校验和能够发现被篡改的大小写`() {
        val tampered = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"
        assertThat(EvmAddress.isValid(tampered)).isFalse()
    }

    @Test
    fun `全小写地址视为合法但会被规范化为校验和形式`() {
        val lower = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"
        assertThat(EvmAddress.isValid(lower)).isTrue()
        assertThat(EvmAddress.checksum(lower)).isEqualTo("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
    }

    @Test
    fun `长度或字符非法的地址被拒绝`() {
        assertThat(EvmAddress.isValid("0x1234")).isFalse()
        assertThat(EvmAddress.isValid("0x" + "z".repeat(40))).isFalse()
        assertThat(EvmAddress.isValid("")).isFalse()
    }

    /**
     * 标准测试助记词（TREZOR 官方 BIP-44 测试向量）：
     * `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about`
     */
    @Test
    fun `标准助记词派生出已知的以太坊地址`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
        val master = Bip32.fromMnemonic(mnemonic)
        val address = EvmDerivation.addressOf(master, index = 0)
        assertThat(address).isEqualTo("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
    }

    @Test
    fun `所有 EVM 链共用同一路径因此地址相同`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
        val master = Bip32.fromMnemonic(mnemonic)
        // 第 1 个与第 2 个地址必须不同；同一个 index 反复派生必须一致
        val first = EvmDerivation.addressOf(master, index = 0)
        val second = EvmDerivation.addressOf(master, index = 1)
        assertThat(first).isNotEqualTo(second)
        assertThat(EvmDerivation.addressOf(master, index = 0)).isEqualTo(first)
        // 路径必须是 m/44'/60'/0'/0/i
        assertThat(EvmDerivation.path(0)).isEqualTo("m/44'/60'/0'/0/0")
        assertThat(EvmDerivation.path(7)).isEqualTo("m/44'/60'/0'/0/7")
    }

    @Test
    fun `缩短显示保留首尾`() {
        val address = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        assertThat(EvmAddress.shorten(address)).isEqualTo("0x9858…da94")
    }

    @Test
    fun `地址头像色与地址稳定对应`() {
        val a = EvmAddress.avatarColorSeed("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
        val b = EvmAddress.avatarColorSeed("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
        val c = EvmAddress.avatarColorSeed("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a).isAtLeast(0)
    }
}
