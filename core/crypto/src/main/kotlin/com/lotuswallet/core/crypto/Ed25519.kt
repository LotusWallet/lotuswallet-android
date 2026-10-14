package com.lotuswallet.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/**
 * ed25519 公钥推导（种子 → 公钥）。
 *
 * 走 BouncyCastle 的标准实现：`SHA-512(seed)` → clamp → 标量乘基点。
 * Solana / ICP 的地址就是「32 字节公钥」的不同编码，因此只需要这一步。
 *
 * Monero 例外：它的私钥是**标量本身**而不是种子，所以另见 [Ed25519Group]。
 */
object Ed25519 {

    /** 由 32 字节种子得到 32 字节压缩公钥。 */
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "ed25519 种子必须是 32 字节，实际 ${seed.size}" }
        val private = Ed25519PrivateKeyParameters(seed, 0)
        return private.generatePublicKey().encoded
    }

    /** 由标量直接算公钥（把标量当成种子用）。Monero 不用这个，见 [Ed25519Group]。 */
    fun publicKeyFromScalar(scalar: ByteArray): ByteArray = publicKey(scalar)
}
