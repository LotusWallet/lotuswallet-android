package com.lotuswallet.chain.tron

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * `raw_data` 解码测试。
 *
 * 测试里用一个**独立实现的最小 protobuf 写入器**构造字节，再交给被测的解码器读取 ——
 * 字段号与 wire type 的处理因此是被真实校验的，而不是「自己写、自己读」的循环论证。
 *
 * 字段号取自 java-tron 的 `Tron.proto`：
 * ```
 * Transaction.raw:  ref_block_bytes=1, ref_block_num=3, ref_block_hash=4,
 *                   expiration=8, contract=11, timestamp=14, fee_limit=18
 * Transaction.Contract: type=1, parameter(Any)=2
 * Any:              type_url=1, value=2
 * TransferContract: owner_address=1, to_address=2, amount=3
 * TriggerSmartContract: owner_address=1, contract_address=2, call_value=3, data=4
 * ```
 */
class TronRawDataTest {

    /** 独立于被测代码实现的最小 protobuf 写入器。 */
    private class ProtoWriter {
        private val out = ByteArrayOutputStream()

        fun varint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

        fun bytes(field: Int, data: ByteArray) {
            tag(field, 2)
            varint(data.size.toLong())
            out.write(data)
        }

        fun uint(field: Int, value: Long) {
            tag(field, 0)
            varint(value)
        }

