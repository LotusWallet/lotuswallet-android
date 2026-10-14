package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

/**
 * ed25519 群运算（标量语义）的自证向量。
 *
 * [Ed25519Group] 是为了 Monero 才写的：Monero 的私钥是**标量本身**，
 * 公钥 = 标量 × 基点，不走 EdDSA 的「SHA-512 + clamp」那一步。
 * 因为这一支是自己用 BigInteger 实现的，所以必须有锚点，否则一旦算错，
 * 产出的是「格式完全合法但没人能解锁」的地址。
 *
 * 三个锚点都来自公开常量，不依赖任何外部参考实现：
 *   · 标量 1 → 基点压缩形式 `5866…66`
 *   · 标量 0 → 单位元 `0100…00`
 *   · 标量 L（子群阶）→ 同样是单位元
 */
class Ed25519GroupTest {

    @Test
    fun `标量1的倍点是基点`() {
        val one = ByteArray(32).also { it[0] = 1 }
        assertThat(Bytes.toHex(Ed25519Group.scalarMultBase(one)))
            .isEqualTo("5866666666666666666666666666666666666666666666666666666666666666")
    }

    @Test
    fun `标量0的倍点是单位元`() {
        assertThat(Bytes.toHex(Ed25519Group.scalarMultBase(ByteArray(32))))
            .isEqualTo("0100000000000000000000000000000000000000000000000000000000000000")
    }

    @Test
    fun `标量L的倍点回到单位元——确认取的是子群阶`() {
        // L = 2^252 + 27742317777372353535851937790883648493，小端字节序
        val l = Bytes.fromHex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")
        assertThat(Ed25519Group.L.toString())
            .isEqualTo("7237005577332262213973186563042994240857116359379907606001950938285454250989")
        assertThat(Bytes.toHex(Ed25519Group.scalarMultBase(l)))
            .isEqualTo("0100000000000000000000000000000000000000000000000000000000000000")
    }

    @Test
    fun `标量2的倍点等于基点自加`() {
        val two = ByteArray(32).also { it[0] = 2 }
        assertThat(Bytes.toHex(Ed25519Group.scalarMultBase(two)))
            .isEqualTo("c9a3f86aae465f0e56513864510f3997561fa2c9e85ea21dc2292309f3cd6022")
    }

    @Test
    fun `scReduce32 把超出 L 的值规约回子群`() {
        // 顺序标量 0x00..0x1f 本身大于 L，规约后必须落在 [0, L)
        val input = ByteArray(32) { it.toByte() }
        val reduced = Ed25519Group.scReduce32(input)
        assertThat(Bytes.toHex(reduced))
            .isEqualTo("132d0ca6e9a1f3ae316c12682d132ffa0f1112131415161718191a1b1c1d1e0f")
        assertThat(Bytes.toHex(Ed25519Group.scalarMultBase(reduced)))
            .isEqualTo("ca4a448c3fc4d04945da9fdf920976c05e9bbe3d8cebb1858ea44d587c5e63c3")
    }

    @Test
    fun `scReduce32 全 0xFF 的规约结果`() {
        val reduced = Ed25519Group.scReduce32(ByteArray(32) { 0xFF.toByte() })
        assertThat(Bytes.toHex(reduced))
            .isEqualTo("1c95988d7431ecd670cf7d73f45befc6feffffffffffffffffffffffffffff0f")
    }

    @Test
    fun `scReduce32 对已经是子群内的值保持不变`() {
        // 标量 1 与 2 都远小于 L，规约应当原样返回
        val one = ByteArray(32).also { it[0] = 1 }
        val two = ByteArray(32).also { it[0] = 2 }
        assertThat(Ed25519Group.scReduce32(one)).isEqualTo(one)
        assertThat(Ed25519Group.scReduce32(two)).isEqualTo(two)
    }

    @Test
    fun `scReduce32 输出恒为 32 字节且小于 L`() {
        repeat(64) {
            val input = SecureRandom.bytes(32)
            val reduced = Ed25519Group.scReduce32(input)
            assertThat(reduced.size).isEqualTo(32)
            val value = java.math.BigInteger(1, reduced.reversedArray())
            assertThat(value < Ed25519Group.L).isTrue()
        }
    }

    @Test
    fun `isZeroScalar 只认全零`() {
        assertThat(Ed25519Group.isZeroScalar(ByteArray(32))).isTrue()
        assertThat(Ed25519Group.isZeroScalar(ByteArray(32).also { it[31] = 1 })).isFalse()
    }

    @Test
    fun `输入长度不合法会被拒绝`() {
        for (size in listOf(0, 16, 31, 33)) {
            assertThat(runCatching { Ed25519Group.scalarMultBase(ByteArray(size)) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(runCatching { Ed25519Group.scReduce32(ByteArray(size)) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `标量语义与 EdDSA 种子语义必须给出不同结果`() {
        // 这正是 Monero 不能复用 Ed25519.publicKey 的原因：
        // 后者会多做一次 SHA-512 与 clamp，把私钥当种子用
        val scalar = Bytes.fromHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        assertThat(Ed25519Group.scalarMultBase(scalar))
            .isNotEqualTo(Ed25519.publicKey(scalar))
    }
}
