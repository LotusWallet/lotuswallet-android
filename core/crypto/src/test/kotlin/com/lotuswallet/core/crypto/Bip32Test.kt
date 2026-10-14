package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

class Bip32Test {

    private val vector1Seed = Bytes.fromHex("000102030405060708090a0b0c0d0e0f")

    private val vector1MasterXprv =
        "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
    private val vector1MasterXpub =
        "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"

    @Test
    fun `BIP32 官方测试向量 1 的主密钥`() {
        val master = Bip32.fromSeed(vector1Seed)
        assertThat(master.serialize()).isEqualTo(vector1MasterXprv)
        assertThat(master.serializePublic()).isEqualTo(vector1MasterXpub)
    }

    @Test
    fun `BIP32 官方测试向量 1 的硬化子密钥`() {
        // m/0'
        val child = Bip32.fromSeed(vector1Seed).derive(0x80000000.toInt())
        val expected =
            "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7"
        assertThat(child.serialize()).isEqualTo(expected)
    }

    @Test
    fun `路径解析支持多种写法`() {
        val expected = listOf(0x80000000.toInt() or 44, 0x80000000.toInt() or 60, 0x80000000.toInt(), 0, 0)
        assertThat(Bip32.parsePath("m/44'/60'/0'/0/0")).isEqualTo(expected)
        assertThat(Bip32.parsePath("M/44h/60h/0h/0/0")).isEqualTo(expected)
        assertThat(Bip32.parsePath("44'/60'/0'/0/0")).isEqualTo(expected)
        assertThat(Bip32.parsePath("m")).isEmpty()
    }

    @Test
    fun `按路径派生的结果与逐级派生一致`() {
        val master = Bip32.fromSeed(vector1Seed)
        val byPath = master.derive("m/0'/1/2'")
        val stepByStep = master.derive(0x80000000.toInt()).derive(1).derive(0x80000000.toInt() or 2)
        assertThat(byPath.privateKey).isEqualTo(stepByStep.privateKey)
        assertThat(byPath.chainCode).isEqualTo(stepByStep.chainCode)
    }

    @Test
    fun `同一助记词派生结果稳定`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val a = Bip32.fromMnemonic(mnemonic).derive("m/44'/60'/0'/0/0")
        val b = Bip32.fromMnemonic(mnemonic).derive("m/44'/60'/0'/0/0")
        assertThat(Bytes.toHex(a.privateKey)).isEqualTo(Bytes.toHex(b.privateKey))
    }

    @Test
    fun `BIP44 路径构造正确`() {
        assertThat(Bip32.bip44Path(coinType = 0)).isEqualTo("44'/0'/0'/0/0")
        assertThat(Bip32.bip44Path(coinType = 60, purpose = 44)).isEqualTo("44'/60'/0'/0/0")
        assertThat(Bip32.bip44Path(coinType = 195, purpose = 44, index = 3)).isEqualTo("44'/195'/0'/0/3")
    }

    @Test
    fun `派生出的私钥总是合法的 secp256k1 标量`() {
        val master = Bip32.fromMnemonic(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        for (i in 0 until 20) {
            val key = master.derive("m/44'/60'/0'/0/$i")
            val scalar = java.math.BigInteger(1, key.privateKey)
            assertThat(scalar.signum()).isGreaterThan(0)
            assertThat(scalar).isLessThan(Secp256k1.N)
            assertThat(key.publicKey).hasLength(33)
        }
    }

    @Test
    fun `非法路径给出明确错误`() {
        val master = Bip32.fromSeed(vector1Seed)
        val error = runCatching { master.derive("m/44'/abc/0") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