        /** 写入一个嵌套消息字段（例如 `contract`）。 */
        fun message(field: Int, block: ProtoWriter.() -> Unit) {
            bytes(field, ProtoWriter().apply(block).toByteArray())
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private val ownerHex = "41c8599111f29c1e1e061265b4af93ea1f274ad78a"
    private val toHex = "4122222222222222222222222222222222222222"

    /** 写出一段完整消息的字节（**不再额外包一层字段**，这正是之前测试出错的地方）。 */
    private fun encode(block: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().apply(block).toByteArray()

    /** 构造一条 `Transaction.Contract` 消息。 */
    private fun contract(typeCode: Long, typeUrl: String, value: ByteArray): ByteArray =
        encode {
            uint(1, typeCode)
            bytes(2, encode {
                bytes(1, typeUrl.toByteArray())
                bytes(2, value)
            })
        }

    private fun trxTransferValue(amount: Long): ByteArray = encode {
        bytes(1, Bytes.fromHex(ownerHex))
        bytes(2, Bytes.fromHex(toHex))
        uint(3, amount)
    }

    private fun buildTransferRawData(
        amount: Long = 1_000_000,
        expiration: Long = 1_700_000_000_000,
        timestamp: Long = 1_699_999_900_000,
        feeLimit: Long? = null,
    ): String {
        val root = ProtoWriter()
        root.bytes(1, byteArrayOf(0x5a, 0x8c.toByte()))
        root.uint(3, 23166)
        root.bytes(4, ByteArray(8) { 0x11 })
        root.uint(8, expiration)
        root.bytes(11, contract(1, "type.googleapis.com/protocol.TransferContract", trxTransferValue(amount)))
        root.uint(14, timestamp)
        feeLimit?.let { root.uint(18, it) }
        return Bytes.toHex(root.toByteArray())
    }

    @Test
    fun `解码 TRX 转账的原始字节`() {
        val hex = buildTransferRawData()
        val raw = TronRawData.decode(hex)

        assertThat(raw.refBlockBytesHex).isEqualTo("5a8c")
        assertThat(raw.refBlockNum).isEqualTo(23166)
        assertThat(raw.refBlockHashHex).isEqualTo("11".repeat(8))
        assertThat(raw.expiration).isEqualTo(1_700_000_000_000)
        assertThat(raw.timestamp).isEqualTo(1_699_999_900_000)
        assertThat(raw.feeLimit).isNull()

        assertThat(raw.contracts).hasSize(1)
        val contract = raw.contracts.single()
        assertThat(contract.typeCode).isEqualTo(1)
        assertThat(contract.type).isEqualTo(TronContractType.TRANSFER)
        assertThat(contract.ownerAddress).isEqualTo(ownerHex)
        assertThat(contract.toAddress).isEqualTo(toHex)
        assertThat(contract.amount).isEqualTo(1_000_000L)

        // 地址可以还原成 Base58 形式
        assertThat(TronAddress.fromHex(contract.ownerAddress!!)).isEqualTo("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH")
        assertThat(TronAddress.isValid(TronAddress.fromHex(contract.toAddress!!))).isTrue()
    }

    @Test
    fun `解码大额转账与费用上限`() {
        val hex = buildTransferRawData(amount = 1_234_567_890_123L, feeLimit = 100_000_000L)
        val raw = TronRawData.decode(hex)
        assertThat(raw.feeLimit).isEqualTo(100_000_000L)
        assertThat(raw.contracts.single().amount).isEqualTo(1_234_567_890_123L)
    }

    @Test
    fun `金额为零也能正确解析为零而不是丢失`() {
        val raw = TronRawData.decode(buildTransferRawData(amount = 0))
        assertThat(raw.contracts.single().amount).isEqualTo(0L)
    }

    @Test
    fun `交易哈希等于原始数据的 SHA256`() {
        val hex = buildTransferRawData()
        val raw = TronRawData.decode(hex)
        assertThat(Bytes.toHex(raw.txId(hex))).isEqualTo(Bytes.toHex(Hash.sha256(Bytes.fromHex(hex))))
        assertThat(raw.txId(hex).size).isEqualTo(32)
    }

    @Test
    fun `解码 TRC-20 转账调用`() {
        val to = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
        val amount = BigInteger("5000000")
        val callData = TronAbi.transferData(to, amount)

        val value = encode {
            bytes(1, Bytes.fromHex(ownerHex))
            bytes(2, Bytes.fromHex(toHex))
            uint(3, 0)
            bytes(4, callData)
        }
        val raw = TronRawData.decode(
            Bytes.toHex(
                encode {
                    bytes(11, contract(31, "type.googleapis.com/protocol.TriggerSmartContract", value))
                },
            ),
        )

        val contract = raw.contracts.single()
        assertThat(contract.type).isEqualTo(TronContractType.TRIGGER_SMART_CONTRACT)
        assertThat(contract.ownerAddress).isEqualTo(ownerHex)
        assertThat(contract.contractAddress).isEqualTo(toHex)
        assertThat(contract.callValue).isEqualTo(0L)

        // 从即将签名的字节里解出真实的收款人与金额 —— 这正是确认页应该展示的内容
        val decoded = TronAbi.decodeTransfer(contract.callData!!)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.first).isEqualTo(to)
        assertThat(decoded.second).isEqualTo(amount)
    }

    @Test
    fun `未知合约类型不会导致解析失败`() {
        val raw = TronRawData.decode(
            Bytes.toHex(
                encode {
                    bytes(11, contract(999, "type.googleapis.com/unknown.Contract", byteArrayOf(0x08, 0x01)))
                },
            ),
        )
        assertThat(raw.contracts.single().type).isEqualTo(TronContractType.UNKNOWN)
        assertThat(raw.contracts.single().typeCode).isEqualTo(999)
    }

    /**
     * 一条交易可以包含多个合约。UI 必须展示**全部**合约，
     * 否则被劫持的节点可以在第二条合约里塞入隐藏的转账。
     */
    @Test
    fun `多重合约全部解码不会被吞掉`() {
        val root = ProtoWriter()
        root.bytes(11, contract(1, "type.googleapis.com/protocol.TransferContract", trxTransferValue(111)))
        root.bytes(11, contract(1, "type.googleapis.com/protocol.TransferContract", trxTransferValue(222)))
        val raw = TronRawData.decode(Bytes.toHex(root.toByteArray()))
        assertThat(raw.contracts).hasSize(2)
        assertThat(raw.contracts.map { it.amount }).containsExactly(111L, 222L).inOrder()
    }

    @Test
    fun `TRC-20 参数编码使用 20 字节地址主体`() {
        val address = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
        val encoded = TronAbi.encodeAddress(address)
        assertThat(encoded.size).isEqualTo(32)
        // 前 12 字节必须为零（只有 20 字节主体，不含 0x41 前缀）
        assertThat(encoded.copyOfRange(0, 12).all { it.toInt() == 0 }).isTrue()
        assertThat(Bytes.toHex(encoded.copyOfRange(12, 32)))
            .isEqualTo("c8599111f29c1e1e061265b4af93ea1f274ad78a")
        assertThat(TronAbi.decodeAddress(encoded)).isEqualTo(address)
    }

    @Test
    fun `TRC-20 选择器与 ERC-20 一致`() {
        assertThat(Bytes.toHex(TronAbi.selector("transfer(address,uint256)")))
            .isEqualTo("a9059cbb")
        assertThat(Bytes.toHex(TronAbi.selector("balanceOf(address)")))
            .isEqualTo("70a08231")
    }

    @Test
    fun `解析转账调用时校验选择器`() {
        val to = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
        val good = TronAbi.transferData(to, BigInteger.TEN)
        assertThat(TronAbi.decodeTransfer(good)).isNotNull()
        // 换成别的选择器必须解析失败，避免把 approve 误认为 transfer
        val bad = good.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertThat(TronAbi.decodeTransfer(bad)).isNull()
    }
}
