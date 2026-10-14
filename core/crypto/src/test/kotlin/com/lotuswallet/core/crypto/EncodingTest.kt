package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

class EncodingTest {

    @Test
    fun `Base58 经典向量`() {
        assertThat(Base58.encode("Hello World".toByteArray())).isEqualTo("JxF12TrwUP45BMd")
        assertThat(String(Base58.decode("JxF12TrwUP45BMd"))).isEqualTo("Hello World")
    }

    @Test
    fun `Base58 保留前导零字节`() {
        val data = byteArrayOf(0, 0, 1, 2, 3)
        val encoded = Base58.encode(data)
        assertThat(encoded.take(2)).isEqualTo("11")
        assertThat(Base58.decode(encoded)).isEqualTo(data)
    }

    @Test
    fun `Base58 往返随机数据`() {
        repeat(50) {
            val data = SecureRandom.bytes(32)
            assertThat(Base58.decode(Base58.encode(data))).isEqualTo(data)
        }
    }

    @Test
    fun `Base58Check 校验和能被检出篡改`() {
        val payload = Bytes.fromHex("0062e907b15cbf27d5425399ebf6f0fb50ebb88f18")
        val encoded = Base58.encodeChecked(payload)
        assertThat(Base58.decodeChecked(encoded)).isEqualTo(payload)

        val tampered = encoded.dropLast(1) + if (encoded.last() == '2') "3" else "2"
        val error = runCatching { Base58.decodeChecked(tampered) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Base58 拒绝非法字符`() {
        // '0' 'O' 'I' 'l' 不在 Base58 字母表中
        for (bad in listOf("0", "O", "I", "l")) {
            val error = runCatching { Base58.decode(bad) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `Base32 RFC4648 向量`() {
        assertThat(Base32.encode("foobar".toByteArray(), pad = false)).isEqualTo("MZXW6YTBOI")
        assertThat(Base32.encode("foobar".toByteArray(), pad = true)).isEqualTo("MZXW6YTBOI======")
        assertThat(String(Base32.decode("MZXW6YTBOI======"))).isEqualTo("foobar")
    }

    @Test
    fun `Base32 大小写与填充均可解析`() {
        assertThat(String(Base32.decode("mzxw6ytboi"))).isEqualTo("foobar")
        assertThat(String(Base32.decode("MZXW6YTBOI"))).isEqualTo("foobar")
    }

    @Test
    fun `Base32 宽松解码处理常见误认字符`() {
        // 用户常把 O 写成 0，把 l/I 写成 1
        val canonical = Base32.decode("MZXW6YTBOI")
        assertThat(Base32.decodeLenient("MZXW6YTB0I")).isEqualTo(canonical)
    }

    @Test
    fun `Bech32 校验和有效`() {
        val decoded = Bech32.decode("a12uel5l")
        assertThat(decoded.hrp).isEqualTo("a")
        assertThat(decoded.variant).isEqualTo(Bech32.Variant.BECH32)
    }

    @Test
    fun `Bech32 大小写混用被拒绝`() {
        val error = runCatching { Bech32.decode("A12uel5l") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Bech32 篡改后校验和失败`() {
        val error = runCatching { Bech32.decode("a12uel5m") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `BIP173 P2WPKH 地址向量`() {
        // BIP-173 示例：witness v0 + 20 字节 hash160
        val program = Bytes.fromHex("751e76e8199196d454941c45d1b3a323f1433bd6")
        val address = Bech32.encodeSegwit("bc", 0, program)
        assertThat(address).isEqualTo("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")

        val decoded = Bech32.decode(address)
        assertThat(decoded.hrp).isEqualTo("bc")
        assertThat(decoded.variant).isEqualTo(Bech32.Variant.BECH32)
        val payload5 = decoded.data
        assertThat(payload5[0].toInt()).isEqualTo(0)
        val program8 = Bech32.convertBits(payload5.copyOfRange(1, payload5.size), 5, 8, false)
        assertThat(program8).isEqualTo(program)
    }

    @Test
    fun `BIP350 P2TR 使用 bech32m`() {
        val program = SecureRandom.bytes(32)
        val address = Bech32.encodeSegwit("bc", 1, program)
        val decoded = Bech32.decode(address)
        assertThat(decoded.variant).isEqualTo(Bech32.Variant.BECH32M)
    }

    @Test
    fun `v0 地址拒绝非法 program 长度`() {
        val error = runCatching { Bech32.encodeSegwit("bc", 0, SecureRandom.bytes(21)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
