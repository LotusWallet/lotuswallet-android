package com.lotuswallet.chain.alt

import com.lotuswallet.core.crypto.Base32
import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1

/**
 * Filecoin 地址。
 *
 * ## 结构
 * ```
 * 地址文本 = 网络前缀('f'/'t') + 协议号('1') + Base32小写无填充(负载 || 校验和)
 * 负载     = BLAKE2b-160(公钥)        // 20 字节
 * 校验和   = BLAKE2b-4(协议字节 || 负载)
 * ```
 *
 * ## 一个只看代码看不出来的坑
 * 规范里的 `Encode` 是 `network + protocol + base32(payload + checksum)` ——
 * 参与 base32 的只有**负载与校验和**，协议号只作为那个字符 `1` 出现在前缀里。
 * 如果顺手把协议字节也塞进 base32（「反正它也是地址的一部分」），
 * 编出来是 25 字节 → 40 字符，整条地址变成 42 个字符；
 * 而真实主网的 `f1` 地址是 **41** 个字符。多出来这一个字符会让地址全网无人认识，
 * 输入钱包时还会被当成「校验和错误」。
 *
 * 本实现只生成 **`f1`（secp256k1）** 地址：路径 `m/44'/461'/0'/0/0`，
 * 负载取**未压缩**公钥（65 字节）的 BLAKE2b-160 —— Filecoin 用的是未压缩公钥，
 * 用压缩公钥会得到另一个地址。
 *
 * `f3`（BLS）地址需要 BLS12-381，本版本不生成，但 [isValid] 能识别其形态。
 */
object FilecoinAddress {

    /** 协议号：secp256k1 地址。 */
    const val PROTOCOL_SECP256K1 = 1

    /** 协议号：BLS 地址。 */
    const val PROTOCOL_BLS = 3

    /** 校验和长度（字节）。 */
    private const val CHECKSUM_BYTES = 4

    fun pathFor(account: Int, coinType: Int = 461): String = "m/44'/$coinType'/$account'/0/0"

    fun address(seed: ByteArray, path: String, network: String = "f"): String {
        val privateKey = Bip32.fromSeed(seed).derive(path).privateKey
        val payload = Hash.blake2b(Secp256k1.publicKeyUncompressed(privateKey), 20)
        return encode(network, PROTOCOL_SECP256K1, payload)
    }

    fun encode(network: String, protocol: Int, payload: ByteArray): String {
        val checksum = checksum(protocol, payload)
        // 只编 (负载 || 校验和)；协议号体现在前缀字符里
        val encoded = Base32.encode(payload + checksum, pad = false).lowercase()
        return "$network$protocol$encoded"
    }

    /** 校验和：`BLAKE2b-4(协议字节 || 负载)`，注意协议字节**参与**校验和计算。 */
    private fun checksum(protocol: Int, payload: ByteArray): ByteArray =
        Hash.blake2b(byteArrayOf(protocol.toByte()) + payload, CHECKSUM_BYTES)

    /** 识别度较高的结构性校验（前缀 + Base32 可解码 + 负载长度 + 校验和）。 */
    fun isValid(address: String, network: String = "f"): Boolean {
        val trimmed = address.trim()
        if (trimmed.length < 3) return false
        if (!trimmed.startsWith(network)) return false
        val protocol = trimmed[1].digitToIntOrNull() ?: return false
        val decoded = runCatching { Base32.decode(trimmed.substring(2).uppercase()) }.getOrNull()
            ?: return false

        val payloadSize = when (protocol) {
            PROTOCOL_SECP256K1 -> 20
            PROTOCOL_BLS -> 48
            else -> return false
        }
        if (decoded.size != payloadSize + CHECKSUM_BYTES) return false
        val payload = decoded.copyOfRange(0, payloadSize)
        val checksum = decoded.copyOfRange(payloadSize, decoded.size)
        return checksum(protocol, payload).contentEquals(checksum)
    }
}
