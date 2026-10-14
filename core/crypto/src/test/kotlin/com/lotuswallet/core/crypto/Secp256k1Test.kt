package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test
import java.math.BigInteger

class Secp256k1Test {

    @Test
    fun `私钥到公钥与已知向量一致`() {
        // 私钥 1 对应生成点 G
        val one = Bytes.leftPad(byteArrayOf(1), 32)
        val pub = Secp256k1.publicKeyUncompressed(one)
        assertThat(Bytes.toHex(pub)).isEqualTo(
            "0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798" +
                "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8",
        )
    }

    @Test
    fun `私钥 1 的压缩公钥`() {
        val one = Bytes.leftPad(byteArrayOf(1), 32)
        assertThat(Bytes.toHex(Secp256k1.publicKeyCompressed(one)))
            .isEqualTo("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
    }

    @Test
    fun `签名可被验证`() {
        val priv = Secp256k1.randomPrivateKey()
        val pub = Secp256k1.publicKeyCompressed(priv)
        val digest = Hash.sha256("hello lotuswallet".toByteArray())
        val sig = Secp256k1.sign(priv, digest)
        assertThat(Secp256k1.verify(pub, digest, sig.r, sig.s)).isTrue()
    }

    @Test
    fun `签名采用 low-S 规范化`() {
        repeat(30) {
            val priv = Secp256k1.randomPrivateKey()
            val digest = Hash.sha256(SecureRandom.bytes(32))
            val sig = Secp256k1.sign(priv, digest)
            assertThat(sig.s).isAtMost(Secp256k1.HALF_N)
        }
    }

    @Test
    fun `确定性签名（RFC6979）对同一输入产生相同结果`() {
        val priv = Bytes.leftPad(byteArrayOf(7), 32)
        val digest = Hash.sha256("deterministic".toByteArray())
        val a = Secp256k1.sign(priv, digest)
        val b = Secp256k1.sign(priv, digest)
        assertThat(a.r).isEqualTo(b.r)
        assertThat(a.s).isEqualTo(b.s)
        assertThat(a.recId).isEqualTo(b.recId)
    }

    @Test
    fun `公钥恢复与原始公钥一致`() {
        repeat(30) {
            val priv = Secp256k1.randomPrivateKey()
            val digest = Hash.sha256(SecureRandom.bytes(32))
            val sig = Secp256k1.sign(priv, digest)
            val recovered = Secp256k1.recoverPublicPoint(digest, sig.r, sig.s, sig.recId)
            assertThat(recovered.getEncoded(true)).isEqualTo(Secp256k1.publicKeyCompressed(priv))
        }
    }

    @Test
    fun `篡改签名后验证失败`() {
        val priv = Secp256k1.randomPrivateKey()
        val digest = Hash.sha256("tamper".toByteArray())
        val sig = Secp256k1.sign(priv, digest)
        val tamperedR = (sig.r + BigInteger.ONE).mod(Secp256k1.N)
        assertThat(Secp256k1.verify(Secp256k1.publicKeyCompressed(priv), digest, tamperedR, sig.s)).isFalse()
    }

    @Test
    fun `紧凑签名长度为 64 字节`() {
        val priv = Secp256k1.randomPrivateKey()
        val sig = Secp256k1.sign(priv, Hash.sha256("x".toByteArray()))
        assertThat(sig.toCompact()).hasLength(64)
    }

    @Test
    fun `DER 签名以 0x30 开头且可解析`() {
        val priv = Secp256k1.randomPrivateKey()
        val sig = Secp256k1.sign(priv, Hash.sha256("y".toByteArray()))
        val der = sig.toDer()
        assertThat(der[0]).isEqualTo(0x30.toByte())
        assertThat(der.size).isAtLeast(68)
    }

    @Test
    fun `越界私钥被拒绝`() {
        val zero = ByteArray(32)
        val error = runCatching { Secp256k1.publicKeyCompressed(zero) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)

        // 全 0xFF 必然大于曲线阶 n
        val tooBig = ByteArray(32) { 0xFF.toByte() }
        assertThat(BigInteger(1, tooBig)).isGreaterThan(Secp256k1.N)
        val error2 = runCatching { Secp256k1.publicKeyCompressed(tooBig) }.exceptionOrNull()
        assertThat(error2).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `recId 必须唯一指向真实公钥而不是镜像公钥`() {
        // ECDSA 可恢复签名存在固有二义性：同一个 (r, s) 下，
        // 用 recId=0 与 recId=1 恢复出的两个公钥都能通过验签。
        // 因此 recId 必须与签名时的 nonce 点一致，否则 ecrecover 会得到错误的地址。
        repeat(50) {
            val priv = Secp256k1.randomPrivateKey()
            val digest = Hash.sha256(SecureRandom.bytes(32))
            val sig = Secp256k1.sign(priv, digest)

            val recovered = Secp256k1.recoverPublicPoint(digest, sig.r, sig.s, sig.recId)
            assertThat(recovered.getEncoded(true)).isEqualTo(Secp256k1.publicKeyCompressed(priv))

            // 镜像 recId 同样能验签，但恢复出的公钥不同 —— 这正是必须精确记录 recId 的原因
            val mirrorRecId = sig.recId xor 1
            val mirror = Secp256k1.recoverPublicPoint(digest, sig.r, sig.s, mirrorRecId)
            assertThat(Secp256k1.verify(mirror.getEncoded(false), digest, sig.r, sig.s)).isTrue()
            assertThat(mirror.getEncoded(true)).isNotEqualTo(recovered.getEncoded(true))
        }
    }

    @Test
    fun `摘要长度非 32 字节时签名报错`() {
        val priv = Secp256k1.randomPrivateKey()
        val error = runCatching { Secp256k1.sign(priv, ByteArray(20)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `随机私钥总在合法范围内且互不相同`() {
        val keys = (0 until 50).map { Bytes.toHex(Secp256k1.randomPrivateKey()) }
        assertThat(keys.toSet()).hasSize(50)
        keys.forEach {
            val scalar = BigInteger(1, Bytes.fromHex(it))
            assertThat(scalar.signum()).isGreaterThan(0)
            assertThat(scalar).isLessThan(Secp256k1.N)
        }
    }

    @Test
    fun `keccak256 与已知向量一致`() {
        // 空输入的 Keccak-256
        assertThat(Bytes.toHex(Hash.keccak256(ByteArray(0))))
            .isEqualTo("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")
    }

    @Test
    fun `hash160 长度正确`() {
        assertThat(Hash.hash160("abc".toByteArray())).hasLength(20)
        assertThat(Hash.ripemd160("abc".toByteArray())).hasLength(20)
    }
}
