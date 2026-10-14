package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

class Bip39Test {

    private val abandonMnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun `词表加载 2048 个单词`() {
        assertThat(Bip39.words).hasSize(2048)
        assertThat(Bip39.words.first()).isEqualTo("abandon")
        assertThat(Bip39.words.last()).isEqualTo("zoo")
    }

    @Test
    fun `全零熵生成标准助记词`() {
        val entropy = ByteArray(16)
        assertThat(Bip39.entropyToMnemonic(entropy)).isEqualTo(abandonMnemonic)
    }

    @Test
    fun `助记词可还原熵`() {
        val entropy = ByteArray(16)
        assertThat(Bip39.mnemonicToEntropy(abandonMnemonic)).isEqualTo(entropy)
    }

    @Test
    fun `TREZOR 官方种子向量`() {
        val seed = Bip39.mnemonicToSeed(abandonMnemonic, "TREZOR")
        assertThat(Bytes.toHex(seed)).isEqualTo(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e5349553" +
                "1f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
        )
    }

    @Test
    fun `空口令与 TREZOR 口令得到不同种子`() {
        assertThat(Bip39.mnemonicToSeed(abandonMnemonic, ""))
            .isNotEqualTo(Bip39.mnemonicToSeed(abandonMnemonic, "TREZOR"))
    }

    @Test
    fun `校验和错误必须被拒绝`() {
        // 把最后一个词换掉，校验和必然不匹配
        val broken = abandonMnemonic.replace("about", "ability")
        assertThat(Bip39.validate(broken)).isFalse()
    }

    @Test
    fun `非词表单词必须被拒绝`() {
        val broken = abandonMnemonic.replace("about", "notaword")
        assertThat(Bip39.validate(broken)).isFalse()
    }

    @Test
    fun `正确的助记词通过校验`() {
        assertThat(Bip39.validate(abandonMnemonic)).isTrue()
    }

    @Test
    fun `生成的助记词可自我校验且长度正确`() {
        for (strength in listOf(128, 160, 192, 224, 256)) {
            val mnemonic = Bip39.generate(strength)
            val wordCount = strength / 32 * 3
            assertThat(mnemonic.split(" ")).hasSize(wordCount)
            assertThat(Bip39.validate(mnemonic)).isTrue()
        }
    }

    @Test
    fun `两次生成不会重复`() {
        assertThat(Bip39.generate()).isNotEqualTo(Bip39.generate())
    }

    @Test
    fun `单词联想按前缀返回`() {
        val suggestions = Bip39.suggest("ab")
        assertThat(suggestions).contains("abandon")
        assertThat(suggestions).contains("ability")
        assertThat(suggestions).contains("about")
        assertThat(suggestions).contains("above")
    }
}
