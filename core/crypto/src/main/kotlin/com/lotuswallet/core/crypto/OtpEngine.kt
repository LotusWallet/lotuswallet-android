package com.lotuswallet.core.crypto

import java.io.ByteArrayOutputStream

/**
 * 一次性口令引擎：HOTP (RFC 4226) 与 TOTP (RFC 6238)。
 *
 * 这是从旧版 `util/crypto/GoogleAuth` 平移并修正后的实现 —— 属于「必须保留」的能力。
 *
 * 相对旧版修正的点：
 * - 旧版仅支持 HmacSHA1，这里支持 SHA1/SHA256/SHA512（现代站点大量使用后两者）；
 * - 旧版 `generateSecretKey()` 用一个固定 SEED 做种子，等价于所有用户共享弱熵，已废弃；
 * - 时间步长、位数、算法均可配置，不再是 30s / 6 位写死；
 * - 新增 `otpauth://` URI 的解析与生成，兼容 Google Authenticator / Aegis / 1Password 的导入导出。
 */
object OtpEngine {

    enum class Algorithm(val jcaName: String, val otpauthName: String) {
        SHA1("HmacSHA1", "SHA1"),
        SHA256("HmacSHA256", "SHA256"),
        SHA512("HmacSHA512", "SHA512");

        companion object {
            fun fromOtpAuth(value: String?): Algorithm =
                entries.firstOrNull { it.otpauthName.equals(value, ignoreCase = true) } ?: SHA1
        }
    }

    const val DEFAULT_PERIOD_SECONDS = 30
    const val DEFAULT_DIGITS = 6

    /**
     * HOTP：基于计数器的动态口令。
     *
     * @param secret Base32 编码的共享密钥（大小写、空格、填充均可容忍）
     */
    fun hotp(
        secret: String,
        counter: Long,
        digits: Int = DEFAULT_DIGITS,
        algorithm: Algorithm = Algorithm.SHA1,
    ): String {
        require(digits in 6..8) { "口令位数必须在 6..8，实际 $digits" }
        val key = Base32.decodeLenient(secret)
        require(key.isNotEmpty()) { "OTP 密钥为空" }

        val message = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            message[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }

        val hash = when (algorithm) {
            Algorithm.SHA1 -> Hash.hmacSha1(key, message)
            Algorithm.SHA256 -> Hash.hmacSha256(key, message)
            Algorithm.SHA512 -> Hash.hmacSha512(key, message)
        }
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val modulus = POWERS_OF_TEN[digits]
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /**
     * TOTP：基于时间的动态口令。
     *
     * @param timeMillis 当前时间（UTC 毫秒）
     */
    fun totp(
        secret: String,
        timeMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
        digits: Int = DEFAULT_DIGITS,
        algorithm: Algorithm = Algorithm.SHA1,
    ): String {
        require(periodSeconds > 0) { "时间步长必须为正数" }
        val counter = (timeMillis / 1000L) / periodSeconds
        return hotp(secret, counter, digits, algorithm)
    }

    /** 当前口令剩余有效秒数，用于 UI 倒计时环。 */
    fun remainingSeconds(
        timeMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
    ): Int {
        require(periodSeconds > 0)
        val elapsed = ((timeMillis / 1000L) % periodSeconds).toInt()
        return periodSeconds - elapsed
    }

    /** 新建条目用的随机密钥（默认 160 bit，符合 RFC 4226 推荐）。 */
    fun generateSecret(bits: Int = 160): String = Base32.randomSecret(bits / 8)

    /**
     * 校验用户输入的验证码，允许前后各 [window] 个时间步的漂移（应对设备时钟偏差）。
     */
    fun verifyTotp(
        secret: String,
        code: String,
        timeMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
        digits: Int = DEFAULT_DIGITS,
        algorithm: Algorithm = Algorithm.SHA1,
        window: Int = 1,
    ): Boolean {
        val target = code.filter { it.isDigit() }
        if (target.length != digits) return false
        for (offset in -window..window) {
            val shifted = timeMillis + offset * periodSeconds * 1000L
            if (totp(secret, shifted, periodSeconds, digits, algorithm) == target) return true
        }
        return false
    }

    /** OTP 条目的结构化描述。 */
    data class OtpAuth(
        val type: String,
        val label: String,
        val issuer: String?,
        val secret: String,
        val algorithm: Algorithm,
        val digits: Int,
        val periodSeconds: Int,
        val counter: Long?,
    )

    /** 生成标准 `otpauth://` URI，便于导出到其他认证器。 */
    fun buildUri(
        label: String,
        secret: String,
        issuer: String? = null,
        algorithm: Algorithm = Algorithm.SHA1,
        digits: Int = DEFAULT_DIGITS,
        periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
        counter: Long? = null,
    ): String {
        val type = if (counter == null) "totp" else "hotp"
        val fullLabel = if (issuer.isNullOrBlank()) label else "$issuer:$label"
        val params = buildList {
            add("secret=${secret.replace("=", "")}")
            if (!issuer.isNullOrBlank()) add("issuer=${encode(issuer)}")
            add("algorithm=${algorithm.otpauthName}")
            add("digits=$digits")
            if (counter == null) add("period=$periodSeconds") else add("counter=$counter")
        }
        return "otpauth://$type/${encode(fullLabel)}?${params.joinToString("&")}"
    }

    /**
     * 解析 `otpauth://` URI（扫码或粘贴导入）。
     * 同时也接受纯 Base32 密钥（此时按默认 TOTP 处理）。
     */
    fun parseUri(uri: String): OtpAuth {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("otpauth://", ignoreCase = true)) {
            val secret = Base32.decodeLenient(trimmed)
            require(secret.isNotEmpty()) { "无法识别的 OTP 输入" }
            return OtpAuth(
                type = "totp",
                label = "未命名",
                issuer = null,
                secret = Base32.encode(secret, pad = false),
                algorithm = Algorithm.SHA1,
                digits = DEFAULT_DIGITS,
                periodSeconds = DEFAULT_PERIOD_SECONDS,
                counter = null,
            )
        }

        val withoutScheme = trimmed.substringAfter("://")
        val type = withoutScheme.substringBefore('/').lowercase()
        require(type == "totp" || type == "hotp") { "不支持的 OTP 类型: $type" }

        val pathAndQuery = withoutScheme.substringAfter('/')
        val rawLabel = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', "")

        val params = query.split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        val label = decode(rawLabel)
        val issuerParam = params["issuer"]?.let { decode(it) }
        val issuer = issuerParam ?: label.substringBefore(':', "").ifBlank { null }
        val displayLabel = if (issuer != null && label.startsWith("$issuer:")) {
            label.removePrefix("$issuer:")
        } else {
            label
        }

        val secretRaw = params["secret"] ?: throw IllegalArgumentException("otpauth:// 缺少 secret 参数")
        val secret = Base32.encode(Base32.decodeLenient(secretRaw), pad = false)

        return OtpAuth(
            type = type,
            label = displayLabel.ifBlank { "未命名" },
            issuer = issuer,
            secret = secret,
            algorithm = Algorithm.fromOtpAuth(params["algorithm"]),
            digits = params["digits"]?.toIntOrNull()?.coerceIn(6, 8) ?: DEFAULT_DIGITS,
            periodSeconds = params["period"]?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_PERIOD_SECONDS,
            counter = params["counter"]?.toLongOrNull(),
        )
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value, "UTF-8")

    private val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000)
}
