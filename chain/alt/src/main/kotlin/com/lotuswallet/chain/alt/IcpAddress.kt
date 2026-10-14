package com.lotuswallet.chain.alt

import com.lotuswallet.core.crypto.Ed25519
import com.lotuswallet.core.crypto.Principal
import com.lotuswallet.core.network.chain.Ed25519Derivation

/**
 * Internet Computer（ICP）的「地址」。
 *
 * ICP 没有账户地址，只有 **principal**。由助记词派生出来的自认证 principal：
 * ```
 * 32 字节密钥（BIP-32，路径 m/44'/223'/0'/0/0）
 *   → ed25519 公钥
 *   → sha224(DER 编码)
 *   → base32(crc32 || sha224)  =  abcde-...-wxyz
 * ```
 * 路径与算法刻意对齐官方 `dfx`：它用 secp256k1 的 BIP-32 推导出 32 字节，
 * 再把这 32 字节当作 ed25519 种子 —— 换成 SLIP-0010 会得到完全不同的 principal。
 */
object IcpAddress {

    fun pathFor(account: Int, coinType: Int = 223): String = "m/44'/$coinType'/$account'/0/0"

    fun address(seed: ByteArray, path: String): String {
        val material = AltKeyDerivation.keyMaterial(seed, path, Ed25519Derivation.BIP32)
        return Principal.selfAuthenticating(Ed25519.publicKey(material))
    }

    fun isValid(address: String): Boolean = Principal.isValid(address.trim())
}
