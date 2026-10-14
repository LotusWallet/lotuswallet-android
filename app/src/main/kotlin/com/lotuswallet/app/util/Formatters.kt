package com.lotuswallet.app.util

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 展示层格式化。
 *
 * 所有金额在内部一律用「最小单位整数」（wei / 聪 / sun）传递，
 * **只在渲染的最后一刻**转成十进制字符串 —— 避免用 Double 表示金额导致精度丢失。
 */
object Formatters {

    /**
     * 最小单位整数 → 人类可读金额。
     *
     * 采用「最多 [maxFraction] 位小数 + 去掉尾随零」，并且对小于 1 的金额
     * 保留足够的有效位（否则 0.00000001 ETH 会被显示成 0）。
     */
    fun formatUnits(raw: BigInteger, decimals: Int, maxFraction: Int = 8): String {
        if (decimals <= 0) return raw.toString()
        val scale = decimals.coerceIn(0, 36)
        val negative = raw.signum() < 0
        val abs = raw.abs()
        val divisor = BigInteger.TEN.pow(scale)
        val quotient = abs.divide(divisor)
        val remainder = abs.mod(divisor)

        val fractionFull = remainder.toString().padStart(scale, '0')

        // 小金额（整数部分为 0）时，保证至少显示 maxFraction 位有效小数
        val leadingZeros = fractionFull.indexOfFirst { it != '0' }
        val fractionDigits = when {
            leadingZeros < 0 -> 0
            quotient.signum() != 0 -> maxFraction
            else -> (leadingZeros + maxFraction).coerceAtMost(scale)
        }
        val fraction = fractionFull.take(fractionDigits).trimEnd('0')

        val body = if (fraction.isEmpty()) quotient.toString() else "$quotient.$fraction"
        return if (negative) "-$body" else body
    }

    /** 人类可读金额 → 最小单位（转账输入用）。非法输入返回 null。 */
    fun parseUnits(text: String, decimals: Int): BigInteger? {
        val trimmed = text.trim().replace(",", "")
        if (trimmed.isEmpty() || trimmed == ".") return null
        val value = try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            return null
        }
        if (value.signum() < 0) return null
        return try {
            value.setScale(decimals, RoundingMode.DOWN).movePointRight(decimals).toBigIntegerExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** 手续费等小额金额的紧凑显示（Gwei 量级不会显示成一长串 0）。 */
    fun formatFee(raw: BigInteger, decimals: Int, symbol: String): String =
        "${formatUnits(raw, decimals)} $symbol"

    /** 地址缩略。**仅用于展示**，任何复制/转账都必须用完整地址。 */
    fun shorten(address: String, head: Int = 8, tail: Int = 6): String {
        val a = address.trim()
        return if (a.length <= head + tail + 1) a else "${a.take(head)}…${a.takeLast(tail)}"
    }

    /** 时间戳（毫秒）→ `MM-dd HH:mm`；同一年不显示年份。 */
    fun formatTime(millis: Long): String {
        if (millis <= 0) return "—"
        val now = Date()
        val then = Date(millis)
        val pattern = if (yearOf(now) == yearOf(then)) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(then)
    }

    fun formatDateTime(millis: Long): String =
        if (millis <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun yearOf(date: Date): Int = SimpleDateFormat("yyyy", Locale.US).format(date).toInt()

    /** 秒数 → `mm:ss`。 */
    fun formatCountdown(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
