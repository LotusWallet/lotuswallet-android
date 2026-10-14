package com.lotuswallet.chain.evm

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

/**
 * RLP 测试用例取自 Ethereum wiki 的官方示例，覆盖：
 * - 单字节 < 0x80 直接输出（最容易被实现漏掉的规则）
 * - 短串 / 长串（长度 >= 56 需要长度前缀）
 * - 空串 / 空列表 / 嵌套列表
 */
class RlpTest {

    private fun hex(bytes: ByteArray) = Bytes.toHex(bytes)

    @Test
    fun `空串编码为 0x80`() {
        assertThat(hex(Rlp.encodeBytes(ByteArray(0)))).isEqualTo("80")
    }

    @Test
    fun `空列表编码为 0xc0`() {
        assertThat(hex(Rlp.encodeList(emptyList()))).isEqualTo("c0")
    }

    @Test
    fun `单字节且小于 0x80 时直接输出自身`() {
        assertThat(hex(Rlp.encodeBytes(byteArrayOf(0x0f)))).isEqualTo("0f")
        assertThat(hex(Rlp.encodeBytes(byteArrayOf(0x00)))).isEqualTo("00")
        assertThat(hex(Rlp.encodeBytes(byteArrayOf(0x7f)))).isEqualTo("7f")
    }

    @Test
    fun `单字节且大于等于 0x80 时需要长度前缀`() {
        assertThat(hex(Rlp.encodeBytes(byteArrayOf(0x80.toByte())))).isEqualTo("8180")
        assertThat(hex(Rlp.encodeBytes(byteArrayOf(0xff.toByte())))).isEqualTo("81ff")
    }

    @Test
    fun `dog 的编码`() {
        assertThat(hex(Rlp.encodeBytes("dog".toByteArray()))).isEqualTo("83646f67")
    }

    @Test
    fun `列表 cat dog 的编码`() {
        val encoded = Rlp.encodeList(
            Rlp.encodeBytes("cat".toByteArray()),
            Rlp.encodeBytes("dog".toByteArray()),
        )
        assertThat(hex(encoded)).isEqualTo("c88363617483646f67")
    }

    @Test
    fun `长度 56 的字符串需要 0xb8 前缀`() {
        val text = "Lorem ipsum dolor sit amet, consectetur adipisicing elit"
        assertThat(text.toByteArray().size).isEqualTo(56)
        val encoded = Rlp.encodeBytes(text.toByteArray())
        assertThat(hex(encoded)).startsWith("b838")
        assertThat(encoded.size).isEqualTo(2 + 56)
    }

    @Test
    fun `嵌套列表的编码`() {
        val empty = Rlp.encodeList(emptyList())
        val listOfEmpty = Rlp.encodeList(listOf(empty))
        val twoLevels = Rlp.encodeList(listOf(empty, listOfEmpty))
        val threeLevels = Rlp.encodeList(listOf(empty, listOfEmpty, twoLevels))
        assertThat(hex(empty)).isEqualTo("c0")
        assertThat(hex(listOfEmpty)).isEqualTo("c1c0")
        assertThat(hex(twoLevels)).isEqualTo("c3c0c1c0")
        // [[], [[]], [[], [[]]]] —— RLP 规范里的经典用例
        assertThat(hex(threeLevels)).isEqualTo("c7c0c1c0c3c0c1c0")
    }

    @Test
    fun `整数数量使用最小大端表示`() {
        assertThat(hex(Rlp.encodeQuantity(0))).isEqualTo("80")
        assertThat(hex(Rlp.encodeQuantity(15))).isEqualTo("0f")
        assertThat(hex(Rlp.encodeQuantity(1024))).isEqualTo("820400")
        assertThat(hex(Rlp.encodeQuantity(0xFFFFFFFFL))).isEqualTo("84ffffffff")
    }

    @Test
    fun `为空的 to 字段表示合约创建`() {
        assertThat(hex(Rlp.encodeOptional(null))).isEqualTo("80")
        assertThat(hex(Rlp.encodeOptional(ByteArray(20)))).isEqualTo("94" + "00".repeat(20))
    }
}
