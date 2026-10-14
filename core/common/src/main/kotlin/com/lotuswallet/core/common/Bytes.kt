package com.lotuswallet.core.common

/**
 * 字节 / 十六进制 工具。
 *
 * 说明：项目内所有十六进制一律使用小写、无 `0x` 前缀的内部表示，
 * 仅在展示层添加前缀。避免旧版代码中大小写混用导致的地址/哈希比较 bug。
 */
object Bytes {

    private const val HEX = "0123456789abcdef"

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun toHex(bytes: ByteArray, prefix: Boolean): String =
        if (prefix) "0x" + toHex(bytes) else toHex(bytes)

    fun fromHex(hex: String): ByteArray {
        val s = hex.removePrefix("0x").removePrefix("0X")
        require(s.length % 2 == 0) { "十六进制字符串长度必须为偶数: ${s.length}" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "非法的十六进制字符" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun fromHexOrNull(hex: String): ByteArray? = try {
        fromHex(hex)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 常量时间比较，防止时序侧信道。用于校验 MAC / 校验和。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }

    /** 在左侧补零到指定长度；若已超长则抛错（不做静默截断）。 */
    fun leftPad(bytes: ByteArray, length: Int): ByteArray {
        require(bytes.size <= length) { "字节长度 ${bytes.size} 超过目标长度 $length" }
        if (bytes.size == length) return bytes
        val out = ByteArray(length)
        System.arraycopy(bytes, 0, out, length - bytes.size, bytes.size)
        return out
    }

    fun ByteArray.copyRange(from: Int, toExclusive: Int): ByteArray =
        copyOfRange(from, toExclusive)
}
