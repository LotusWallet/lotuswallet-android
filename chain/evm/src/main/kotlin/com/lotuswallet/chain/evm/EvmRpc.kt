package com.lotuswallet.chain.evm

import com.lotuswallet.core.network.chain.EvmChainConfig
import com.lotuswallet.core.network.rpc.JsonRpcClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger

/**
 * EVM 只读 RPC 封装。
 *
 * 全部走公共节点，**不带任何鉴权、不携带用户标识**。
 * 端点由链配置给出，[JsonRpcClient] 负责多端点故障转移。
 */
class EvmRpc(private val rpc: JsonRpcClient) {

    /** 校验节点返回的 chainId 是否与配置一致（防连错链导致资产风险）。 */
    suspend fun chainId(rpcUrls: List<String>): Long = parseHexLong(callString(rpcUrls, "eth_chainId"))

    suspend fun blockNumber(rpcUrls: List<String>): Long = parseHexLong(callString(rpcUrls, "eth_blockNumber"))

    /** 原生币余额（wei）。 */
    suspend fun balance(rpcUrls: List<String>, address: String): BigInteger =
        callString(
            rpcUrls,
            "eth_getBalance",
            JsonPrimitive(normalizeAddress(address)),
            JsonPrimitive("latest"),
        ).let(::parseHexBigInteger)

    /**
     * 已发出的交易数（nonce）。
     * 用 `pending` 而不是 `latest`，避免连续发送时复用同一个 nonce。
     */
    suspend fun transactionCount(rpcUrls: List<String>, address: String): Long =
        parseHexLong(
            callString(
                rpcUrls,
                "eth_getTransactionCount",
                JsonPrimitive(normalizeAddress(address)),
                JsonPrimitive("pending"),
            ),
        )

    suspend fun gasPrice(rpcUrls: List<String>): BigInteger =
        parseHexBigInteger(callString(rpcUrls, "eth_gasPrice"))

    /** 节点建议的优先费；部分节点不实现该方法，返回 null 由上层兜底。 */
    suspend fun maxPriorityFeePerGas(rpcUrls: List<String>): BigInteger? = runCatching {
        parseHexBigInteger(callString(rpcUrls, "eth_maxPriorityFeePerGas"))
    }.getOrNull()

    /** 最新区块的 baseFee；不支持 1559 的链返回 null。 */
    suspend fun baseFeePerGas(rpcUrls: List<String>): BigInteger? {
        val result = runCatching {
            rpc.callRaw(
                rpcUrls,
                "eth_getBlockByNumber",
                JsonArray(listOf(JsonPrimitive("latest"), JsonPrimitive(false))),
            )
        }.getOrNull() ?: return null
        val obj = result as? JsonObject ?: return null
        val hex = obj["baseFeePerGas"]?.jsonPrimitive?.contentOrNull ?: return null
        return parseHexBigInteger(hex)
    }

    suspend fun estimateGas(rpcUrls: List<String>, request: EvmCallRequest): BigInteger =
        parseHexBigInteger(
            callString(
                rpcUrls,
                "eth_estimateGas",
                EvmJson.encode(request),
                JsonPrimitive("latest"),
            ),
        )

    /** 只读调用（不产生交易）。 */
    suspend fun call(rpcUrls: List<String>, request: EvmCallRequest): ByteArray {
        val hex = callString(
            rpcUrls,
            "eth_call",
            EvmJson.encode(request),
            JsonPrimitive("latest"),
        ).removePrefix("0x")
        return com.lotuswallet.core.common.Bytes.fromHex(hex)
    }

    /** 广播已签名的原始交易，返回交易哈希。 */
    suspend fun sendRawTransaction(rpcUrls: List<String>, rawHex: String): String =
        callString(rpcUrls, "eth_sendRawTransaction", JsonPrimitive(normalizeHex(rawHex)))

    suspend fun getCode(rpcUrls: List<String>, address: String): String =
        callString(
            rpcUrls,
            "eth_getCode",
            JsonPrimitive(normalizeAddress(address)),
            JsonPrimitive("latest"),
        )

    /** 查询交易回执；未上链时返回 null。 */
    suspend fun transactionReceipt(rpcUrls: List<String>, txHash: String): EvmReceipt? {
        val result = runCatching {
            rpc.callRaw(rpcUrls, "eth_getTransactionReceipt", JsonArray(listOf(JsonPrimitive(normalizeHex(txHash)))))
        }.getOrNull() ?: return null
        if (result is kotlinx.serialization.json.JsonNull) return null
        val obj = result as? JsonObject ?: return null
        val statusHex = obj["status"]?.jsonPrimitive?.contentOrNull ?: return null
        val blockHex = obj["blockNumber"]?.jsonPrimitive?.contentOrNull
        val gasUsedHex = obj["gasUsed"]?.jsonPrimitive?.contentOrNull
        return EvmReceipt(
            transactionHash = obj["transactionHash"]?.jsonPrimitive?.contentOrNull ?: normalizeless(txHash),
            success = parseHexLong(statusHex) == 1L,
            blockNumber = blockHex?.let(::parseHexLong),
            gasUsed = gasUsedHex?.let(::parseHexBigInteger) ?: BigInteger.ZERO,
        )
    }

