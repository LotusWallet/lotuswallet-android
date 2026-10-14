package com.lotuswallet.chain.evm

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1
import java.math.BigInteger

/**
 * EVM 交易构建与签名。
 *
 * 支持两种交易类型：
 * | 类型 | 编码 | 适用 |
 * |---|---|---|
 * | Legacy | `rlp([nonce, gasPrice, gasLimit, to, value, data, v, r, s])` | 所有 EVM 链（含不支持 1559 的链） |
 * | EIP-1559 | `0x02 || rlp([...])` | 主流链，费用更可预测 |
 *
 * 两者都实现了可恢复签名（`v`/`yParity` 由 `recId` 得出），
 * 这是 `ecrecover` 能反推出正确发送方地址的前提。
 */
sealed class EvmTransaction {

    abstract val chainId: Long
    abstract val nonce: Long
    abstract val gasLimit: BigInteger
    /** 目标地址（小写十六进制、无 0x）。为 null 时是合约创建。 */
    abstract val to: String?
    abstract val value: BigInteger
    abstract val data: ByteArray

    /** 未签名交易的摘要（用于展示 / 硬件钱包确认）。 */
    abstract fun unsignedHash(): ByteArray

    /** 构造待签名的 RLP 载荷（不含签名字段）。 */
    protected abstract fun signingPayload(): ByteArray

    /** 用私钥签名，产出可直接广播的原始交易。 */
    fun sign(privateKey: ByteArray): SignedTransaction {
        val payload = signingPayload()
        val digest = Hash.keccak256(payload)
        val signature = Secp256k1.sign(privateKey, digest)
        val raw = encodeSigned(signature)
        val hash = Bytes.toHex(Hash.keccak256(raw))
        val from = EvmAddress.fromPrivateKey(privateKey)
        return SignedTransaction(
            rawHex = "0x" + Bytes.toHex(raw),
            hash = "0x$hash",
            from = from,
        )
    }

    protected abstract fun encodeSigned(signature: Secp256k1.Signature): ByteArray
}

/** 签名结果。 */
data class SignedTransaction(
    /** 可直接提交给 `eth_sendRawTransaction` 的十六进制串。 */
    val rawHex: String,
    /** 交易哈希（keccak256(raw)）。 */
    val hash: String,
    /** 发送方地址。 */
    val from: String,
)

/**
 * 传统交易（type 0，EIP-155 重放保护）。
 */
data class LegacyEvmTransaction(
    override val chainId: Long,
    override val nonce: Long,
    val gasPrice: BigInteger,
    override val gasLimit: BigInteger,
    override val to: String?,
    override val value: BigInteger,
    override val data: ByteArray = ByteArray(0),
) : EvmTransaction() {

    override fun signingPayload(): ByteArray = Rlp.encodeList(
        Rlp.encodeQuantity(nonce),
        Rlp.encodeQuantity(gasPrice),
        Rlp.encodeQuantity(gasLimit),
        Rlp.encodeOptional(to?.let(::hexToBytes)),
        Rlp.encodeQuantity(value),
        Rlp.encodeBytes(data),
        Rlp.encodeQuantity(chainId),
        Rlp.encodeQuantity(0),
        Rlp.encodeQuantity(0),
    )

    override fun unsignedHash(): ByteArray = Hash.keccak256(signingPayload())

    override fun encodeSigned(signature: Secp256k1.Signature): ByteArray {
        // EIP-155：v = recId + 35 + chainId * 2
        val v = BigInteger.valueOf(signature.recId.toLong() + 35 + chainId * 2)
        return Rlp.encodeList(
            Rlp.encodeQuantity(nonce),
            Rlp.encodeQuantity(gasPrice),
            Rlp.encodeQuantity(gasLimit),
            Rlp.encodeOptional(to?.let(::hexToBytes)),
            Rlp.encodeQuantity(value),
            Rlp.encodeBytes(data),
            Rlp.encodeQuantity(v),
            Rlp.encodeQuantity(signature.r),
            Rlp.encodeQuantity(signature.s),
        )
    }

    // data 是构建期字段，equals/hashCode 用内容比较更符合直觉
    override fun equals(other: Any?): Boolean =
        other is LegacyEvmTransaction && chainId == other.chainId && nonce == other.nonce &&
            gasPrice == other.gasPrice && gasLimit == other.gasLimit && to == other.to &&
            value == other.value && data.contentEquals(other.data)

    override fun hashCode(): Int {
        var result = chainId.hashCode()
        result = 31 * result + nonce.hashCode()
        result = 31 * result + gasPrice.hashCode()
        result = 31 * result + gasLimit.hashCode()
        result = 31 * result + (to?.hashCode() ?: 0)
        result = 31 * result + value.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * EIP-1559 交易（type 0x2）。
 *
 * 费用由 `maxFeePerGas` / `maxPriorityFeePerGas` 两个上限表达，
 * 实际支付 = min(baseFee + priorityFee, maxFee)。
 */
data class Eip1559EvmTransaction(
    override val chainId: Long,
    override val nonce: Long,
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
    override val gasLimit: BigInteger,
    override val to: String?,
    override val value: BigInteger,
    override val data: ByteArray = ByteArray(0),
    /** 保留字段：本期不构造访问列表，留作合约交互优化位。 */
    val accessList: List<Pair<String, List<ByteArray>>> = emptyList(),
) : EvmTransaction() {

    override fun signingPayload(): ByteArray =
        byteArrayOf(TYPE_BYTE) + Rlp.encodeList(unsignedFields())

    override fun unsignedHash(): ByteArray = Hash.keccak256(signingPayload())

    override fun encodeSigned(signature: Secp256k1.Signature): ByteArray =
        byteArrayOf(TYPE_BYTE) + Rlp.encodeList(
            unsignedFields() +
                listOf(
                    // yParity：0 或 1
                    Rlp.encodeQuantity(signature.recId),
                    Rlp.encodeQuantity(signature.r),
                    Rlp.encodeQuantity(signature.s),
                ),
        )

    private fun unsignedFields(): List<ByteArray> = listOf(
        Rlp.encodeQuantity(chainId),
        Rlp.encodeQuantity(nonce),
        Rlp.encodeQuantity(maxPriorityFeePerGas),
        Rlp.encodeQuantity(maxFeePerGas),
        Rlp.encodeQuantity(gasLimit),
        Rlp.encodeOptional(to?.let(::hexToBytes)),
        Rlp.encodeQuantity(value),
        Rlp.encodeBytes(data),
        Rlp.encodeList(accessList.map { (address, storageKeys) ->
            Rlp.encodeList(
                listOf(Rlp.encodeBytes(hexToBytes(address))) +
                    storageKeys.map { Rlp.encodeBytes(it) },
            )
        }),
    )

    companion object {
        /** EIP-2718 交易类型前缀。 */
        const val TYPE_BYTE: Byte = 0x02
    }
}

/** 十六进制串 → 字节（内部统一无 `0x`、小写）。 */
internal fun hexToBytes(hex: String): ByteArray = Bytes.fromHex(hex)
