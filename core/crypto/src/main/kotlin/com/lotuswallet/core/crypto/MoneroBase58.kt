package com.lotuswallet.core.crypto

import java.math.BigInteger

/**
 * Monero 的 Base58 变体。
 *
 * ## 与比特币 Base58 的区别
 * 比特币是「整串一个数，前导 0 用 '1' 表示」；Monero 是**按 8 字节分块**，
 * 每块固定输出 11 个字符（不足 8 字节的尾块按查表输出 2/3/5/6/7/9/10 个字符）。
 *
 * 这个区别很关键：门罗地址是 69 字节 → 8 个满块 + 1 个 5 字节尾块
 * → 88 + 7 = **95 个字符**。用比特币 Base58 编出来的长度与数值都不对，
 * 因此地址在别的钱包里会直接失效。
 */
object MoneroBase58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** 未编码块大小 → 编码后字符数。下标即未编码字节数（1..8）。 */
    private val ENCODED_SIZES = intArrayOf(0, 2, 3, 5, 6, 7, 9, 10, 11)

    private const val FULL_BLOCK = 8
    private const val FULL_ENCODED = 11

    private val DIGITS = HashMap<Char, Int>().apply {
        ALPHABET.forEachIndexed { index, c -> put(c, index) }
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder()
        var offset = 0
        while (data.size - offset >= FULL_BLOCK) {
            sb.append(encodeBlock(data, offset, FULL_BLOCK, FULL_ENCODED))
            offset += FULL_BLOCK
        }
        val tail = data.size - offset
        if (tail > 0) sb.append(encodeBlock(data, offset, tail, ENCODED_SIZES[tail]))
        return sb.toString()
    }

    /** @throws IllegalArgumentException 输入含非法字符或长度不合法 */
    fun decode(text: String): ByteArray {
        val input = text.trim()
        if (input.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (input.length - offset >= FULL_ENCODED) {
            out.write(decodeBlock(input, offset, FULL_ENCODED, FULL_BLOCK))
            offset += FULL_ENCODED
        }
        val tailChars = input.length - offset
        if (tailChars > 0) {
            val decodedSize = DECODED_SIZES[tailChars]
            require(decodedSize > 0) { "Monero Base58 尾块长度非法: $tailChars" }
            out.write(decodeBlock(input, offset, tailChars, decodedSize))
        }
        return out.toByteArray()
    }

    /**
     * 解码块字符数 → 字节数（[ENCODED_SIZES] 的逆）。
     *
     * 用 -1 而不是 0 表示「不存在这种长度」：合法尾块只有 2/3/5/6/7/9/10 个字符，
     * 而某些长度（如 1 或 4）在编码侧根本不会出现。若用 0 占位，
     * 校验就会被跳过，错误信息也会退化成「越界」而看不出真正原因。
     */
    private val DECODED_SIZES = IntArray(12) { -1 }.apply {
        ENCODED_SIZES.forEachIndexed { bytes, chars -> if (bytes > 0) this[chars] = bytes }
    }

    private fun encodeBlock(data: ByteArray, offset: Int, bytes: Int, chars: Int): String {
        var num = BigInteger(1, data.copyOfRange(offset, offset + bytes))
        val base = BigInteger.valueOf(58)
        val res = CharArray(chars)
        for (i in chars - 1 downTo 0) {
            val divRem = num.divideAndRemainder(base)
            res[i] = ALPHABET[divRem[1].toInt()]
            num = divRem[0]
        }
        return String(res)
    }

    private fun decodeBlock(text: String, offset: Int, chars: Int, bytes: Int): ByteArray {
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (i in 0 until chars) {
            val digit = DIGITS[text[offset + i]]
                ?: throw IllegalArgumentException("非法的 Monero Base58 字符: '${text[offset + i]}'")
            num = num.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }
        val be = num.toByteArray()
        val trimmed = if (be.size > 1 && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
        require(trimmed.size <= bytes) { "Monero Base58 块数值超出 $bytes 字节" }
        val out = ByteArray(bytes)
        System.arraycopy(trimmed, 0, out, bytes - trimmed.size, trimmed.size)
        return out
    }
}
