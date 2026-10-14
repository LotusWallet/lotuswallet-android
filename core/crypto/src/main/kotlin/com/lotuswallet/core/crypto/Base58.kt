package com.lotuswallet.core.crypto

import java.math.BigInteger

/**
 * Base58 / Base58Check 编解码（比特币字母表）。
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { i, c -> this[c.code] = i }
    }
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++

        var num = BigInteger(1, input)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val divRem = num.divideAndRemainder(BASE)
            num = divRem[0]
            sb.append(ALPHABET[divRem[1].toInt()])
        }
        repeat(zeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        require(input.isNotEmpty()) { "Base58 输入为空" }
        var num = BigInteger.ZERO
        for (c in input) {
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "非法的 Base58 字符: '$c'" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = num.toByteArray().let {
            // BigInteger 会补一个前导 0x00 表示正数，需剔除
            if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
        }
        var zeros = 0
        while (zeros < input.length && input[zeros] == ALPHABET[0]) zeros++
        return ByteArray(zeros) + bytes
    }

    /** Base58Check：4 字节 double-SHA256 校验和。 */
    fun encodeChecked(payload: ByteArray): String =
        encode(payload + Hash.doubleSha256(payload).copyOfRange(0, 4))

    fun decodeChecked(input: String): ByteArray {
        val decoded = decode(input)
        require(decoded.size >= 5) { "Base58Check 数据过短" }
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val expected = Hash.doubleSha256(payload).copyOfRange(0, 4)
        require(com.lotuswallet.core.common.Bytes.constantTimeEquals(checksum, expected)) {
            "Base58Check 校验和不匹配"
        }
        return payload
    }
}
