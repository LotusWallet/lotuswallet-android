package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

/**
 * 为新增链补上的哈希原语：SHA-224（ICP）、BLAKE2b（Filecoin）、CRC-32（ICP 校验和）、
 * HKDF-SHA256（为 BLS12-381 的 EIP-2333 预留）。
 *
 * 期望值均为公开向量（RFC 3874 / RFC 5869 / BLAKE2 官方测试向量 / CRC-32 标准值）。
 */
class HashExtendedTest {

    @Test
    fun `SHA224 符合 RFC3874 向量`() {
        assertThat(Bytes.toHex(Hash.sha224(ByteArray(0))))
            .isEqualTo("d14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f")
        assertThat(Bytes.toHex(Hash.sha224("abc".toByteArray())))
            .isEqualTo("23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7")
    }

    @Test
    fun `SHA224 输出 28 字节`() {
        assertThat(Hash.sha224(SecureRandom.bytes(64)).size).isEqualTo(28)
    }

    @Test
    fun `BLAKE2b 可按不同输出长度使用`() {
        // Filecoin 用 20（地址负载）与 4（校验和）
        assertThat(Bytes.toHex(Hash.blake2b("abc".toByteArray(), 32)))
            .isEqualTo("bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319")
        assertThat(Bytes.toHex(Hash.blake2b("abc".toByteArray(), 20)))
            .isEqualTo("384264f676f39536840523f284921cdc68b6846b")
        assertThat(Bytes.toHex(Hash.blake2b("abc".toByteArray(), 4)))
            .isEqualTo("63906248")
    }

    @Test
    fun `BLAKE2b 输出长度随参数变化`() {
        val data = ByteArray(8) { 1 }
        for (len in listOf(1, 4, 20, 32, 64)) {
            assertThat(Hash.blake2b(data, len).size).isEqualTo(len)
        }
    }

    @Test
    fun `BLAKE2b 与 SHA256 必须不同——否则 Filecoin 地址会全体错位`() {
        val data = "abc".toByteArray()
        assertThat(Hash.blake2b(data, 32)).isNotEqualTo(Hash.sha256(data))
    }

    @Test
    fun `CRC32 符合标准值`() {
        assertThat(Hash.crc32(ByteArray(0))).isEqualTo(0)
        assertThat(Hash.crc32("123456789".toByteArray())).isEqualTo(0xCBF43926.toInt())
    }

    @Test
    fun `CRC32 按大端写入前缀——ICP 校验和依赖这一点`() {
        // Principal.fromRaw 把 crc 按大端放在前 4 字节。写成小端的话每个 principal 都会变。
        val crc = Hash.crc32("123456789".toByteArray())
        val bigEndian = Bytes.toHex(
            byteArrayOf(
                (crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte(),
            )
        )
        assertThat(bigEndian).isEqualTo("cbf43926")
        assertThat(bigEndian).isNotEqualTo(Bytes.toHex(Bytes.fromHex("cbf43926").reversedArray()))
    }

    @Test
    fun `Keccak256 与 SHA3-256 不是同一个函数`() {
        // 以太坊与 Monero 用的是 Keccak（原始提交版），不是 NIST 的 SHA3
        assertThat(Bytes.toHex(Hash.keccak256(ByteArray(0))))
            .isEqualTo("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")
        assertThat(Bytes.toHex(Hash.keccak256("abc".toByteArray())))
            .isEqualTo("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45")
    }

    @Test
    fun `HKDF-SHA256 符合 RFC5869 测试用例1`() {
        val okm = Hash.hkdfSha256(
            ikm = Bytes.fromHex("0b".repeat(22)),
            salt = Bytes.fromHex("000102030405060708090a0b0c"),
            info = Bytes.fromHex("f0f1f2f3f4f5f6f7f8f9"),
            lengthBytes = 42,
        )
        assertThat(Bytes.toHex(okm)).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        )
    }

    @Test
    fun `HKDF-SHA256 输出长度精确可控`() {
        val ikm = SecureRandom.bytes(32)
        for (len in listOf(1, 16, 32, 33, 64, 100)) {
            assertThat(
                Hash.hkdfSha256(ikm, ByteArray(0), "lotus".toByteArray(), len).size
            ).isEqualTo(len)
        }
    }
}
