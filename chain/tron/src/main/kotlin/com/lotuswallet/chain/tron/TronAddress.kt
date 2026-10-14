package com.lotuswallet.chain.tron

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Base58
import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1

/**
 * TRON 地址与派生。
 *
 * ## 地址算法（与以太坊同源）
 * ```
 * address = Base58Check( 0x41 || keccak256(uncompressedPubKey[1..])[12..32] )
 * ```
 * 与以太坊唯一的区别是多了一个网络前缀字节 `0x41`，以及最后做了 Base58Check 编码
 * （以太坊是十六进制 + EIP-55 校验和）。
 *
 * 因此**同一个私钥**在 TRON 上的地址主体（20 字节）与以太坊地址完全一致 ——
 * 这一点在测试里被显式验证，是判断实现是否正确的最强不变量。
 */
object TronAddress {

    /** TRON 主网的地址前缀字节。 */
    const val PREFIX: Byte = 0x41

    const val COIN_TYPE = 195

    /** 地址主体的长度（不含前缀）。 */
    private const val BODY_LENGTH = 20

    /** 44'/195' 路径的第 [index] 个地址。 */
    fun path(index: Int, account: Int = 0): String = "m/44'/$COIN_TYPE'/$account'/0/$index"

    fun privateKeyOf(master: Bip32.ExtendedPrivateKey, index: Int, account: Int = 0): ByteArray =
        master.derive(path(index, account)).privateKey

    /** 由未压缩公钥（65 字节，0x04 前缀）得到 Base58Check 地址。 */
    fun fromPublicKey(uncompressedPublicKey: ByteArray): String {
        require(uncompressedPublicKey.size == 65 && uncompressedPublicKey[0].toInt() == 0x04) {
            "需要 65 字节未压缩公钥（0x04 前缀），实际 ${uncompressedPublicKey.size} 字节"
        }
        val hash = Hash.keccak256(uncompressedPublicKey.copyOfRange(1, 65))
        val body = hash.copyOfRange(12, 32)
        return Base58.encodeChecked(byteArrayOf(PREFIX) + body)
    }

    fun fromPrivateKey(privateKey: ByteArray): String =
        fromPublicKey(Secp256k1.publicKeyUncompressed(privateKey))

    fun addressOf(master: Bip32.ExtendedPrivateKey, index: Int = 0, account: Int = 0): String =
        fromPrivateKey(privateKeyOf(master, index, account))

    /** 地址 → `41` 前缀的十六进制（TRON 的 RPC 接口既接受 hex 也接受 Base58）。 */
    fun toHex41(address: String): String {
        val trimmed = address.trim()
        return if (trimmed.startsWith("41") && trimmed.length == 42 && !trimmed.startsWith("T")) {
            trimmed.lowercase()
        } else {
            Bytes.toHex(Base58.decodeChecked(trimmed))
        }
    }

    /** 地址 → 不含前缀的 20 字节主体十六进制（用于 ABI 编码）。 */
    fun toBodyHex(address: String): String = toHex41(address).removePrefix("41")

    /** 十六进制（41 前缀 或 20 字节主体）→ Base58 地址。 */
    fun fromHex(hex: String): String {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X").lowercase()
        val payload = when (clean.length) {
            42 -> Bytes.fromHex(clean)
            40 -> byteArrayOf(PREFIX) + Bytes.fromHex(clean)
            else -> throw IllegalArgumentException("TRON 十六进制地址长度非法: ${clean.length}")
        }
        require(payload[0] == PREFIX) { "TRON 地址前缀必须是 0x41" }
        return Base58.encodeChecked(payload)
    }

    /** 校验地址：必须 Base58Check 合法、前缀为 0x41、主体 20 字节。 */
    fun isValid(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.length < 30 || !trimmed.startsWith("T")) return false
        val payload = runCatching { Base58.decodeChecked(trimmed) }.getOrNull() ?: return false
        return payload.size == 1 + BODY_LENGTH && payload[0] == PREFIX
    }

    /**
     * 缩略显示。
     *
     * TRON 地址只有 34 个字符（`T` + 33 位 Base58），尾部多留一位（5 而不是 EVM 的 4）
     * 更利于人工逐位核对；地址中间省略的部分永远不可见，所以缩略形式**只能用于展示**，
     * 任何校验、复制、转账都必须用完整地址。
     */
    fun shorten(address: String, head: Int = 6, tail: Int = 5): String {
        val a = address.trim()
        if (a.length <= head + tail + 1) return a
        return a.substring(0, head) + "…" + a.substring(a.length - tail)
    }

    /** 由地址得到确定性头像色种子。 */
    fun avatarColorSeed(address: String): Int {
        var acc = 0
        for (c in address.trim()) acc = acc * 31 + c.code
        return acc and 0x7FFFFFFF
    }
}