    /**
     * 按费用模型给出建议费用。
     * 不支持 1559 的链直接退化为 `gasPrice`。
     */
    suspend fun suggestFees(rpcUrls: List<String>, chain: EvmChainConfig): EvmFeeSuggestion {
        if (!chain.supportsEip1559) {
            val price = gasPrice(rpcUrls)
            return EvmFeeSuggestion(price, price, price)
        }
        val base = baseFeePerGas(rpcUrls) ?: BigInteger.ZERO
        val priority = maxPriorityFeePerGas(rpcUrls)
            ?: chain.suggestedPriorityFeeGwei?.let { gweiToWei(it) }
            ?: GWEI
        // 给 baseFee 留一倍波动空间，避免因出块时 baseFee 上涨而交易被拒
        val maxFee = base.multiply(BigInteger.TWO).add(priority)
        return EvmFeeSuggestion(priority, maxFee, null)
    }

    private suspend fun callString(
        rpcUrls: List<String>,
        method: String,
        vararg params: JsonElement,
    ): String = rpc.call(rpcUrls, method, JsonArray(params.toList()), String.serializer())

    companion object {
        val GWEI: BigInteger = BigInteger.valueOf(1_000_000_000L)

        fun gweiToWei(gwei: Double): BigInteger =
            BigInteger.valueOf(Math.round(gwei * 1_000_000_000.0))

        fun weiToGwei(wei: BigInteger): Double = wei.toDouble() / 1_000_000_000.0
    }
}

/** 建议费用。 */
data class EvmFeeSuggestion(
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
    /** 仅当链不支持 EIP-1559 时有值。 */
    val legacyGasPrice: BigInteger?,
)

/** 交易回执的精简视图。 */
data class EvmReceipt(
    val transactionHash: String,
    val success: Boolean,
    val blockNumber: Long?,
    val gasUsed: BigInteger,
)

/** `eth_call` / `eth_estimateGas` 的请求体。 */
@Serializable
data class EvmCallRequest(
    val from: String? = null,
    val to: String? = null,
    /** 十六进制数量，如 `0xde0b6b3a7640000`。 */
    val value: String? = null,
    /** 十六进制，含 `0x` 前缀。 */
    val data: String? = null,
    val gas: String? = null,
    val gasPrice: String? = null,
    val maxFeePerGas: String? = null,
    val maxPriorityFeePerGas: String? = null,
)

/** 手动拼 JSON，避免为几个可变字段引入完整序列化注解。 */
private object EvmJson {
    fun encode(request: EvmCallRequest): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        request.from?.let { map["from"] = JsonPrimitive(normalizeAddress(it)) }
        request.to?.let { map["to"] = JsonPrimitive(normalizeAddress(it)) }
        request.value?.let { map["value"] = JsonPrimitive(it) }
        request.data?.let { map["data"] = JsonPrimitive(it) }
        request.gas?.let { map["gas"] = JsonPrimitive(it) }
        request.gasPrice?.let { map["gasPrice"] = JsonPrimitive(it) }
        request.maxFeePerGas?.let { map["maxFeePerGas"] = JsonPrimitive(it) }
        request.maxPriorityFeePerGas?.let { map["maxPriorityFeePerGas"] = JsonPrimitive(it) }
        return JsonObject(map)
    }
}

internal fun normalizeAddress(address: String): String = "0x" + EvmAddress.normalize(address)

internal fun normalizeHex(hex: String): String =
    if (hex.startsWith("0x", ignoreCase = true)) "0x" + hex.substring(2).lowercase() else "0x" + hex.lowercase()

private fun normalizeless(hex: String): String = normalizeHex(hex)

internal fun parseHexBigInteger(hex: String): BigInteger {
    val s = hex.trim().removePrefix("0x").removePrefix("0X").ifEmpty { "0" }
    return if (s.all { it == '0' }) BigInteger.ZERO else BigInteger(s, 16)
}

internal fun parseHexLong(hex: String): Long = parseHexBigInteger(hex).toLong()

/** BigInteger 的 JSON-RPC 数量表示。 */
internal fun BigInteger.toHexQuantity(): String =
    if (signum() == 0) "0x0" else "0x" + toString(16)
