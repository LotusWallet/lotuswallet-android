package com.lotuswallet.chain.alt

import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Slip10
import com.lotuswallet.core.network.chain.Ed25519Derivation

/**
 * Ed25519 系链的公共派生步骤。
 *
 * 三种链（Solana / ICP / Monero）最终都需要一支 **32 字节、随后被当作 ed25519 种子**的材料，
 * 差异只在中间过程：
 * - SLIP-0010（HMAC key = `"ed25519 seed"`，全硬化）—— Solana / Monero；
 * - BIP-32（HMAC key = `"Bitcoin seed"`，允许非硬化）—— ICP（与官方 `dfx` 一致）。
 *
 * 把这一步单独拎出来，是为了让三个地址编码器都只关心「拿到 32 字节之后怎么编码」。
 */
object AltKeyDerivation {

    /** 由 BIP-39 种子沿路径得到 32 字节密钥材料。 */
    fun keyMaterial(seed: ByteArray, path: String, derivation: Ed25519Derivation): ByteArray =
        when (derivation) {
            Ed25519Derivation.SLIP10 -> Slip10.derive(seed, path).key
            Ed25519Derivation.BIP32 -> Bip32.fromSeed(seed).derive(path).privateKey
        }
}
