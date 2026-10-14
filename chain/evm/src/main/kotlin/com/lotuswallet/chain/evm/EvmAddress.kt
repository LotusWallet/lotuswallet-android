package com.lotuswallet.chain.evm

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1

/**
 * EVM 地址工具。
 *
 * 地址 = `keccak256(未压缩公钥去掉首字节)[12..32]`。
 * 展示时按 **EIP-55** 做大小写校验和，可在无网络的情况下发现地址复制/粘贴错误。
 */
object EvmAddress {

    private const val HEX = "0123456789abcdef"

    /**
     * 由 65 字节未压缩公钥（含 0x04 前缀）得到地址。
     *
     * 返回的是**唯一的规范形式**：`0x` 前缀 + EIP-55 大小写校验和。
     * 整个项目里所有产生地址的入口都返回这一种形式，避免同一个地址出现
     * 「带前缀/不带前缀、大写/小写」多种写法，进而在比较、去重、入库时出错。
     */
    fun fromPublicKey(uncompressedPublicKey: ByteArray): String =
        checksum("0x" + bodyOf(uncompressedPublicKey))

    /** 由 33 字节压缩公钥得到地址（内部解压后计算）。 */
    fun fromCompressedPublicKey(compressedPublicKey: ByteArray): String {
        val point = Secp256k1.pointFromBytes(compressedPublicKey)
        return fromPublicKey(point.getEncoded(false))
    }

    /** 由私钥直接得到地址（带 `0x` 前缀与 EIP-55 校验和）。 */
    fun fromPrivateKey(privateKey: ByteArray): String =
        checksum("0x" + bodyOf(Secp256k1.publicKeyUncompressed(privateKey)))

    /**
     * 地址的 20 字节主体，小写十六进制、**不带** `0x`。
     *
     * 只在需要「裸字节」的场合使用（RLP 编码、ERC-20 ABI 参数、哈希输入等）；
     * 对外展示与存储请使用 [fromPublicKey] 的规范形式。
     */
    fun bodyOf(uncompressedPublicKey: ByteArray): String {
        require(uncompressedPublicKey.size == 65 && uncompressedPublicKey[0].toInt() == 0x04) {
            "需要 65 字节未压缩公钥（0x04 前缀），实际 ${uncompressedPublicKey.size} 字节"
        }
        val hash = Hash.keccak256(uncompressedPublicKey.copyOfRange(1, 65))
        return Bytes.toHex(hash.copyOfRange(12, 32))
    }

    /** 去掉 `0x` 并转小写，得到可比较的标准形式。 */
    fun normalize(address: String): String = address.trim().removePrefix("0x").removePrefix("0X").lowercase()

    /** 是否是一个格式合法的地址（长度 20 字节 + 十六进制）。 */
    fun isValid(address: String): Boolean {
        val hex = normalize(address)
        if (hex.length != 40) return false
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return false
        // 若原串带大小写混合，则检查 EIP-55 校验和
        val body = address.trim().removePrefix("0x").removePrefix("0X")
        val hasUpper = body.any { it.isUpperCase() }
        val hasLower = body.any { it.isLowerCase() }
        if (hasUpper && hasLower) {
            return checksum("0x$hex") == "0x$body"
        }
        return true
    }

    /**
     * EIP-55 校验和编码。
     *
     * 规则：对地址的小写十六进制文本（不含 `0x`）做 keccak256，
     * 哈希对应半字节 >= 8 的字母位改为大写。
     */
    fun checksum(address: String): String {
        val hex = normalize(address)
        require(hex.length == 40) { "地址必须是 40 位十六进制，实际 ${hex.length}" }
        val hash = Hash.keccak256(hex.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder(42)
        sb.append("0x")
        for (i in hex.indices) {
            val c = hex[i]
            if (c in '0'..'9') {
                sb.append(c)
            } else {
                val nibble = (hash[i / 2].toInt() ushr (if (i % 2 == 0) 4 else 0)) and 0x0F
                sb.append(if (nibble >= 8) c.uppercaseChar() else c)
            }
        }
        return sb.toString()
    }

    /**
     * 缩短显示：`0x1234…abcd`。用于列表与确认页。
     */
    fun shorten(address: String, head: Int = 6, tail: Int = 4): String {
        val a = address.trim()
        if (a.length <= head + tail + 1) return a
        return a.substring(0, head) + "…" + a.substring(a.length - tail)
    }

    /** 生成一个确定性的占位色（用于无图标时的头像），与地址稳定对应。 */
    fun avatarColorSeed(address: String): Int {
        val hex = normalize(address)
        var acc = 0
        for (c in hex) acc = acc * 31 + c.code
        return acc and 0x7FFFFFFF
    }

    internal fun hexChar(value: Int): Char = HEX[value and 0x0F]
}
