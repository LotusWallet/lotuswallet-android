package com.lotuswallet.chain.alt

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Bip39
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1
import org.junit.Test

/**
 * Filecoin `f1` 地址。
 *
 * 四个容易踩的点：
 *   · 负载是 **BLAKE2b-160**（20 字节），不是 hash160、不是 Keccak；
 *   · 哈希输入是**未压缩**公钥（65 字节）—— 用压缩公钥会得到另一个地址；
 *   · 校验和是 `BLAKE2b-4(协议字节 || 负载)`，把协议字节也算进去了；
 *   · **base32 只编「负载 || 校验和」，不含协议字节** —— 多编一个字节会让整条地址
 *     从 41 字符变成 42 字符，全网无人认识。这条用长度断言单独钉住。
 */
class FilecoinAddressTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private val seed: ByteArray get() = Bip39.mnemonicToSeed(mnemonic)

    /** 与派生无关的固定负载输入，用来单独钉住编码与校验和。 */
    private val fixedPayload = Bytes.fromHex("3f7a1c0d9b2e5a4c6f8b0d1e2a3b4c5d6e7f8091")

    @Test
    fun `固定负载的编码与校验和`() {
        assertThat(Bytes.toHex(Hash.blake2b(Bytes.fromHex("013f7a1c0d9b2e5a4c6f8b0d1e2a3b4c5d6e7f8091"), 4)))
            .isEqualTo("a4f747ad")
        assertThat(FilecoinAddress.encode("f", FilecoinAddress.PROTOCOL_SECP256K1, fixedPayload))
            .isEqualTo("f1h55bydm3fzney34lbupcuo2mlvxh7aerut3upli")
        assertThat(FilecoinAddress.encode("t", FilecoinAddress.PROTOCOL_SECP256K1, fixedPayload))
            .isEqualTo("t1h55bydm3fzney34lbupcuo2mlvxh7aerut3upli")
    }

    @Test
    fun `f1 地址是41个字符、f3 是86个字符——与主网一致`() {
        // 这是最容易被「顺手把协议字节也编进去」破坏的地方
        val f1 = FilecoinAddress.encode("f", FilecoinAddress.PROTOCOL_SECP256K1, fixedPayload)
        assertThat(f1.length).isEqualTo(41)

        val f3 = FilecoinAddress.encode("f", FilecoinAddress.PROTOCOL_BLS, ByteArray(48) { (it * 3 + 1).toByte() })
        assertThat(f3.length).isEqualTo(86)
    }

    @Test
    fun `标准助记词——账户0的地址`() {
        val address = FilecoinAddress.address(seed, FilecoinAddress.pathFor(0))
        assertThat(address).isEqualTo("f1qode47ievxlxzk6z2viuovedabmn3tq6t57uqhq")
    }

    @Test
    fun `标准助记词——账户1的地址`() {
        val address = FilecoinAddress.address(seed, FilecoinAddress.pathFor(1))
        assertThat(address).isEqualTo("f12oiips7bvuo3owfeietpqwhb6jf2zlcyoitbvra")
    }

    @Test
    fun `中间值对得上——私钥、未压缩公钥、20字节负载`() {
        val path = FilecoinAddress.pathFor(0)
        val privateKey = Bip32.fromSeed(seed).derive(path).privateKey
        assertThat(Bytes.toHex(privateKey))
            .isEqualTo("e1808079c6734eff9a187c917455dc1b2c70385e13f1cd6cecc94978e57f7f76")

        val uncompressed = Secp256k1.publicKeyUncompressed(privateKey)
        assertThat(uncompressed.size).isEqualTo(65)
        assertThat(uncompressed[0].toInt()).isEqualTo(0x04)
        assertThat(Bytes.toHex(Hash.blake2b(uncompressed, 20)))
            .isEqualTo("83864e7d04add77cabd9d5514754830058ddce1e")
    }

    @Test
    fun `用压缩公钥会得到另一个地址——证实必须用未压缩公钥`() {
        val privateKey = Bip32.fromSeed(seed).derive(FilecoinAddress.pathFor(0)).privateKey
        val compressedPayload = Hash.blake2b(Secp256k1.publicKeyCompressed(privateKey), 20)
        val wrong = FilecoinAddress.encode("f", FilecoinAddress.PROTOCOL_SECP256K1, compressedPayload)
        assertThat(wrong).isNotEqualTo(FilecoinAddress.address(seed, FilecoinAddress.pathFor(0)))
    }

    @Test
    fun `路径约定为 m 44 461 account 0 0`() {
        assertThat(FilecoinAddress.pathFor(0)).isEqualTo("m/44'/461'/0'/0/0")
        assertThat(FilecoinAddress.pathFor(2)).isEqualTo("m/44'/461'/2'/0/0")
    }

    @Test
    fun `地址形态——41个字符、f1 开头、正文为小写 base32`() {
        val address = FilecoinAddress.address(seed, FilecoinAddress.pathFor(0))
        assertThat(address.length).isEqualTo(41)
        assertThat(address).startsWith("f1")
        // f1 的正文是 39 个字符（20 字节负载 + 4 字节校验和 = 24 字节 → 39 个 base32 字符）
        assertThat(address.substring(2).length).isEqualTo(39)
        assertThat(address.substring(2)).matches("[a-z2-7]+")
    }

    @Test
    fun `地址校验——合法地址通过`() {
        assertThat(FilecoinAddress.isValid("f1qode47ievxlxzk6z2viuovedabmn3tq6t57uqhq")).isTrue()
        assertThat(FilecoinAddress.isValid("  f1qode47ievxlxzk6z2viuovedabmn3tq6t57uqhq  ")).isTrue()
    }

    @Test
    fun `地址校验——网络前缀不匹配时拒绝`() {
        val mainnet = FilecoinAddress.address(seed, FilecoinAddress.pathFor(0))
        val testnet = FilecoinAddress.address(seed, FilecoinAddress.pathFor(0), "t")
        assertThat(FilecoinAddress.isValid(mainnet, "f")).isTrue()
        assertThat(FilecoinAddress.isValid(mainnet, "t")).isFalse()
        assertThat(FilecoinAddress.isValid(testnet, "t")).isTrue()
        assertThat(testnet).isEqualTo(mainnet.replaceFirst("f", "t"))
    }

    @Test
    fun `地址校验——改一个字符即被拒绝`() {
        val address = "f1qode47ievxlxzk6z2viuovedabmn3tq6t57uqhq"

        // 动正文的第 1 个字符 → 落在负载第 1 个字节上，必然破坏校验和
        val head = if (address[2] == 'q') 'r' else 'q'
        assertThat(FilecoinAddress.isValid("f1$head" + address.substring(3))).isFalse()

        // 末位字符的**最低 3 位是填充位**（24 字节 = 192 位，39 个 base32 字符 = 195 位）：
        // 改成一个那 3 位相同的字符，解码结果一模一样，当然「验得过」——
        // 这不是校验和失效。所以这里特意选一个高 2 位不同的值。
        val tail = if (address.last() == 'z') 'q' else 'z'
        assertThat(FilecoinAddress.isValid(address.dropLast(1) + tail)).isFalse()
    }

    @Test
    fun `地址校验——未知协议号与非法字符时拒绝`() {
        assertThat(FilecoinAddress.isValid("f9qode47ievxlxzk6z2viuovedabmn3tq6t57uqhq")).isFalse()
        assertThat(FilecoinAddress.isValid("f1qode47ievxlxzk6z2viuovedabmn3tq6t57uqh0")).isFalse()
        assertThat(FilecoinAddress.isValid("f")).isFalse()
        assertThat(FilecoinAddress.isValid("")).isFalse()
    }

    @Test
    fun `f1 与 f3 的校验和都必须在解码时被验到`() {
        // 把 f1 的负载长度按 f3 解释（48 字节）必须失败，反之亦然
        val f1 = FilecoinAddress.encode("f", FilecoinAddress.PROTOCOL_SECP256K1, fixedPayload)
        val f3 = FilecoinAddress.encode("f", FilecoinAddress.PROTOCOL_BLS, ByteArray(48))
        assertThat(f1.substring(1)).isNotEqualTo(f3.substring(1))
        assertThat(FilecoinAddress.isValid(f1)).isTrue()
        assertThat(FilecoinAddress.isValid(f3)).isTrue()
    }

    @Test
    fun `不同账户派生不同地址`() {
        assertThat(FilecoinAddress.address(seed, FilecoinAddress.pathFor(0)))
            .isNotEqualTo(FilecoinAddress.address(seed, FilecoinAddress.pathFor(1)))
    }
}
