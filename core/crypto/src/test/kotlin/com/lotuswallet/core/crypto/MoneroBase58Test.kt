package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

/**
 * Monero Base58 的分块编码。
 *
 * 这里最要紧的不是「往返能对上」（那样一个自洽的错误实现也能通过），
 * 而是**分块边界**：Monero 与比特币 Base58 的唯一区别就是按 8 字节分块、
 * 每块固定 11 字符。写成分块之外的形式，长度和数值都会变，
 * 地址在其它钱包里直接失效。
 *
 * 因此下面同时钉住了「每块的字符数」和「一个 69 字节地址正好 95 字符」这两件事。
 */
class MoneroBase58Test {

    @Test
    fun `8字节块编码为11个字符`() {
        assertThat(MoneroBase58.encode(ByteArray(8))).isEqualTo("11111111111")
        assertThat(MoneroBase58.encode(ByteArray(8)).length).isEqualTo(11)
    }

    @Test
    fun `尾块按查表输出对应字符数`() {
        // 未编码 1..8 字节 → 编码 2,3,5,6,7,9,10,11 个字符
        val expected = mapOf(1 to 2, 2 to 3, 3 to 5, 4 to 6, 5 to 7, 6 to 9, 7 to 10, 8 to 11)
        for ((bytes, chars) in expected) {
            assertThat(MoneroBase58.encode(ByteArray(bytes)).length).isEqualTo(chars)
        }
    }

    @Test
    fun `9字节等于一个满块加一个尾块`() {
        assertThat(MoneroBase58.encode(ByteArray(9)).length).isEqualTo(11 + 2)
    }

    @Test
    fun `69字节地址编码为95个字符`() {
        // Monero 主网地址 = 1(前缀) + 32(花费公钥) + 32(查看公钥) + 4(校验和)
        // = 8 个满块(88 字符) + 5 字节尾块(7 字符) = 95
        assertThat(MoneroBase58.encode(ByteArray(69)).length).isEqualTo(95)
    }

    @Test
    fun `分块序列的固定向量`() {
        // bytes(range(64)) + 01..05：跨多个块边界，能同时验到满块与尾块
        val data = ByteArray(64) { it.toByte() } + Bytes.fromHex("0102030405")
        assertThat(MoneroBase58.encode(data))
            .isEqualTo(
                "113DUyZY2dc2LxFSMtsQ5k3gsHPkECmXt52nKM8ZY8z26NhMJWtsWSA7icPFuECstJ" +
                    "94XRDHZYFLSAQSTAftscna17bWpTW"
            )
    }

    @Test
    fun `任意长度都能往返`() {
        for (size in 1..80) {
            val data = SecureRandom.bytes(size)
            assertThat(MoneroBase58.decode(MoneroBase58.encode(data))).isEqualTo(data)
        }
    }

    @Test
    fun `空输入往返`() {
        assertThat(MoneroBase58.encode(ByteArray(0))).isEmpty()
        assertThat(MoneroBase58.decode("")).isEmpty()
    }

    @Test
    fun `解码拒绝非法字符与非法尾块长度`() {
        // '0' 'O' 'I' 'l' 都不在字母表里
        for (bad in listOf("0", "O", "I", "l")) {
            assertThat(runCatching { MoneroBase58.decode(bad) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        // 12 个字符不是任何合法块长度（满块 11、尾块最多 10 之外）
        assertThat(runCatching { MoneroBase58.decode("1".repeat(12)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `与比特币Base58必须给出不同结果`() {
        // 同一个 69 字节内容，两种 Base58 编出来的长度不同 —— 这就是不能复用的证据
        val data = ByteArray(69) { (it * 7 + 3).toByte() }
        assertThat(MoneroBase58.encode(data)).isNotEqualTo(Base58.encode(data))
        assertThat(MoneroBase58.encode(data).length).isEqualTo(95)
    }
}
