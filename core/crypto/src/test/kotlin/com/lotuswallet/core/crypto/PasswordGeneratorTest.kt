package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordGeneratorTest {

    @Test
    fun `长度符合要求`() {
        for (len in listOf(4, 8, 16, 32, 64)) {
            val pw = PasswordGenerator.generate(PasswordGenerator.Options(length = len))
            assertThat(pw).hasLength(len)
        }
    }

    @Test
    fun `每个被选中的字符集至少出现一次`() {
        repeat(200) {
            val pw = PasswordGenerator.generate(PasswordGenerator.Options(length = 8))
            assertThat(pw.any { it in PasswordGenerator.NUMBERS }).isTrue()
            assertThat(pw.any { it in PasswordGenerator.LOWER }).isTrue()
            assertThat(pw.any { it in PasswordGenerator.UPPER }).isTrue()
            assertThat(pw.any { it in PasswordGenerator.SYMBOLS }).isTrue()
        }
    }

    @Test
    fun `只选数字时只出现数字`() {
        repeat(50) {
            val pw = PasswordGenerator.generate(
                PasswordGenerator.Options(length = 12, digits = true, lowercase = false, uppercase = false, symbols = false),
            )
            assertThat(pw.all { it in PasswordGenerator.NUMBERS }).isTrue()
        }
    }

    @Test
    fun `避免相似字符时排除 0O1lI`() {
        repeat(50) {
            val pw = PasswordGenerator.generate(
                PasswordGenerator.Options(length = 24, avoidAmbiguous = true),
            )
            assertThat(pw.any { it in "0O1lI|`'\"" }).isFalse()
        }
    }

    @Test
    fun `未选择任何字符集时抛出明确异常`() {
        val error = runCatching {
            PasswordGenerator.generate(
                PasswordGenerator.Options(
                    length = 10,
                    digits = false,
                    lowercase = false,
                    uppercase = false,
                    symbols = false,
                ),
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `长度不足以覆盖字符类型时报错`() {
        val error = runCatching { PasswordGenerator.generate(PasswordGenerator.Options(length = 2)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `连续生成不重复`() {
        val set = (0 until 100).map { PasswordGenerator.generate(PasswordGenerator.Options(length = 20)) }.toSet()
        assertThat(set).hasSize(100)
    }

    @Test
    fun `熵估算随长度增长`() {
        val short = PasswordGenerator.entropyBits("aB3!")
        val long = PasswordGenerator.entropyBits("aB3!aB3!aB3!aB3!")
        assertThat(long).isGreaterThan(short)
        assertThat(PasswordGenerator.entropyBits("")).isEqualTo(0.0)
    }
}
