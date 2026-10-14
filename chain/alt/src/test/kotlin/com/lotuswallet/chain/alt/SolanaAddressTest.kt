package com.lotuswallet.chain.alt

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bip32
import com.lotuswallet.core.crypto.Bip39
import com.lotuswallet.core.crypto.Base58
import com.lotuswallet.core.crypto.Ed25519
import com.lotuswallet.core.network.chain.Ed25519Derivation
import org.junit.Test

/**
 * Solana 地址派生。
 *
 * 端到端向量用标准测试助记词（全 `abandon` … `about`）算出来，用来钉住三件事：
 *   · 路径必须是 `m/44'/501'/account'/0'` 且**全部硬化**；
 *   · HMAC key 必须是 `"ed25519 seed"`（SLIP-0010），不是 `"Bitcoin seed"`；
 *   · 地址就是 `Base58(32 字节公钥)`，没有版本字节也没有校验和。
 */
class SolanaAddressTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private val seed: ByteArray get() = Bip39.mnemonicToSeed(mnemonic)

    @Test
    fun `标准助记词——账户0的地址`() {
        val address = SolanaAddress.address(seed, SolanaAddress.pathFor(0))
        assertThat(address).isEqualTo("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk")
    }

    @Test
    fun `标准助记词——账户1的地址`() {
        val address = SolanaAddress.address(seed, SolanaAddress.pathFor(1))
        assertThat(address).isEqualTo("Hh8QwFUA6MtVu1qAoq12ucvFHNwCcVTV7hpWjeY1Hztb")
    }

    @Test
    fun `中间密钥材料也对得上——出错时能定位到派生而不是编码`() {
        val material = AltKeyDerivation.keyMaterial(
            seed,
            SolanaAddress.pathFor(0),
            Ed25519Derivation.SLIP10,
        )
        assertThat(Bytes.toHex(material))
            .isEqualTo("37df573b3ac4ad5b522e064e25b63ea16bcbe79d449e81a0268d1047948bb445")
        assertThat(Bytes.toHex(Ed25519.publicKey(material)))
            .isEqualTo("f036276246a75b9de3349ed42b15e232f6518fc20f5fcd4f1d64e81f9bd258f7")
    }

    @Test
    fun `路径约定为 m 44 501 account 0 且全部硬化`() {
        assertThat(SolanaAddress.pathFor(0)).isEqualTo("m/44'/501'/0'/0'")
        assertThat(SolanaAddress.pathFor(7)).isEqualTo("m/44'/501'/7'/0'")
    }

    @Test
    fun `误用 BIP-32 会得到另一个地址——证实必须走 SLIP-0010`() {
        val path = SolanaAddress.pathFor(0)
        val wrong = Base58.encode(
            Ed25519.publicKey(Bip32.fromSeed(seed).derive(path).privateKey)
        )
        assertThat(wrong).isNotEqualTo(SolanaAddress.address(seed, path))
    }

    @Test
    fun `不同账户派生不同地址——同一账户可复现`() {
        val a1 = SolanaAddress.address(seed, SolanaAddress.pathFor(0))
        val a2 = SolanaAddress.address(seed, SolanaAddress.pathFor(0))
        val b = SolanaAddress.address(seed, SolanaAddress.pathFor(1))
        assertThat(a1).isEqualTo(a2)
        assertThat(a1).isNotEqualTo(b)
    }

    @Test
    fun `地址校验——合法地址通过`() {
        assertThat(SolanaAddress.isValid("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk")).isTrue()
        assertThat(SolanaAddress.isValid("  HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk  ")).isTrue()
    }

    @Test
    fun `地址校验——长度或字符不对时拒绝`() {
        // 去掉 5 个字符后只剩 29 字节，必然被拒
        assertThat(SolanaAddress.isValid("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3")).isFalse()
        // 混入 Base58 字母表之外的字符（0 / O / I / l）
        assertThat(SolanaAddress.isValid("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpq0")).isFalse()
        assertThat(SolanaAddress.isValid("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqO")).isFalse()
        assertThat(SolanaAddress.isValid("")).isFalse()
        assertThat(SolanaAddress.isValid("abc")).isFalse()
    }

    @Test
    fun `少一位字符仍可能是合法长度——这是 Solana 地址的固有限制而不是 bug`() {
        // 44 字符去掉末位 → 仍是 32 字节，isValid 只能放行。
        // 钉住这个事实，避免以后有人「顺手修一下」把判定改成按字符串长度卡，
        // 那反而会误杀合法地址（32 字节全零公钥的地址只有 32 个字符）。
        val truncated = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpq"
        assertThat(truncated.length).isEqualTo(43)
        assertThat(SolanaAddress.isValid(truncated)).isTrue()
    }

    @Test
    fun `地址校验——比特币地址不能当 Solana 地址用`() {
        val btc = Base58.encodeChecked(Bytes.fromHex("0062e907b15cbf27d5425399ebf6f0fb50ebb88f18"))
        assertThat(SolanaAddress.isValid(btc)).isFalse()
    }
}
