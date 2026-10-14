package com.lotuswallet.chain.alt

import com.lotuswallet.core.crypto.Ed25519Group
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.MoneroBase58
import com.lotuswallet.core.network.chain.Ed25519Derivation

/**
 * Monero 地址。
 *
 * ## 结构
 * ```
 * 地址 = Base58Monero( 网络前缀(1) || 花费公钥 A(32) || 查看公钥 B(32) || Keccak 校验和(4) )
 * 主网前缀 = 0x12（18）→ 得到 69 字节 → 95 个字符
 * ```
 *
 * ## 密钥
 * ```
 * a（花费私钥） = sc_reduce32(派生得到的 32 字节)
 * A = a·G
 * b（查看私钥） = sc_reduce32(Keccak256(a))
 * B = b·G
 * ```
 * 与 Solana / ICP 不同，这里的私钥是**标量本身**而不是「种子」，
 * 因此必须走 [Ed25519Group] 的裸标量乘法，而不能用 EdDSA 的种子语义。
 *
 * 派生路径取 `m/44'/128'/account'`（Ledger / Trust Wallet 的 BIP-44 约定）。
 */
object MoneroAddress {

    /** 主网标准地址前缀。 */
    const val MAINNET_PREFIX = 18

    /** 测试网（stagenet）标准地址前缀。 */
    const val TESTNET_PREFIX = 53

    fun pathFor(account: Int, coinType: Int = 128): String = "m/44'/$coinType'/$account'"

    fun address(seed: ByteArray, path: String, prefix: Int = MAINNET_PREFIX): String {
        val material = AltKeyDerivation.keyMaterial(seed, path, Ed25519Derivation.SLIP10)
        val spendSecret = Ed25519Group.scReduce32(material)
        val viewSecret = Ed25519Group.scReduce32(Hash.keccak256(spendSecret))

        val body = byteArrayOf(prefix.toByte()) +
            Ed25519Group.scalarMultBase(spendSecret) +
            Ed25519Group.scalarMultBase(viewSecret)
        val checksum = Hash.keccak256(body).copyOfRange(0, 4)
        return MoneroBase58.encode(body + checksum)
    }

    /** 校验地址：长度、前缀、以及 Keccak 校验和。 */
    fun isValid(address: String, prefix: Int = MAINNET_PREFIX): Boolean {
        val decoded = runCatching { MoneroBase58.decode(address.trim()) }.getOrNull() ?: return false
        if (decoded.size != 69) return false
        if ((decoded[0].toInt() and 0xFF) != prefix) return false
        val body = decoded.copyOfRange(0, 65)
        val checksum = decoded.copyOfRange(65, 69)
        return Hash.keccak256(body).copyOfRange(0, 4).contentEquals(checksum)
    }
}
