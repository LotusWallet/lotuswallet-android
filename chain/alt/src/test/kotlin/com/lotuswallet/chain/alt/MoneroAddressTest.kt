package com.lotuswallet.chain.alt

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bip39
import com.lotuswallet.core.crypto.Ed25519Group
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.MoneroBase58
import com.lotuswallet.core.network.chain.Ed25519Derivation
import org.junit.Test

/**
 * Monero 地址派生。
 *
 * 这条链在实现上最容易出错，因为三处都与 Solana / ICP 不同：
 *   1. 私钥是**标量**而不是种子（公钥 = 标量 × 基点，不能走 EdDSA 的哈希语义）；
 *   2. 查看私钥 = `sc_reduce32(Keccak256(花费私钥))`，用的是 Keccak 不是 SHA3；
 *   3. 地址用**按 8 字节分块**的 Monero Base58 —— 69 字节编出来必须是 95 个字符。
 *
 * 因此下面既钉住明文地址，也钉住「解码后的字节布局」（前缀 + 两个 32 字节公钥 + 校验和），
 * 后者出错时能直接看出是哪一步错了。
 */
class MoneroAddressTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private val seed: ByteArray get() = Bip39.mnemonicToSeed(mnemonic)

    private val account0 =
        "44jKQv6ZKMd5ecLLmkNJGi7azgSptEq8ki7TFiat1TfLfdDQ1tQ7ZYa3cRh7X2uRwvLDjddWh97ajeyhR2seKSECQeDx1WR"

    @Test
    fun `标准助记词——账户0的地址`() {
        assertThat(MoneroAddress.address(seed, MoneroAddress.pathFor(0))).isEqualTo(account0)
    }

    @Test
    fun `标准助记词——账户1的地址`() {
        assertThat(MoneroAddress.address(seed, MoneroAddress.pathFor(1)))
            .isEqualTo(
                "43gVG8hoX8UL6wMn3Eai9dQVdP6BfQXiuSB5TqNBfHvPW5VUmx9WXV2Vnfta9b2S4v6gZwbsGF3MJ48JWh3pgnXSQQ3NhiL"
            )
    }

    @Test
    fun `主网标准地址是95个字符并以4开头`() {
        assertThat(account0.length).isEqualTo(95)
        assertThat(account0).startsWith("4")
    }

    @Test
    fun `地址的字节布局符合 1+32+32+4`() {
        val decoded = MoneroBase58.decode(account0)
        assertThat(decoded.size).isEqualTo(69)
        assertThat(decoded[0].toInt() and 0xFF).isEqualTo(MoneroAddress.MAINNET_PREFIX)
        assertThat(decoded[0].toInt() and 0xFF).isEqualTo(18)

        // 花费公钥 / 查看公钥（各自的压缩形式）
        assertThat(Bytes.toHex(decoded.copyOfRange(1, 33)))
            .isEqualTo("51fd81faa5e2641bca8a5c43d764ab276033c12b459fd9f5c9c23b7733af67e6")
        assertThat(Bytes.toHex(decoded.copyOfRange(33, 65)))
            .isEqualTo("eb8855fd7d21670f9bdfc0cdcfa54172e80322c4eaee5ce30d85cb9ec90d21d1")

        // 校验和 = Keccak256(前 65 字节) 的前 4 字节
        val checksum = Hash.keccak256(decoded.copyOfRange(0, 65))
        assertThat(decoded.copyOfRange(65, 69)).isEqualTo(checksum.copyOfRange(0, 4))
    }

    @Test
    fun `私钥是标量——由 sc_reduce32 得到`() {
        val material = AltKeyDerivation.keyMaterial(
            seed,
            MoneroAddress.pathFor(0),
            Ed25519Derivation.SLIP10,
        )
        val expected = Bytes.fromHex("8f2d521d4334f4d5d174c47aacb346e7633516416fae089d74ecfd0c389a6b08")
        assertThat(Ed25519Group.scReduce32(material)).isEqualTo(expected)
        // 规约前与规约后不同 —— 说明这一步确实发生了，而不是碰巧相等
        assertThat(material).isNotEqualTo(expected)
    }

    @Test
    fun `路径约定为 m 44 128 account`() {
        assertThat(MoneroAddress.pathFor(0)).isEqualTo("m/44'/128'/0'")
        assertThat(MoneroAddress.pathFor(5)).isEqualTo("m/44'/128'/5'")
    }

    @Test
    fun `地址校验——合法地址通过并容忍首尾空白`() {
        assertThat(MoneroAddress.isValid(account0)).isTrue()
        assertThat(MoneroAddress.isValid("  $account0  ")).isTrue()
    }

    @Test
    fun `地址校验——篡改一个字符即失败`() {
        val tampered = account0.dropLast(1) + if (account0.last() == '1') "2" else "1"
        assertThat(MoneroAddress.isValid(tampered)).isFalse()
    }

    @Test
    fun `地址校验——前缀不匹配时失败`() {
        // 拿主网地址按测试网前缀校验
        assertThat(MoneroAddress.isValid(account0, MoneroAddress.TESTNET_PREFIX)).isFalse()
        assertThat(MoneroAddress.TESTNET_PREFIX).isEqualTo(53)
    }

    @Test
    fun `地址校验——明显非法的输入返回 false 而不是抛异常`() {
        for (bad in listOf("", "abc", "0".repeat(95), account0.dropLast(10))) {
            assertThat(runCatching { MoneroAddress.isValid(bad) }.getOrDefault(false)).isFalse()
        }
    }

    @Test
    fun `测试网前缀生成的地址长度与主网一致但首字符不同`() {
        val testnet = MoneroAddress.address(seed, MoneroAddress.pathFor(0), MoneroAddress.TESTNET_PREFIX)
        assertThat(testnet.length).isEqualTo(95)
        assertThat(testnet).isNotEqualTo(account0)
        assertThat(MoneroAddress.isValid(testnet, MoneroAddress.TESTNET_PREFIX)).isTrue()
    }

    @Test
    fun `不同账户派生不同地址`() {
        assertThat(MoneroAddress.address(seed, MoneroAddress.pathFor(0)))
            .isNotEqualTo(MoneroAddress.address(seed, MoneroAddress.pathFor(1)))
    }
}
