package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

/**
 * ed25519 公钥推导（种子语义）的 RFC 8032 官方向量。
 *
 * 这一层是所有 ed25519 系链（Solana / ICP）地址的公共基础：
 * 种子错、clamp 错、压缩时最高位放错（应为 x 的奇偶）都会产出另一个地址。
 */
class Ed25519Test {

    @Test
    fun `RFC8032 测试向量1`() {
        val seed = Bytes.fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expected = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        assertThat(Bytes.toHex(Ed25519.publicKey(seed))).isEqualTo(expected)
    }

    @Test
    fun `RFC8032 测试向量2`() {
        val seed = Bytes.fromHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val expected = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        assertThat(Bytes.toHex(Ed25519.publicKey(seed))).isEqualTo(expected)
    }

    @Test
    fun `全零种子得到确定的公钥（常用作交叉验证常量）`() {
        val expected = "3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29"
        assertThat(Bytes.toHex(Ed25519.publicKey(ByteArray(32)))).isEqualTo(expected)
    }

    @Test
    fun `公钥长度固定为 32 字节且随种子改变`() {
        val a = Ed25519.publicKey(ByteArray(32) { 1 })
        val b = Ed25519.publicKey(ByteArray(32) { 2 })
        assertThat(a.size).isEqualTo(32)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `非 32 字节种子会被拒绝`() {
        for (size in listOf(16, 31, 33, 64)) {
            val error = runCatching { Ed25519.publicKey(ByteArray(size)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
