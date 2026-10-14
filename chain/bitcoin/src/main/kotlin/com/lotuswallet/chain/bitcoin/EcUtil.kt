package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.common.Bytes
import java.math.BigInteger

/**
 * 椭圆曲线相关的内部工具。放在包里作为顶层函数，便于 [Schnorr] 与 [Taproot] 共用，
 * 且不污染公开 API（全部 `internal`）。
 */

/** 大整数 → 32 字节大端；若 `toByteArray()` 带符号前缀则丢弃。 */
internal fun BigInteger.to32Bytes(): ByteArray {
    val raw = toByteArray()
    val trimmed = when {
        raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
        else -> raw
    }
    return Bytes.leftPad(trimmed, 32)
}

/** 逐字节异或（长度必须一致）。 */
internal fun ByteArray.xorWith(other: ByteArray): ByteArray {
    require(size == other.size) { "异或双方长度必须一致：$size vs ${other.size}" }
    return ByteArray(size) { (this[it].toInt() xor other[it].toInt()).toByte() }
}

/** 大整数 → 32 字节大端（用于把私钥字节补足长度）。 */
internal fun ByteArray.padTo32(): ByteArray = Bytes.leftPad(this, 32)
