package com.lotuswallet.chain.tron

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash

/**
 * `raw_data` 的解码结果 —— 用于在确认页展示「即将签名到底是什么」。
 *
 * 注意：这里解码的是节点返回的 `raw_data_hex` **原始字节**，
 * 而不是同一次响应里的 JSON 字段。两者若不一致，以字节为准，从而防止被中间人误导。
 */
data class TronRawData(
    val refBlockBytesHex: String,
    val refBlockNum: Long,
    val refBlockHashHex: String,
    val expiration: Long,
    val timestamp: Long,
    val feeLimit: Long?,
    val contracts: List<TronContract>,
) {

    /** 交易哈希：`SHA256(raw_data)`。这就是要被签名的 32 字节。 */
    fun txId(rawDataHex: String): ByteArray = Hash.sha256(Bytes.fromHex(rawDataHex))

    val primary: TronContract? get() = contracts.firstOrNull()

    companion object {

        /** 解码 `raw_data_hex`。字段号来自 java-tron 的 `Tron.proto`。 */
        fun decode(rawDataHex: String): TronRawData {
            val bytes = Bytes.fromHex(rawDataHex)
            val reader = ProtoReader(bytes)

            var refBlockBytes = ByteArray(0)
            var refBlockNum = 0L
            var refBlockHash = ByteArray(0)
            var expiration = 0L
            var timestamp = 0L
            var feeLimit: Long? = null
            val contracts = mutableListOf<TronContract>()

            while (reader.hasMore) {
                val field = reader.readTag()
                when (field) {
                    1 -> refBlockBytes = reader.readLengthDelimited()
                    3 -> refBlockNum = reader.readVarint()
                    4 -> refBlockHash = reader.readLengthDelimited()
                    8 -> expiration = reader.readVarint()
                    11 -> contracts += TronContract.decode(reader.readLengthDelimited())
                    14 -> timestamp = reader.readVarint()
                    18 -> feeLimit = reader.readVarint()
                    else -> reader.skip(reader.lastWireType)
                }
            }

            return TronRawData(
                refBlockBytesHex = Bytes.toHex(refBlockBytes),
                refBlockNum = refBlockNum,
                refBlockHashHex = Bytes.toHex(refBlockHash),
                expiration = expiration,
                timestamp = timestamp,
                feeLimit = feeLimit,
                contracts = contracts,
            )
        }
    }
}

/** TRON 的合约类型。只列出钱包会遇到的，其余保留原始 code。 */
enum class TronContractType(val code: Int, val displayName: String) {
    ACCOUNT_CREATE(0, "创建账户"),
    TRANSFER(1, "TRX 转账"),
    TRANSFER_ASSET(2, "TRC-10 转账"),
    VOTE_WITNESS(4, "投票"),
    WITHDRAW_BALANCE(13, "提取奖励"),
    FREEZE_BALANCE_V2(54, "质押 TRX"),
    UNFREEZE_BALANCE_V2(55, "解除质押"),
    WITHDRAW_EXPIRE_UNFREEZE(56, "提取到期解质押"),
    DELEGATE_RESOURCE(57, "代理资源"),
    UNDELEGATE_RESOURCE(58, "取消代理资源"),
    TRIGGER_SMART_CONTRACT(31, "智能合约调用（通常为 TRC-20 转账）"),
    UNKNOWN(-1, "未知操作");

