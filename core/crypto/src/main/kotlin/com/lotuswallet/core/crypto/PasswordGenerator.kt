package com.lotuswallet.core.crypto

/**
 * 密码生成器（旧版 `util/pwgenerator/PasswordGenerator` 的平移与加固版）。
 *
 * 加固点：
 * - 旧版基于 `java.util.Random` 与 `RandomStringUtils`，可预测；这里改用 [SecureRandom]；
 * - 保证生成的密码**每个被选中的字符集至少出现一次**（旧版只在部分分支做了）。
 */
object PasswordGenerator {

    const val NUMBERS = "0123456789"
    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val SYMBOLS = "!#$%&()*+,-./:;<=>?@[]^_`{|}~"

    /** 易混淆字符，用于「避免相似字符」选项。 */
    private const val AMBIGUOUS = "0O1lI|`'\""

    data class Options(
        val length: Int = 16,
        val digits: Boolean = true,
        val lowercase: Boolean = true,
        val uppercase: Boolean = true,
        val symbols: Boolean = true,
        val avoidAmbiguous: Boolean = false,
    ) {
        /** 至少选中一个字符集。 */
        val hasAnyCharset: Boolean get() = digits || lowercase || uppercase || symbols
    }

    fun generate(options: Options): String {
        require(options.hasAnyCharset) { "至少要选择一种字符类型" }
        require(options.length >= 1) { "长度必须为正数" }

        val pools = buildList {
            if (options.digits) add(pool(NUMBERS, options.avoidAmbiguous))
            if (options.lowercase) add(pool(LOWER, options.avoidAmbiguous))
            if (options.uppercase) add(pool(UPPER, options.avoidAmbiguous))
            if (options.symbols) add(pool(SYMBOLS, options.avoidAmbiguous))
        }.filter { it.isNotEmpty() }

        require(pools.isNotEmpty()) { "过滤易混淆字符后没有可用字符" }
        require(options.length >= pools.size) {
            "长度至少为 ${pools.size} 才能覆盖所选的 ${pools.size} 种字符类型"
        }

        val all = pools.joinToString("")
        val chars = ArrayList<Char>(options.length)

        // 先各取一个，保证每种类型都出现
        pools.forEach { chars.add(it[SecureRandom.nextInt(it.length)]) }

        // 剩余位置从全集里取
        while (chars.size < options.length) {
            chars.add(all[SecureRandom.nextInt(all.length)])
        }

        // Fisher–Yates 洗牌，打散固定的「各类型一个」前缀
        for (i in chars.indices.reversed()) {
            val j = SecureRandom.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return chars.joinToString("")
    }

    /**
     * 口令强度估算（基于字符集大小与长度，返回熵的比特数）。
     * 仅用于 UI 提示，不作为安全判据。
     */
    fun entropyBits(password: String): Double {
        if (password.isEmpty()) return 0.0
        var poolSize = 0
        if (password.any { it in NUMBERS }) poolSize += NUMBERS.length
        if (password.any { it in LOWER }) poolSize += LOWER.length
        if (password.any { it in UPPER }) poolSize += UPPER.length
        if (password.any { it in SYMBOLS }) poolSize += SYMBOLS.length
        if (poolSize == 0) poolSize = 26
        return password.length * (kotlin.math.ln(poolSize.toDouble()) / kotlin.math.ln(2.0))
    }

    private fun pool(source: String, avoidAmbiguous: Boolean): String =
        if (avoidAmbiguous) source.filterNot { it in AMBIGUOUS } else source
}
