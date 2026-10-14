package com.lotuswallet.chain.alt

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bech32
import com.lotuswallet.core.crypto.SecureRandom
import org.junit.Test

/**
 * Chia 的 `xch1…` 地址编解码。
 *
 * ## 为什么这里只测编解码、不测「由助记词派生」
 * Chia 的地址是 **puzzle hash** 的 bech32m 编码，而 puzzle hash 需要
 * BLS12-381 与标准 puzzle 程序的**字节级常量**。常量写错的话，地址看上去
 * 完全合法、却指向一个没人能解锁的谜题 —— 属于「静默错误资产」。
 * 因此本版本**不生成** Chia 地址，只提供 `xch1…` 的编解码与校验，
 * 让用户能把自己的地址以「观察地址」方式纳入统一账户管理。
 *
 * 这里的向量由独立的 bech32m 实现算出（BIP-350），长度 62 与主网一致。
 */
class ChiaAddressTest {

    private val puzzleHash =
        Bytes.fromHex("1f4b3c2d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809")

    @Test
    fun `puzzle hash 编码为主网地址`() {
        assertThat(ChiaAddress.puzzleHashToAddress(puzzleHash))
            .isEqualTo("xch1ra9nct27dacgry4rknzadelcpydzk0zdtehhpqvj5w6vt4h8lqyslgt6q2")
    }

    @Test
    fun `puzzle hash 编码为测试网地址`() {
        assertThat(ChiaAddress.puzzleHashToAddress(puzzleHash, ChiaAddress.TESTNET_HRP))
            .isEqualTo("txch1ra9nct27dacgry4rknzadelcpydzk0zdtehhpqvj5w6vt4h8lqysj0vvpe")
    }

    @Test
    fun `地址长度为62个字符——与主网一致`() {
        val address = ChiaAddress.puzzleHashToAddress(puzzleHash)
        assertThat(address.length).isEqualTo(62)
        assertThat(address).startsWith("xch1")
    }

    @Test
    fun `地址往返回 puzzle hash`() {
        val address = ChiaAddress.puzzleHashToAddress(puzzleHash)
        assertThat(ChiaAddress.addressToPuzzleHash(address)).isEqualTo(puzzleHash)
    }

    @Test
    fun `随机 puzzle hash 往返一致`() {
        repeat(32) {
            val hash = SecureRandom.bytes(32)
            val address = ChiaAddress.puzzleHashToAddress(hash)
            assertThat(ChiaAddress.addressToPuzzleHash(address)).isEqualTo(hash)
        }
    }

    @Test
    fun `校验——合法地址通过`() {
        assertThat(ChiaAddress.isValid("xch1ra9nct27dacgry4rknzadelcpydzk0zdtehhpqvj5w6vt4h8lqyslgt6q2")).isTrue()
        assertThat(
            ChiaAddress.isValid(
                "  xch1ra9nct27dacgry4rknzadelcpydzk0zdtehhpqvj5w6vt4h8lqyslgt6q2  "
            )
        ).isTrue()
    }

    @Test
    fun `校验——篡改一个字符即失败`() {
        val address = ChiaAddress.puzzleHashToAddress(puzzleHash)
        val tampered = address.dropLast(1) + if (address.last() == 'q') "p" else "q"
        assertThat(ChiaAddress.isValid(tampered)).isFalse()
        assertThat(ChiaAddress.addressToPuzzleHash(tampered)).isNull()
    }

    @Test
    fun `校验——网络前缀不匹配时失败`() {
        val mainnet = ChiaAddress.puzzleHashToAddress(puzzleHash)
        val testnet = ChiaAddress.puzzleHashToAddress(puzzleHash, ChiaAddress.TESTNET_HRP)
        assertThat(ChiaAddress.isValid(testnet)).isFalse()
        assertThat(ChiaAddress.isValid(testnet, ChiaAddress.TESTNET_HRP)).isTrue()
        assertThat(ChiaAddress.isValid(mainnet, ChiaAddress.TESTNET_HRP)).isFalse()
    }

    @Test
    fun `校验——必须用 bech32m 而不是 bech32`() {
        // 这是 BIP-350 的核心区别：把同一个数据用 bech32 编出来，
        // 文本同样「可解码」，但校验和常量不同，必须判为非法
        val data5 = Bech32.convertBits(puzzleHash, 8, 5, true)
        val bech32Address = Bech32.encode(ChiaAddress.MAINNET_HRP, data5, Bech32.Variant.BECH32)
        assertThat(bech32Address).isNotEqualTo(ChiaAddress.puzzleHashToAddress(puzzleHash))
        assertThat(ChiaAddress.addressToPuzzleHash(bech32Address)).isNull()
        assertThat(ChiaAddress.isValid(bech32Address)).isFalse()
    }

    @Test
    fun `校验——比特币地址与垃圾输入必须被拒绝`() {
        // 比特币的 bc1 地址同样是 bech32，但前缀与长度都对不上
        val btcProgram = Bytes.fromHex("751e76e8199196d454941c45d1b3a323f1433bd6")
        val btc = Bech32.encodeSegwit("bc", 0, btcProgram)
        assertThat(ChiaAddress.isValid(btc)).isFalse()

        for (bad in listOf("", "xch", "xch1", "not-an-address", "xch1qqqqqqqq")) {
            assertThat(ChiaAddress.isValid(bad)).isFalse()
        }
    }

    @Test
    fun `puzzle hash 长度不是32字节时拒绝编码`() {
        for (size in listOf(0, 20, 31, 33, 48)) {
            assertThat(runCatching { ChiaAddress.puzzleHashToAddress(ByteArray(size)) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
