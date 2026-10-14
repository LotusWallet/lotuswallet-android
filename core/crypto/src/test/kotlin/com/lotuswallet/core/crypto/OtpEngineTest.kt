package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OtpEngineTest {

    /** RFC 4226 / RFC 6238 使用的标准测试密钥：ASCII "12345678901234567890" */
    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun `RFC4226 HOTP 官方向量`() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676")
        expected.forEachIndexed { counter, code ->
            assertThat(OtpEngine.hotp(rfcSecret, counter.toLong()))
                .isEqualTo(code)
        }
    }

    @Test
    fun `RFC6238 TOTP SHA1 官方向量`() {
        val cases = listOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        )
        for ((seconds, expected) in cases) {
            assertThat(
                OtpEngine.totp(rfcSecret, timeMillis = seconds * 1000, digits = 8, algorithm = OtpEngine.Algorithm.SHA1),
            ).isEqualTo(expected)
        }
    }

    @Test
    fun `同一时间步内结果稳定 跨步后变化`() {
        // 取一个恰好落在时间步边界上的时刻，避免测试受当前相位影响
        val base = 1_700_000_010_000L
        val a = OtpEngine.totp(rfcSecret, timeMillis = base)
        val b = OtpEngine.totp(rfcSecret, timeMillis = base + 29_000)
        val c = OtpEngine.totp(rfcSecret, timeMillis = base + 31_000)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun `验证码位数与格式`() {
        val code = OtpEngine.totp(rfcSecret)
        assertThat(code).hasLength(6)
        assertThat(code.all { it.isDigit() }).isTrue()
    }

    @Test
    fun `剩余秒数落在合法区间`() {
        for (offset in 0 until 30) {
            val remaining = OtpEngine.remainingSeconds(timeMillis = 1_700_000_000_000 + offset * 1000L)
            assertThat(remaining).isIn(1..30)
        }
    }

    @Test
    fun `密钥解码容忍空格小写与缺失填充`() {
        val messy = "gezd gnbv gy3t qojq gezd gnbv gy3t qojq"
        assertThat(OtpEngine.totp(messy, timeMillis = 59_000, digits = 8)).isEqualTo("94287082")
    }

    @Test
    fun `verifyTotp 允许一个时间步的时钟漂移`() {
        val secret = OtpEngine.generateSecret()
        val now = 1_700_000_000_000L
        val code = OtpEngine.totp(secret, now)
        assertThat(OtpEngine.verifyTotp(secret, code, now)).isTrue()
        assertThat(OtpEngine.verifyTotp(secret, code, now + 30_000)).isTrue()
        assertThat(OtpEngine.verifyTotp(secret, code, now + 5 * 30_000)).isFalse()
        assertThat(OtpEngine.verifyTotp(secret, "000000", now)).isFalse()
        assertThat(OtpEngine.verifyTotp(secret, "12345", now)).isFalse()
    }

    @Test
    fun `生成的密钥长度为 32 个 Base32 字符且随机`() {
        val a = OtpEngine.generateSecret()
        val b = OtpEngine.generateSecret()
        assertThat(a).hasLength(32)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `构建并解析 otpauth URI 可往返`() {
        val uri = OtpEngine.buildUri(
            label = "alice@example.com",
            secret = "JBSWY3DPEHPK3PXP",
            issuer = "Example",
            digits = 6,
            periodSeconds = 30,
        )
        assertThat(uri).startsWith("otpauth://totp/")
        val parsed = OtpEngine.parseUri(uri)
        assertThat(parsed.issuer).isEqualTo("Example")
        assertThat(parsed.label).isEqualTo("alice@example.com")
        assertThat(parsed.secret).isEqualTo("JBSWY3DPEHPK3PXP")
        assertThat(parsed.digits).isEqualTo(6)
        assertThat(parsed.periodSeconds).isEqualTo(30)
        assertThat(parsed.algorithm).isEqualTo(OtpEngine.Algorithm.SHA1)
    }

    @Test
    fun `解析非默认算法与周期的 URI`() {
        val parsed = OtpEngine.parseUri(
            "otpauth://totp/GitHub:dev?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=60",
        )
        assertThat(parsed.algorithm).isEqualTo(OtpEngine.Algorithm.SHA256)
        assertThat(parsed.digits).isEqualTo(8)
        assertThat(parsed.periodSeconds).isEqualTo(60)
    }

    @Test
    fun `解析纯密钥输入按默认 TOTP 处理`() {
        val parsed = OtpEngine.parseUri("JBSWY3DPEHPK3PXP")
        assertThat(parsed.type).isEqualTo("totp")
        assertThat(parsed.secret).isEqualTo("JBSWY3DPEHPK3PXP")
        assertThat(parsed.digits).isEqualTo(6)
    }

    @Test
    fun `缺少 secret 的 URI 应报错`() {
        val error = runCatching { OtpEngine.parseUri("otpauth://totp/x?issuer=y") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
