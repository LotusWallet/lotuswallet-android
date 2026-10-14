package com.lotuswallet.chain.alt

import com.lotuswallet.core.crypto.Base58
import com.lotuswallet.core.crypto.Ed25519
import com.lotuswallet.core.network.chain.Ed25519Derivation

/**
 * Solana 地址：`Base58(32 字节 ed25519 公钥)`。
 *
 * 派生路径 `m/44'/501'/account'/0'`（Phantom / Trust Wallet 的约定，全部硬化）。
 * 私钥、公钥都是 32 字节，地址就是公钥本身 —— 没有校验和、没有版本字节，
 * 因此**只能靠长度与合法性判断**，这也正是 Solana 地址必须逐字符核对的原因。
 */
object SolanaAddress {

    fun pathFor(account: Int, coinType: Int = 501): String = "m/44'/$coinType'/$account'/0'"

    fun address(seed: ByteArray, path: String): String {
        val material = AltKeyDerivation.keyMaterial(seed, path, Ed25519Derivation.SLIP10)
        return Base58.encode(Ed25519.publicKey(material))
    }

    /**
     * 结构性校验：Base58 可解码、且恰好 32 字节。
     *
     * **这不是校验和**，也做不了校验和 —— Solana 地址就是裸公钥，没有版本字节、
     * 没有 checksum。因此「少打一位但恰好仍解出 32 字节」的地址无法被识别
     * （44 字符地址去掉末位后有相当大概率仍是 32 字节）。
     * 收款前逐字符核对地址是这一族链的固有要求，不能靠这里的返回值兜底。
     */
    fun isValid(address: String): Boolean = runCatching {
        Base58.decode(address.trim()).size == 32
    }.getOrDefault(false)
}
