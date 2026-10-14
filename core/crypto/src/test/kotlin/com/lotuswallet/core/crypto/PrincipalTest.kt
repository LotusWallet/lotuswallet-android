package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

/**
 * ICP principal 的文本编码。
 *
 * 两件事容易做错，而且都不会报错：
 *   1. 漏掉 ed25519 公钥的 DER/SPKI 前缀 → sha224 的输入整体错位；
 *   2. 文本形式忘了每 5 个字符插连字符 → ICP 工具链会当成**另一个** principal。
 *
 * 因此这里用两个公开常量把它钉死：空 principal 一定是 `aaaaa-aa`，
 * 以及一个由已知 ed25519 公钥算出的完整 principal。
 */
class PrincipalTest {

    @Test
    fun `空主体是管理容器的常量 aaaaa-aa`() {
        assertThat(Principal.fromRaw(ByteArray(0))).isEqualTo("aaaaa-aa")
    }

    @Test
    fun `由已知 ed25519 公钥得到的 principal 与外部工具一致`() {
        // 公钥取自 RFC 8032 测试向量 1
        val pub = Bytes.fromHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        assertThat(Principal.selfAuthenticating(pub))
            .isEqualTo("7oxhs-vj5tp-nkgt7-idxyw-ngkah-47bpv-qdase-pzde6-g6vwc-a3eql-ja")
    }

    @Test
    fun `文本形式每5个字符一个连字符`() {
        val text = Principal.selfAuthenticating(ByteArray(32) { 7 })
        val groups = text.split("-")
        // sha224 是 28 字节，加 4 字节校验和 = 32 字节 → 52 个 base32 字符 → 10 组 + 2 字符
        assertThat(groups).hasSize(11)
        assertThat(groups.dropLast(1).all { it.length == 5 }).isTrue()
        assertThat(groups.last().length).isEqualTo(2)
        assertThat(groups.joinToString("").length).isEqualTo(52)
    }

    @Test
    fun `往返一致——带连字符、不带连字符、大写均可解析`() {
        val pub = Bytes.fromHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val raw = Principal.toRaw(Principal.selfAuthenticating(pub))
        assertThat(raw).isNotNull()
        assertThat(raw!!.size).isEqualTo(28)

        val compact = Principal.selfAuthenticating(pub).replace("-", "")
        assertThat(Principal.toRaw(compact)).isEqualTo(raw)
        assertThat(Principal.toRaw(compact.uppercase())).isEqualTo(raw)
        assertThat(Principal.toRaw("  ${Principal.selfAuthenticating(pub)}  ")).isEqualTo(raw)
    }

    @Test
    fun `principal 负载是加上 DER 前缀后的 sha224`() {
        val pub = ByteArray(32) { 3 }
        val der = Bytes.fromHex("302a300506032b6570032100") + pub
        val raw = Principal.toRaw(Principal.selfAuthenticating(pub))!!
        assertThat(raw).isEqualTo(Hash.sha224(der))
        assertThat(raw.size).isEqualTo(28)

        // 若漏掉 DER 前缀（直接哈希 32 字节裸公钥），会得到完全不同的 principal
        assertThat(Hash.sha224(pub)).isNotEqualTo(raw)
    }

    @Test
    fun `篡改一个字符会被校验和拒绝`() {
        val text = Principal.selfAuthenticating(ByteArray(32) { 9 })
        // 把最后一组的一个字符换成字母表里的另一个
        val tampered = text.dropLast(1) + if (text.last() == 'a') "b" else "a"
        assertThat(Principal.toRaw(tampered)).isNull()
        assertThat(Principal.isValid(tampered)).isFalse()
    }

    @Test
    fun `空串与明显非法的输入返回 null`() {
        for (bad in listOf("", "   ", "-", "111", "not a principal", "aaaaa")) {
            assertThat(runCatching { Principal.toRaw(bad) }.getOrNull()).isNull()
            assertThat(Principal.isValid(bad)).isFalse()
        }
    }

    @Test
    fun `非 32 字节公钥会被拒绝`() {
        for (size in listOf(0, 31, 33)) {
            assertThat(runCatching { Principal.selfAuthenticating(ByteArray(size)) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
