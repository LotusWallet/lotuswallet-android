package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger

/**
 * BIP-340 Schnorr 签名（Taproot 的签名算法）。
 *
 * 与 ECDSA 的关键差异：
 * - 公钥是 **x-only**（32 字节），隐含取 y 为偶数的那个点，因此签名长度固定 64 字节；
 * - 签名是**线性**的，可以聚合 —— 这是 Taproot 能降低多签成本的根本原因；
 * - 没有 `recId` 概念（签名不携带恢复信息，因为有公钥就能验签）。
 *
 * 实现严格遵循 BIP-340，并自带自校验（签名后立即验签一次），
 * 避免产出坏签名导致广播被拒。
 */
object Schnorr {

    private val P: BigInteger = Secp256k1.CURVE.curve.field.characteristic
    private val N: BigInteger = Secp256k1.N
    private val G: ECPoint = Secp256k1.CURVE.g

    private const val AUX_TAG = "BIP0340/aux"
    private const val NONCE_TAG = "BIP0340/nonce"
    private const val CHALLENGE_TAG = "BIP0340/challenge"

    /** BIP-340 的带标签哈希：`SHA256(SHA256(tag) || SHA256(tag) || msg)`。 */
    internal fun taggedHash(tag: String, vararg messages: ByteArray): ByteArray {
        val tagHash = Hash.sha256(tag.toByteArray(Charsets.US_ASCII))
        var data = tagHash + tagHash
        for (m in messages) data += m
        return Hash.sha256(data)
    }

    /** 点的 x 坐标（32 字节）。 */
    internal fun xOnly(point: ECPoint): ByteArray =
        point.normalize().affineXCoord.toBigInteger().to32Bytes()

    /** `lift_x`：由 x 坐标还原曲线点，取 y 为偶数的那个。 */
    internal fun liftX(x: ByteArray): ECPoint {
        require(x.size == 32) { "x-only 公钥必须是 32 字节" }
        val xInt = BigInteger(1, x)
        require(xInt < P) { "x 超出曲线域范围" }
        // secp256k1 的 p ≡ 3 (mod 4)，因此平方根可以直接用指数运算求得
        val c = xInt.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P)
        var y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        require(y.multiply(y).mod(P) == c) { "该 x 不在 secp256k1 曲线上" }
        if (y.testBit(0)) y = P.subtract(y)
        return Secp256k1.CURVE.curve.createPoint(xInt, y)
    }

    /**
     * 取出「等价于 x-only 公钥」的私钥标量。
     *
     * 若 `d·G` 的 y 是奇数，则 x-only 公钥实际对应 `n - d`。
     * 这一步在推导 Taproot 输出键与签名时都必须一致，否则会得到错误地址。
     */
    internal fun evenYScalar(privateKey: ByteArray): BigInteger {
        val d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < N) { "私钥超出 secp256k1 有效范围" }
        val point = Secp256k1.publicPoint(privateKey).normalize()
        return if (point.affineYCoord.toBigInteger().testBit(0)) N.subtract(d) else d
    }

    /** 私钥 → x-only 公钥（32 字节）。 */
    fun publicKey(privateKey: ByteArray): ByteArray = xOnly(Secp256k1.publicPoint(privateKey))

    /**
     * 签名。
     *
     * @param auxRand 32 字节辅助随机数。默认取系统安全随机 —— 随机化的签名能防止
     *                侧信道攻击；测试时可传入确定的 32 个零以复现官方向量。
     */
    fun sign(
        privateKey: ByteArray,
        message: ByteArray,
        auxRand: ByteArray = com.lotuswallet.core.crypto.SecureRandom.bytes(32),
    ): ByteArray {
        require(privateKey.size == 32) { "私钥必须是 32 字节" }
        require(message.size == 32) { "待签消息必须是 32 字节" }
        require(auxRand.size == 32) { "辅助随机数必须是 32 字节" }

        val d = evenYScalar(privateKey)
        val px = publicKey(privateKey)

        val t = privateKey.padTo32().xorWith(taggedHash(AUX_TAG, auxRand))
        val k0 = BigInteger(1, taggedHash(NONCE_TAG, t, px, message)).mod(N)
        require(k0.signum() != 0) { "派生出的 nonce 为 0，请更换辅助随机数" }

        val rPoint = G.multiply(k0).normalize()
        val k = if (rPoint.affineYCoord.toBigInteger().testBit(0)) N.subtract(k0) else k0
        val rx = xOnly(rPoint)
        val e = BigInteger(1, taggedHash(CHALLENGE_TAG, rx, px, message)).mod(N)
        val s = k.add(e.multiply(d)).mod(N)

        val signature = rx + s.to32Bytes()
        check(verify(px, message, signature)) {
            "Schnorr 签名自校验失败（实现缺陷，不应发生）"
        }
        return signature
    }

    /**
     * 验签。
     *
     * @param publicKeyXOnly 32 字节 x-only 公钥
     * @param signature 64 字节签名（`R.x || s`）
     */
    fun verify(publicKeyXOnly: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKeyXOnly.size != 32 || message.size != 32 || signature.size != 64) return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false

        val p = try {
            liftX(publicKeyXOnly)
        } catch (_: Exception) {
            return false
        }
        val e = BigInteger(
            1,
            taggedHash(CHALLENGE_TAG, signature.copyOfRange(0, 32), publicKeyXOnly, message),
        ).mod(N)

        val rPoint = G.multiply(s).subtract(p.multiply(e)).normalize()
        if (rPoint.isInfinity) return false
        if (rPoint.affineYCoord.toBigInteger().testBit(0)) return false
        return xOnly(rPoint).contentEquals(signature.copyOfRange(0, 32))
    }

    /** 便于在日志/测试中展示。 */
    fun signHex(privateKey: ByteArray, message: ByteArray, auxRand: ByteArray): String =
        Bytes.toHex(sign(privateKey, message, auxRand))
}
