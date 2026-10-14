package com.lotuswallet.core.crypto

/**
 * Bech32 / Bech32m 编解码（BIP-173 与 BIP-350）。
 *
 * 用于：BTC 的 P2WPKH / P2WSH / P2TR 地址，以及其他采用 bech32 的链。
 * 相比旧版实现，这里同时支持 bech32m（Taproot 必需）并显式区分解码时的校验和常量。
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.apply {
        CHARSET.forEachIndexed { i, c -> this[c.code] = i }
    }

    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3

    enum class Variant { BECH32, BECH32M }

    data class Decoded(val hrp: String, val data: ByteArray, val variant: Variant) {
        override fun equals(other: Any?): Boolean =
            other is Decoded && hrp == other.hrp && data.contentEquals(other.data) && variant == other.variant

        override fun hashCode(): Int = (hrp.hashCode() * 31 + data.contentHashCode()) * 31 + variant.hashCode()
    }

    private fun polymod(values: IntArray): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) chk = chk xor generators[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun createChecksum(hrp: String, data: IntArray, variant: Variant): IntArray {
        val constant = if (variant == Variant.BECH32) BECH32_CONST else BECH32M_CONST
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor constant
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Variant? {
        val mod = polymod(hrpExpand(hrp) + data)
        return when (mod) {
            BECH32_CONST -> Variant.BECH32
            BECH32M_CONST -> Variant.BECH32M
            else -> null
        }
    }

    /** 把 8 位字节序列转换成 5 位整数序列（用于 bech32 数据部分）。 */
    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        for (b in data) {
            val value = b.toInt() and 0xFF
            require(value ushr fromBits == 0) { "输入值超出 $fromBits 位范围" }
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.write((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc shl (toBits - bits)) and maxv)
        } else {
            require(bits < fromBits) { "存在未对齐的填充位" }
            require(((acc shl (toBits - bits)) and maxv) == 0) { "填充位非零" }
        }
        return out.toByteArray()
    }

    fun encode(hrp: String, data5: ByteArray, variant: Variant = Variant.BECH32): String {
        require(hrp.isNotEmpty()) { "hrp 不能为空" }
        val lower = hrp.lowercase()
        val dataInts = IntArray(data5.size) { data5[it].toInt() and 0x1F }
        val checksum = createChecksum(lower, dataInts, variant)
        val sb = StringBuilder(lower).append('1')
        for (d in dataInts + checksum) sb.append(CHARSET[d])
        return sb.toString()
    }

    /** 编码 SegWit 地址：`hrp` + witness version + 程序。 */
    fun encodeSegwit(hrp: String, witnessVersion: Int, witnessProgram: ByteArray): String {
        require(witnessVersion in 0..16) { "witness version 必须在 0..16" }
        require(witnessProgram.size in 2..40) { "witness program 长度必须在 2..40 字节" }
        if (witnessVersion == 0) {
            require(witnessProgram.size == 20 || witnessProgram.size == 32) {
                "v0 witness program 必须为 20 或 32 字节"
            }
        }
        val converted = convertBits(witnessProgram, 8, 5, true)
        val data = ByteArray(1 + converted.size)
        data[0] = witnessVersion.toByte()
        System.arraycopy(converted, 0, data, 1, converted.size)
        val variant = if (witnessVersion == 0) Variant.BECH32 else Variant.BECH32M
        return encode(hrp, data, variant)
    }

    fun decode(input: String): Decoded {
        require(input.length in 8..90) { "bech32 长度非法" }
        require(input.lowercase() == input || input.uppercase() == input) { "不允许大小写混用" }
        val s = input.lowercase()
        val pos = s.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= s.length) { "缺少分隔符或校验和长度不足" }
        val hrp = s.substring(0, pos)
        val dataPart = s.substring(pos + 1)
        val data = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val c = dataPart[i]
            val rev = if (c.code < 128) CHARSET_REV[c.code] else -1
            require(rev >= 0) { "非法的 bech32 字符: '$c'" }
            data[i] = rev
        }
        val variant = verifyChecksum(hrp, data) ?: throw IllegalArgumentException("bech32 校验和校验失败")
        val payload = data.copyOfRange(0, data.size - 6)
        return Decoded(hrp, ByteArray(payload.size) { payload[it].toByte() }, variant)
    }
}