    companion object {
        fun fromCode(code: Int): TronContractType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * 单条合约调用。
 *
 * TRON 的交易可以包含多条合约（`repeated Contract`），尽管钱包产生的交易通常只有一条。
 * 这里全部解码出来，UI 必须展示**全部**合约，避免用户在多重合约中被隐藏条款欺骗。
 */
data class TronContract(
    val typeCode: Int,
    val ownerAddress: String?,
    val toAddress: String?,
    /** 转账金额或质押金额（sun）。 */
    val amount: Long?,
    /** 智能合约地址（TRC-20 场景）。 */
    val contractAddress: String?,
    /** 合约调用数据（ABI 编码）。 */
    val callData: ByteArray?,
    val callValue: Long?,
    val typeUrl: String?,
) {

    val type: TronContractType get() = TronContractType.fromCode(typeCode)

    companion object {

        internal fun decode(payload: ByteArray): TronContract {
            val reader = ProtoReader(payload)
            var typeCode = -1
            var typeUrl: String? = null
            var value: ByteArray? = null

            while (reader.hasMore) {
                val field = reader.readTag()
                when (field) {
                    1 -> typeCode = reader.readVarint().toInt()
                    2 -> {
                        // Any { type_url = 1(string); value = 2(bytes) }
                        val anyReader = ProtoReader(reader.readLengthDelimited())
                        while (anyReader.hasMore) {
                            when (anyReader.readTag()) {
                                1 -> typeUrl = String(anyReader.readLengthDelimited(), Charsets.UTF_8)
                                2 -> value = anyReader.readLengthDelimited()
                                else -> anyReader.skip(anyReader.lastWireType)
                            }
                        }
                    }
                    else -> reader.skip(reader.lastWireType)
                }
            }

            val raw = value ?: return TronContract(typeCode, null, null, null, null, null, null, typeUrl)

            return when (TronContractType.fromCode(typeCode)) {
                TronContractType.TRANSFER -> decodeTransfer(typeCode, raw, typeUrl)
                TronContractType.ACCOUNT_CREATE -> decodeAccountCreate(typeCode, raw, typeUrl)
                TronContractType.TRIGGER_SMART_CONTRACT -> decodeTrigger(typeCode, raw, typeUrl)
                TronContractType.FREEZE_BALANCE_V2 -> decodeFreeze(typeCode, raw, typeUrl)
                TronContractType.WITHDRAW_BALANCE, TronContractType.UNFREEZE_BALANCE_V2,
                TronContractType.WITHDRAW_EXPIRE_UNFREEZE,
                -> TronContract(typeCode, ownerAddress = readField(raw, 1, WireKind.BYTES)?.toHex41(), toAddress = null, amount = null, contractAddress = null, callData = null, callValue = null, typeUrl = typeUrl)

                else -> TronContract(typeCode, null, null, null, null, null, null, typeUrl)
            }
        }

        private fun decodeTransfer(typeCode: Int, raw: ByteArray, typeUrl: String?): TronContract {
            val map = readAll(raw)
            return TronContract(
                typeCode = typeCode,
                ownerAddress = map[1]?.toHex41(),
                toAddress = map[2]?.toHex41(),
                amount = map[3]?.let { readVarintValue(it) },
                contractAddress = null,
                callData = null,
                callValue = null,
                typeUrl = typeUrl,
            )
        }

        private fun decodeAccountCreate(typeCode: Int, raw: ByteArray, typeUrl: String?): TronContract {
            val map = readAll(raw)
            // AccountCreateContract: owner_address = 1, account_address = 2
            val owner = map[1]
            val created = map[2]
            return TronContract(
                typeCode = typeCode,
                ownerAddress = owner?.toHex41(),
                toAddress = created?.toHex41(),
                amount = null,
                contractAddress = null,
                callData = null,
                callValue = null,
                typeUrl = typeUrl,
            )
        }

        private fun decodeTrigger(typeCode: Int, raw: ByteArray, typeUrl: String?): TronContract {
            val map = readAll(raw)
            // TriggerSmartContract: owner_address=1, contract_address=2, call_value=3, data=4
            return TronContract(
                typeCode = typeCode,
                ownerAddress = map[1]?.toHex41(),
                toAddress = null,
                amount = null,
                contractAddress = map[2]?.toHex41(),
                callData = map[4],
                callValue = map[3]?.let { readVarintValue(it) },
                typeUrl = typeUrl,
            )
        }

        private fun decodeFreeze(typeCode: Int, raw: ByteArray, typeUrl: String?): TronContract {
            val map = readAll(raw)
            // FreezeBalanceV2Contract: owner_address=1, frozen_balance=2, resource=3
            return TronContract(
                typeCode = typeCode,
                ownerAddress = map[1]?.toHex41(),
                toAddress = null,
                amount = map[2]?.let { readVarintValue(it) },
                contractAddress = null,
                callData = null,
                callValue = null,
                typeUrl = typeUrl,
            )
        }

        /** 读取所有字段 → (字段号 → 原始字段内容)。varint 字段以原始 varint 字节形式保存。 */
        private fun readAll(proto: ByteArray): Map<Int, ByteArray> {
            val reader = ProtoReader(proto)
            val map = LinkedHashMap<Int, ByteArray>()
            while (reader.hasMore) {
                val field = reader.readTag()
                when (reader.lastWireType) {
                    ProtoReader.WIRE_LENGTH_DELIMITED -> map[field] = reader.readLengthDelimited()
                    ProtoReader.WIRE_VARINT -> {
                        val start = reader.position
                        reader.readVarint()
                        map[field] = proto.copyOfRange(start, reader.position)
                    }
                    else -> reader.skip(reader.lastWireType)
                }
            }
            return map
        }

        private fun readField(proto: ByteArray, field: Int, kind: WireKind): ByteArray? =
            readAll(proto)[field]

        private fun readVarintValue(rawVarintBytes: ByteArray): Long {
            val reader = ProtoReader(rawVarintBytes)
            return reader.readVarint()
        }

        private enum class WireKind { BYTES }
    }
}
