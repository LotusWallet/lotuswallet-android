package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Secp256k1
import java.math.BigInteger

/**
 * Taproot（BIP-86 单密钥 / BIP-341 输出键）支持。
 *
 * ## 输出键是怎么来的
 * Taproot 地址锁定的不是内部公钥 `P`，而是**调整后**的输出键：
 * ```
 * Q = P + int(taggedHash("TapTweak", x(P))) · G
 * ```
 * 因为这里只用「密钥路径花费」（没有脚本树），merkle root 为空，
 * 所以 tweak 只依赖 `x(P)`。
 *
 * ## 为什么私钥也要调整
 * 签名时用的私钥必须是 `d + t (mod n)`，并且要先处理 y 的奇偶
 * （x-only 公钥只保留 x，隐含选择 y 为偶数的点，必要时用 `n - d`）。
 * 漏掉任何一步都会导致地址或签名不匹配。
 */
object Taproot {

    private val N: BigInteger = Secp256k1.N

    /** 由内部 x-only 公钥计算输出键（32 字节）。 */
    fun outputKeyFromInternalKey(internalKeyXOnly: ByteArray): ByteArray {
        require(internalKeyXOnly.size == 32) { "内部公钥必须是 32 字节 x-only 公钥" }
        val tweak = BigInteger(1, Schnorr.taggedHash("TapTweak", internalKeyXOnly))
        val p = Schnorr.liftX(internalKeyXOnly)
        val q = p.add(Secp256k1.CURVE.g.multiply(tweak)).normalize()
        return Schnorr.xOnly(q)
    }

    /** 由私钥计算输出键。 */
    fun outputKey(privateKey: ByteArray): ByteArray =
        outputKeyFromInternalKey(Schnorr.publicKey(privateKey))

    /** 由私钥计算**调整后**的私钥（用于 BIP-341 密钥路径签名）。 */
    fun tweakedPrivateKey(privateKey: ByteArray): ByteArray {
        val d = Schnorr.evenYScalar(privateKey)
        val tweak = BigInteger(1, Schnorr.taggedHash("TapTweak", Schnorr.publicKey(privateKey)))
        return d.add(tweak).mod(N).to32Bytes()
    }

    /** 内部公钥（32 字节），即 BIP-86 里的 `x(P)`。 */
    fun internalKey(privateKey: ByteArray): ByteArray = Schnorr.publicKey(privateKey)

    /** 由输出键得到 P2TR 的 scriptPubKey。 */
    fun scriptPubKey(outputKey: ByteArray): ByteArray = Script.p2trScriptPubKey(outputKey)

    /** 便于调试的十六进制输出。 */
    fun outputKeyHex(privateKey: ByteArray): String = Bytes.toHex(outputKey(privateKey))
}
