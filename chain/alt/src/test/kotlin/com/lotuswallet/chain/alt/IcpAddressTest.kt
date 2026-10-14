package com.lotuswallet.chain.alt

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bip39
import com.lotuswallet.core.crypto.Ed25519
import com.lotuswallet.core.crypto.Principal
import com.lotuswallet.core.crypto.Slip10
import com.lotuswallet.core.network.chain.Ed25519Derivation
import org.junit.Test

/**
 * ICP 的「地址」= 自认证 principal。
 *
 * 与 Solana 的关键差异在派生算法：ICP 的官方工具 `dfx` 走 **BIP-32**
 * （HMAC key 为 `"Bitcoin seed"`）再把 32 字节当 ed25519 种子用；
 * 若误用 SLIP-0010，得到的是一串**同样合法但完全不属于该账户**的 principal。
 */
class IcpAddressTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private val seed: ByteArray get() = Bip39.mnemonicToSeed(mnemonic)

    @Test
    fun `标准助记词——账户0的 principal`() {
        val address = IcpAddress.address(seed, IcpAddress.pathFor(0))
        assertThat(address).isEqualTo("br4qw-5qmgh-wczig-7all5-t2kyv-7aifu-adrew-jew6d-dxpcb-stewa-ta")
    }

    @Test
    fun `标准助记词——账户1的 principal`() {
        val address = IcpAddress.address(seed, IcpAddress.pathFor(1))
        assertThat(address).isEqualTo("rej2n-iael2-2tc2n-xw7xp-yr3q6-qx7y3-552c2-lb6cy-t7e3o-w6ipn-dq")
    }

    @Test
    fun `中间密钥材料与公钥都对得上`() {
        val path = IcpAddress.pathFor(0)
        val material = AltKeyDerivation.keyMaterial(seed, path, Ed25519Derivation.BIP32)
        assertThat(Bytes.toHex(material))
            .isEqualTo("f60151c409cb357e00a4267ad2cfa0001ff431ef5911110d651b1e7fc03451ac")
        assertThat(Bytes.toHex(Ed25519.publicKey(material)))
            .isEqualTo("f9285950e15ba72fce430f023ba70ed7e8d1c2c88319150adbba5c120cbb91da")
        // 裸 principal = sha224(SPKI)；toRaw 返回的就是它（不含那 4 字节 CRC 前缀）
        val raw = Principal.toRaw(IcpAddress.address(seed, path))
        assertThat(raw).isNotNull()
        assertThat(raw!!.size).isEqualTo(28)
        assertThat(Bytes.toHex(raw))
            .isEqualTo("0c31ec2ca0df02d7d9e958afc082d003892c925bc31dde20ca64b026")
    }

    @Test
    fun `路径约定为 m 44 223 account 0 0 ——最后两级不硬化`() {
        // dfx 的路径末两级（change / addressIndex）是非硬化的，
        // 这也正是 ICP 必须用 BIP-32 而不能用 SLIP-0010 的原因
        assertThat(IcpAddress.pathFor(0)).isEqualTo("m/44'/223'/0'/0/0")
        assertThat(IcpAddress.pathFor(3)).isEqualTo("m/44'/223'/3'/0/0")
    }

    @Test
    fun `误用 SLIP-0010 会得到另一个 principal`() {
        val path = IcpAddress.pathFor(0)
        val wrong = Principal.selfAuthenticating(
            Ed25519.publicKey(Slip10.derive(seed, path).key)
        )
        assertThat(wrong).isNotEqualTo(IcpAddress.address(seed, path))
    }

    @Test
    fun `principal 文本形式带连字符且分组为5`() {
        val text = IcpAddress.address(seed, IcpAddress.pathFor(0))
        val groups = text.split("-")
        assertThat(groups.dropLast(1).all { it.length == 5 }).isTrue()
        assertThat(text).doesNotContain("--")
    }

    @Test
    fun `地址校验——合法 principal 通过，篡改后失败`() {
        val address = IcpAddress.address(seed, IcpAddress.pathFor(0))
        assertThat(IcpAddress.isValid(address)).isTrue()
        assertThat(IcpAddress.isValid("  $address  ")).isTrue()
        assertThat(IcpAddress.isValid(address.dropLast(1) + "z")).isFalse()
        assertThat(IcpAddress.isValid("")).isFalse()
    }

    @Test
    fun `不同账户派生不同 principal`() {
        assertThat(IcpAddress.address(seed, IcpAddress.pathFor(0)))
            .isNotEqualTo(IcpAddress.address(seed, IcpAddress.pathFor(1)))
    }
}
