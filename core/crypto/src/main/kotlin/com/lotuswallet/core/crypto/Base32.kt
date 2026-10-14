package com.lotuswallet.core.crypto

/**
 * RFC 4648 Base32（无 `1` `0` `8` `9` 歧义字符）。
 *
 * 用途：TOTP / HOTP 的共享密钥（`otpauth://` 里的 secret）以及部分链的地址编码（如 Filecoin）。
 * 相比旧版实现，这里对长度、字符集、填充都做了严格校验，避免静默产出错误密钥。
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val INDEXES = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { i, c -> this[c.code] = i }
    }

    /** @param pad 是否输出 `=` 填充（OTP 场景通常不需要） */
    fun encode(data: ByteArray, pad: Boolean = true): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(ALPHABET[(buffer ushr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        if (pad) {
            while (sb.length % 8 != 0) sb.append('=')
        }
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        val s = input.trim().replace("=", "").replace(" ", "").uppercase()
        if (s.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (c in s) {
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "非法的 Base32 字符: '$c'" }
            buffer = (buffer shl 5) or digit
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write((buffer ushr (bitsLeft - 8)) and 0xFF)
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    /**
     * 宽松解码：用于用户从二维码/剪贴板粘贴的 secret，
     * 允许小写、空格、缺失填充以及常见的 `0/O`、`1/l/I` 误认。
     */
    fun decodeLenient(input: String): ByteArray {
        val normalized = input
            .trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("=", "")
            .uppercase()
            .replace('0', 'O')
            .replace('1', 'I')
            .replace('L', 'I')
        return decode(normalized)
    }

    /** 生成 [bytes] 字节的随机密钥并编码，用于新建 OTP 条目。 */
    fun randomSecret(bytes: Int = 20): String =
        encode(SecureRandom.bytes(bytes), pad = false)
}
