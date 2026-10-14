package com.lotuswallet.core.crypto

import java.math.BigInteger

/**
 * ed25519 群运算（**标量**语义）。
 *
 * ## 为什么需要它
 * [Ed25519.publicKey] 接受的是「种子」，内部还会做一次 SHA-512 + clamp，
 * 这是 EdDSA 的签名语义。但 Monero 的私钥是一支**已经是标量的 32 字节数**，
 * 公钥 = `标量 × 基点`，跳过哈希这一步。BouncyCastle 没有暴露这个入口，
 * 所以这里用 BigInteger 直接实现扭曲爱德华兹曲线的点运算。
 *
 * 只实现了「标量乘基点」与「标量规约」两件事 —— 没有签名、没有任意点乘，
 * 需要多少写多少，避免为了一个地址派生引入一整条未经审计的曲线库。
 *
 * 正确性由 [Ed25519GroupTest] 里的已知向量保证（标量 1 → 基点压缩形式）。
 */
object Ed25519Group {

    /** 域特征：2^255 - 19。 */
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** 子群阶 L = 2^252 + 27742317777372353535851937790883648493。 */
    val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** 曲线常数 d = -121665/121666。 */
    private val D: BigInteger =
        BigInteger.valueOf(-121665).mod(P)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P)

    private val TWO_D: BigInteger = D.multiply(BigInteger.TWO).mod(P)

    /** 基点（仿射坐标）。 */
    private val BX = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val BY = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

    /** 扩展坐标 (X, Y, Z, T)，满足 x = X/Z、y = Y/Z、T = XY/Z。 */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private val BASE = Point(BX.mod(P), BY.mod(P), BigInteger.ONE, BX.multiply(BY).mod(P))

    /**
     * 标量乘基点，返回 32 字节压缩公钥。
     *
     * @param scalar 32 字节**小端**标量（Monero 的私钥就是这个小端表示）
     */
    fun scalarMultBase(scalar: ByteArray): ByteArray {
        require(scalar.size == 32) { "标量必须是 32 字节，实际 ${scalar.size}" }
        val k = littleEndianToBigInteger(scalar)
        var acc = IDENTITY
        var started = false
        for (bit in 255 downTo 0) {
            if (started) acc = double(acc)
            if (k.testBit(bit)) {
                acc = if (started) add(acc, BASE) else BASE
                started = true
            }
        }
        return compress(if (started) acc else IDENTITY)
    }

    /** 把 32 字节小端整数规约到 [0, L)。用于 Monero 的 `sc_reduce32`。 */
    fun scReduce32(input: ByteArray): ByteArray {
        require(input.size == 32) { "sc_reduce32 输入必须是 32 字节，实际 ${input.size}" }
        return bigIntegerToLittleEndian(littleEndianToBigInteger(input).mod(L), 32)
    }

    /** 判断标量是否为零（全 0）。 */
    fun isZeroScalar(scalar: ByteArray): Boolean = scalar.all { it == 0.toByte() }

    // ------------------------------------------------------------ 点运算

    /** 倍点（dbl-2008-hwcd，a = -1）。 */
    private fun double(p: Point): Point {
        if (p.z.signum() == 0 || isIdentity(p)) return IDENTITY
        val a = p.x.multiply(p.x).mod(P)
        val b = p.y.multiply(p.y).mod(P)
        val c = p.z.multiply(p.z).mod(P).multiply(BigInteger.TWO).mod(P)
        val d = a.negate().mod(P)
        val e = p.x.add(p.y).let { it.multiply(it) }.subtract(a).subtract(b).mod(P)
        val g = d.add(b).mod(P)
        val f = g.subtract(c).mod(P)
        val h = d.subtract(b).mod(P)
        return Point(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P),
        )
    }

    /** 点加（add-2008-hwcd-3）。 */
    private fun add(p: Point, q: Point): Point {
        if (isIdentity(p)) return q
        if (isIdentity(q)) return p
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(TWO_D).multiply(q.t).mod(P)
        val d = p.z.multiply(BigInteger.TWO).multiply(q.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return Point(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P),
        )
    }

    private fun isIdentity(p: Point): Boolean =
        p.x.signum() == 0 && p.y.subtract(p.z).mod(P).signum() == 0

    /** 压缩：`y`（32 字节小端）+ 最高位存放 `x` 的奇偶。 */
    private fun compress(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = bigIntegerToLittleEndian(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    // ------------------------------------------------------------ 编解码

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        val le = bytes.reversedArray()
        return BigInteger(1, le)
    }

    private fun bigIntegerToLittleEndian(value: BigInteger, length: Int): ByteArray {
        val be = value.toByteArray()
        val trimmed = if (be.size > 1 && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
        val out = ByteArray(length)
        val start = length - trimmed.size
        if (start >= 0) {
            System.arraycopy(trimmed, 0, out, start, trimmed.size)
        } else {
            System.arraycopy(trimmed, trimmed.size - length, out, 0, length)
        }
        return out.reversedArray()
    }
}
