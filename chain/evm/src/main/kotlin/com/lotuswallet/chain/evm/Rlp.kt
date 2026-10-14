package com.lotuswallet.chain.evm

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * RLP（Recursive Length Prefix）编解码 —— 以太坊所有交易与区块的基本序列化格式。
 *
 * 只实现编码所需的子集（本项目不解析他人发来的原始交易，只做本地构建与签名）。
 * 实现严格遵循黄皮书定义，包含「单字节 < 0x80 直接输出」这条容易被漏掉的规则。
 */
object Rlp {

    /** 编码一个字节串。 */
    fun encodeBytes(data: ByteArray): ByteArray = when {
        data.size == 1 && (data[0].toInt() and 0xFF) < 0x80 -> data
        else -> encodeLength(data.size, OFFSET_SHORT_STRING) + data
    }

    /** 编码一个字节串。 */
    fun encode(value: ByteArray): ByteArray = encodeBytes(value)

    /** 编码一个列表（元素需已各自编码）。 */
    fun encodeList(items: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (item in items) out.write(item)
        val payload = out.toByteArray()
        return encodeLength(payload.size, OFFSET_LONG_LIST) + payload
    }

    fun encodeList(vararg items: ByteArray): ByteArray = encodeList(items.toList())

    /**
     * 编码一个无符号整数（最小长度大端表示）。
     * 0 编码为空字节串（0x80），这是以太坊交易字段的标准做法。
     */
    fun encodeQuantity(value: BigInteger): ByteArray = encodeBytes(value.toMinimalBytes())

    fun encodeQuantity(value: Long): ByteArray = encodeQuantity(BigInteger.valueOf(value))

    fun encodeQuantity(value: Int): ByteArray = encodeQuantity(value.toLong())

    /** 允许显式传入「空」的量，例如 `to` 为空表示合约创建。 */
    fun encodeOptional(bytes: ByteArray?): ByteArray = encodeBytes(bytes ?: ByteArray(0))

    private fun encodeLength(length: Int, offset: Int): ByteArray =
        if (length < 56) {
            byteArrayOf((offset + length).toByte())
        } else {
            val lengthBytes = BigInteger.valueOf(length.toLong()).toMinimalBytes()
            byteArrayOf((offset + 55 + lengthBytes.size).toByte()) + lengthBytes
        }

    private const val OFFSET_SHORT_STRING = 0x80
    private const val OFFSET_LONG_LIST = 0xc0
}

/** 去掉 BigInteger 的符号前导零（`toByteArray` 对正数会补一个 0x00）。 */
internal fun BigInteger.toMinimalBytes(): ByteArray {
    if (signum() == 0) return ByteArray(0)
    val raw = toByteArray()
    return if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
}
