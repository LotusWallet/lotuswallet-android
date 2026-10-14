package com.lotuswallet.core.crypto

import com.lotuswallet.core.common.Bytes
import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger

/**
 * secp256k1 椭圆曲线运算。BTC / ETH 及所有 EVM 链 / TRON 共用同一套曲线。
 *
 * 关键设计：
 * - 签名采用 RFC 6979 确定性 nonce（`HMacDSAKCalculator`），杜绝弱随机数导致私钥泄露；
 * - 默认强制 low-S 规范化（BIP-62 / EIP-2 要求），避免交易被拒；
 * - 提供可恢复签名（`recId`），这是 EVM 与 TRON 交易签名的前提。
 */
object Secp256k1 {

    private val CURVE_PARAMS = CustomNamedCurves.getByName("secp256k1")
    val CURVE: ECDomainParameters = ECDomainParameters(
        CURVE_PARAMS.curve,
        CURVE_PARAMS.g,
        CURVE_PARAMS.n,
        CURVE_PARAMS.h,
    )

    val N: BigInteger = CURVE.n
    val HALF_N: BigInteger = N.shiftRight(1)
    private val G: ECPoint = CURVE.g
    private val CONVERTER = X9IntegerConverter()

    /** 单次签名的结果。 */
    data class Signature(val r: BigInteger, val s: BigInteger, val recId: Int) {

        /** 64 字节 `r || s`，EVM / TRON 使用。 */
        fun toCompact(): ByteArray =
            Bytes.leftPad(r.toByteArray().stripSign(), 32) + Bytes.leftPad(s.toByteArray().stripSign(), 32)

        /** DER 编码，比特币使用。 */
        fun toDer(): ByteArray {
            val rBytes = r.toByteArray().stripSign()
            val sBytes = s.toByteArray().stripSign()
            val rEnc = derInteger(rBytes)
            val sEnc = derInteger(sBytes)
            val len = rEnc.size + sEnc.size
            val out = java.io.ByteArrayOutputStream()
            out.write(0x30)
            if (len < 128) {
                out.write(len)
            } else {
                val lenBytes = BigInteger.valueOf(len.toLong()).toByteArray().stripSign()
                out.write(0x80 or lenBytes.size)
                out.write(lenBytes)
            }
            out.write(rEnc)
            out.write(sEnc)
            return out.toByteArray()
        }

        private fun derInteger(value: ByteArray): ByteArray {
            val needsPad = value.isNotEmpty() && (value[0].toInt() and 0x80) != 0
            val body = if (needsPad) byteArrayOf(0) + value else value
            return byteArrayOf(0x02, body.size.toByte()) + body
        }
    }

    private fun ByteArray.stripSign(): ByteArray =
        if (size > 1 && this[0].toInt() == 0) copyOfRange(1, size) else this

    /** 私钥（32 字节）→ 公钥点。 */
    fun publicPoint(privateKey: ByteArray): ECPoint {
        val d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < N) { "私钥超出 secp256k1 有效范围" }
        return G.multiply(d).normalize()
    }

    /** 私钥 → 压缩公钥（33 字节，BTC / TRON 常用）。 */
    fun publicKeyCompressed(privateKey: ByteArray): ByteArray = publicPoint(privateKey).getEncoded(true)

    /** 私钥 → 未压缩公钥（65 字节，含 `0x04` 前缀，EVM 常用）。 */
    fun publicKeyUncompressed(privateKey: ByteArray): ByteArray = publicPoint(privateKey).getEncoded(false)

    /** 把 SEC1 编码的公钥字节解析为曲线点。 */
    fun pointFromBytes(bytes: ByteArray): ECPoint {
        require(bytes.size == 33 || bytes.size == 65) { "公钥长度必须为 33 或 65 字节" }
        return CURVE.curve.decodePoint(bytes).normalize()
    }

    fun encodePoint(point: ECPoint, compressed: Boolean): ByteArray = point.normalize().getEncoded(compressed)

    /**
     * 对 32 字节摘要做 ECDSA 签名。
     *
     * @param privateKey 32 字节私钥
     * @param messageHash 32 字节摘要（调用方负责先做哈希，例如 ETH 用 Keccak-256）
     */
    fun sign(privateKey: ByteArray, messageHash: ByteArray): Signature {
        require(messageHash.size == 32) { "待签摘要必须为 32 字节" }
        val d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < N) { "私钥超出 secp256k1 有效范围" }

        val signer = ECDSASigner(HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, CURVE))
        val components = signer.generateSignature(messageHash)
        var r = components[0]
        var s = components[1]

        // low-S 规范化（EIP-2 / BIP-62）
        if (s > HALF_N) s = N.subtract(s)

        // 恢复标识必须与「签名当时的 nonce 点」一致。
        // 注意：对同一个 (r, s)，recId=0 与 recId=1 恢复出的两个公钥都能通过验签
        // （这是 ECDSA 可恢复签名的固有二义性），所以不能靠验签来挑选 recId，
        // 必须与已知的真实公钥比对。
        val expectedPublicKey = G.multiply(d).normalize().getEncoded(true)
        val recId = (0..3).firstOrNull { candidate ->
            val recovered = try {
                recoverPublicPoint(messageHash, r, s, candidate)
            } catch (_: Exception) {
                null
            }
            recovered != null && recovered.getEncoded(true).contentEquals(expectedPublicKey)
        } ?: 0

        return Signature(r, s, recId)
    }

    fun verify(publicKey: ByteArray, messageHash: ByteArray, r: BigInteger, s: BigInteger): Boolean {
        val signer = ECDSASigner()
        signer.init(false, ECPublicKeyParameters(pointFromBytes(publicKey), CURVE))
        return signer.verifySignature(messageHash, r, s)
    }

    /** 由签名恢复公钥点；这也是 EVM 地址反推（`ecrecover`）的基础。 */
    fun recoverPublicPoint(messageHash: ByteArray, r: BigInteger, s: BigInteger, recId: Int): ECPoint {
        require(recId in 0..3) { "recId 必须在 0..3" }
        val x = r + BigInteger.valueOf((recId / 2).toLong()).multiply(N)
        val rPoint = decompressKey(x, (recId and 1) == 1)
        require(rPoint.multiply(N).isInfinity) { "恢复点不在 n 阶子群内" }

        val e = BigInteger(1, messageHash)
        val eInv = BigInteger.ZERO.subtract(e).mod(N)
        val rInv = r.modInverse(N)
        val srInv = rInv.multiply(s).mod(N)
        val eInvrInv = rInv.multiply(eInv).mod(N)
        val q = ECAlgorithms.sumOfTwoMultiplies(G, eInvrInv, rPoint, srInv).normalize()
        require(!q.isInfinity) { "公钥恢复失败" }
        return q
    }

    private fun decompressKey(x: BigInteger, yOdd: Boolean): ECPoint {
        val x9 = CONVERTER.integerToBytes(x, CONVERTER.getByteLength(CURVE.curve))
        val prefix: Byte = if (yOdd) 0x03 else 0x02
        return CURVE.curve.decodePoint(byteArrayOf(prefix) + x9)
    }

    /** 生成一个合法的随机私钥。 */
    fun randomPrivateKey(): ByteArray =
        Bytes.leftPad(SecureRandom.bigInteger(N).toByteArray().stripSign(), 32)
}
