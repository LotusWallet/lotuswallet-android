package com.lotuswallet.chain.tron

import com.lotuswallet.core.common.Bytes

/**
 * 极简 protobuf 读取器。
 *
 * ## 为什么不用 protobuf 代码生成
 * TRON 节点（`/wallet/createtransaction`）会直接返回构建好的 `raw_data_hex`，
 * 钱包**不需要自己编码** protobuf，只需要**解码**它来向用户展示「你究竟签了什么」。
 * 为这一个用途引入 protobuf 插件与代码生成并不值得，手写一个约 100 行的读取器足够。
 *
 * ## 安全意义
 * 确认页展示的交易详情必须来自**原始字节**，而不是节点返回的 JSON 字段 ——
 * 否则一个被劫持的节点可以返回「金额 1 TRX」的 JSON，而实际字节里写着 1000 TRX。
 * 这里解码的就是即将被签名的那串字节，两者天然一致。
 */
internal class ProtoReader(private val data: ByteArray) {

    var position: Int = 0
        private set

    val hasMore: Boolean get() = position < data.size

    /** 读取字段的 tag，返回 `fieldNumber`；wireType 通过 [lastWireType] 获取。 */
    var lastWireType: Int = 0
        private set

    fun readTag(): Int {
        val tag = readVarint()
        lastWireType = (tag and 0x07).toInt()
        return (tag ushr 3).toInt()
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(position < data.size) { "protobuf 数据在 varint 中途结束" }
            val b = data[position++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 64) { "varint 过长" }
        }
        return result
    }

    fun readLengthDelimited(): ByteArray {
        val length = readVarint().toInt()
        require(length >= 0 && position + length <= data.size) { "protobuf 长度越界" }
        val out = data.copyOfRange(position, position + length)
        position += length
        return out
    }

    /** 读取 64 位定长（wire type 1）。 */
    fun readFixed64(): ByteArray {
        require(position + 8 <= data.size) { "protobuf 定长字段越界" }
        val out = data.copyOfRange(position, position + 8)
        position += 8
        return out
    }

    /** 读取 32 位定长（wire type 5）。 */
    fun readFixed32(): ByteArray {
        require(position + 4 <= data.size) { "protobuf 定长字段越界" }
        val out = data.copyOfRange(position, position + 4)
        position += 4
        return out
    }

    /** 跳过未知字段。 */
    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> readFixed64()
            WIRE_LENGTH_DELIMITED -> readLengthDelimited()
            WIRE_FIXED32 -> readFixed32()
            else -> throw IllegalStateException("不支持的 protobuf wire type: $wireType")
        }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_FIXED32 = 5
    }
}

/** 小工具：把 `41` 前缀的地址字节表示成十六进制字符串。 */
internal fun ByteArray.toHex41(): String = Bytes.toHex(this)
