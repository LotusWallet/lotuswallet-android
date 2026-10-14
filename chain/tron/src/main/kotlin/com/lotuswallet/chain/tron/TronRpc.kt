package com.lotuswallet.chain.tron

import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.network.rpc.RestClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * TronGrid JSON 接口客户端。
 *
 * ## 与 EVM 的流程差异
 * TRON **不要求**钱包自己拼装交易字节。标准流程是：
 * 1. 调用 `/wallet/createtransaction`（或 `triggersmartcontract`）让节点构建**未签名交易**，
 *    返回 `raw_data` / `raw_data_hex` / `txID`；
 * 2. 钱包对 `txID`（即 `SHA256(raw_data)`）签名；
 * 3. 把原交易对象加上 `signature` 字段，调用 `/wallet/broadcasttransaction`。
 *
 * 这样钱包不需要实现 protobuf 编码与能量/带宽计算 —— 但这些逻辑属于节点职责，
 * 也正因如此，**确认页必须解析 `raw_data_hex` 原始字节**（见 [TronRawData]），
 * 而不是信任同一次响应里的 JSON 描述。
 */
class TronRpc(private val rest: RestClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** 默认的 TRC-20 调用费用上限：100 TRX。超出会被节点拒绝而不是静默扣光。 */
    val defaultFeeLimitSun: Long = 100_000_000L

    // ------------------------------------------------------------ 构建交易

    /** 构建 TRX 转账。 */
    suspend fun createTransfer(
        baseUrls: List<String>,
        ownerHex41: String,
        toHex41: String,
        amountSun: Long,
    ): TronUnsignedTransaction {
        val body = buildJsonObjectOf(
            "owner_address" to JsonPrimitive(ownerHex41.lowercase()),
            "to_address" to JsonPrimitive(toHex41.lowercase()),
            "amount" to JsonPrimitive(amountSun),
            "visible" to JsonPrimitive(false),
        )
        return TronUnsignedTransaction(extractTransaction(post(baseUrls, "/wallet/createtransaction", body)))
    }

    /** 构建 TRC-20 转账（`transfer(address,uint256)`）。 */
    suspend fun createTrc20Transfer(
        baseUrls: List<String>,
        ownerHex41: String,
        contractHex41: String,
        toAddress: String,
        amount: java.math.BigInteger,
        feeLimitSun: Long = defaultFeeLimitSun,
    ): TronUnsignedTransaction {
        val parameter = Bytes.toHex(TronAbi.transferParameter(toAddress, amount))
        val body = buildJsonObjectOf(
            "owner_address" to JsonPrimitive(ownerHex41.lowercase()),
            "contract_address" to JsonPrimitive(contractHex41.lowercase()),
            "function_selector" to JsonPrimitive(TronAbi.FUNCTION_TRANSFER),
            "parameter" to JsonPrimitive(parameter),
            "fee_limit" to JsonPrimitive(feeLimitSun),
            "call_value" to JsonPrimitive(0),
            "visible" to JsonPrimitive(false),
        )
        val root = post(baseUrls, "/wallet/triggersmartcontract", body)
        checkResultFlag(root)
        return TronUnsignedTransaction(extractTransaction(root))
    }

    // ------------------------------------------------------------ 查询

    /** 账户信息；账户不存在时返回 null（TRON 上「未激活账户」是合法状态）。 */
    suspend fun account(baseUrls: List<String>, addressHex41: String): TronAccount? {
        val body = buildJsonObjectOf(
            "address" to JsonPrimitive(addressHex41.lowercase()),
            "visible" to JsonPrimitive(false),
        )
        val root = post(baseUrls, "/wallet/getaccount", body)
        val obj = root as? JsonObject ?: return null
        if (obj.isEmpty() || obj["address"] == null) return null
        return runCatching { json.decodeFromJsonElement(TronAccount.serializer(), obj) }.getOrNull()
    }

    /**
     * 常量调用（不产生交易），返回 `constant_result` 的第一项（十六进制）。
     * 用于读取 TRC-20 的 `balanceOf` / `decimals` / `symbol`。
     */
    suspend fun constantCall(
        baseUrls: List<String>,
        ownerHex41: String,
        contractHex41: String,
        functionSelector: String,
        parameterHex: String,
    ): String? {
        val body = buildJsonObjectOf(
            "owner_address" to JsonPrimitive(ownerHex41.lowercase()),
            "contract_address" to JsonPrimitive(contractHex41.lowercase()),
            "function_selector" to JsonPrimitive(functionSelector),
            "parameter" to JsonPrimitive(parameterHex),
            "visible" to JsonPrimitive(false),
        )
        val root = post(baseUrls, "/wallet/triggerconstantcontract", body)
        val results = (root as? JsonObject)?.get("constant_result") as? JsonArray ?: return null
        return results.firstOrNull()?.jsonPrimitive?.contentOrNull
    }

    /** 读取 TRC-20 余额（最小单位）。 */
    suspend fun trc20Balance(
        baseUrls: List<String>,
        ownerHex41: String,
        contractHex41: String,
        holderAddress: String,
    ): java.math.BigInteger {
        val hex = constantCall(
            baseUrls,
            ownerHex41,
            contractHex41,
            TronAbi.FUNCTION_BALANCE_OF,
            Bytes.toHex(TronAbi.balanceOfParameter(holderAddress)),
        ) ?: return java.math.BigInteger.ZERO
        return TronAbi.decodeUintHex(hex)
    }

    suspend fun nowBlock(baseUrls: List<String>): TronBlock =
        json.decodeFromJsonElement(TronBlock.serializer(), post(baseUrls, "/wallet/getnowblock", JsonObject(emptyMap())))

    suspend fun transactionInfo(baseUrls: List<String>, txId: String): TronTransactionInfo? {
        val body = buildJsonObjectOf("value" to JsonPrimitive(txId))
        val obj = post(baseUrls, "/wallet/gettransactioninfobyid", body) as? JsonObject ?: return null
        if (obj.isEmpty() || obj["id"] == null) return null
        return runCatching { json.decodeFromJsonElement(TronTransactionInfo.serializer(), obj) }.getOrNull()
    }

    // ------------------------------------------------------------ 广播

    /**
     * 广播已签名的交易。
     * TRON 的失败信息常常是十六进制 UTF-8，这里做一层解码，方便直接展示给用户。
     */
    suspend fun broadcast(baseUrls: List<String>, signedPayload: JsonObject): TronBroadcastResult {
        val root = post(baseUrls, "/wallet/broadcasttransaction", signedPayload)
        val obj = root as? JsonObject
            ?: return TronBroadcastResult(result = false, message = "节点返回了非预期的响应")
        val result = (obj["result"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        return TronBroadcastResult(
            result = result,
            txid = (obj["txid"] as? JsonPrimitive)?.contentOrNull,
            code = (obj["code"] as? JsonPrimitive)?.contentOrNull,
            message = (obj["message"] as? JsonPrimitive)?.contentOrNull?.let(::decodeHexMessage),
        )
    }

    // ------------------------------------------------------------ 内部

    /** 若响应里带 `result: {result: false, message: ...}`，直接抛出可读错误。 */
    private fun checkResultFlag(root: JsonElement) {
        val resultObj = (root as? JsonObject)?.get("result") as? JsonObject ?: return
        val ok = (resultObj["result"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: true
        if (!ok) {
            val message = (resultObj["message"] as? JsonPrimitive)?.contentOrNull?.let(::decodeHexMessage)
                ?: "节点拒绝了该请求"
            throw AppException(AppError.Network(IllegalStateException(message)))
        }
    }

    /** `triggersmartcontract` 把交易包在 `transaction` 里；`createtransaction` 直接返回。 */
    private fun extractTransaction(root: JsonElement): JsonObject {
        val obj = root as? JsonObject
            ?: throw AppException(AppError.Network(IllegalStateException("节点返回了非对象响应")))
        val inner = obj["transaction"]
        val tx = if (inner is JsonObject) inner else obj
        if (tx["raw_data_hex"] == null || tx["txID"] == null) {
            throw AppException(AppError.Network(IllegalStateException("响应缺少 raw_data_hex 或 txID")))
        }
        return tx
    }

    private suspend fun post(baseUrls: List<String>, path: String, body: JsonObject): JsonElement =
        withFailover(baseUrls) { base ->
            val text = rest.postJson("${base.trimEnd('/')}$path", json.encodeToString(JsonObject.serializer(), body))
            runCatching { json.parseToJsonElement(text) }.getOrElse { throw AppException(AppError.Network(it)) }
        }

    private suspend fun <T> withFailover(baseUrls: List<String>, block: suspend (String) -> T): T {
        require(baseUrls.isNotEmpty()) { "TRON 节点列表为空" }
        var last: Throwable? = null
        for (base in baseUrls) {
            try {
                return block(base)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
            }
        }
        throw AppException(AppError.Network(last))
    }

    private fun buildJsonObjectOf(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(linkedMapOf(*pairs))

    private fun decodeHexMessage(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length % 2 != 0) return trimmed
        if (!trimmed.all { it in "0123456789abcdefABCDEF" }) return trimmed
        return runCatching { String(Bytes.fromHex(trimmed), Charsets.UTF_8) }
            .getOrDefault(trimmed)
            .ifBlank { trimmed }
    }
}

/**
 * 节点返回的未签名交易。保留原始 JSON，以便原样回传（TRON 的广播要求字段完整）。
 */
class TronUnsignedTransaction(val payload: JsonObject) {

    val txId: String
        get() = payload["txID"]?.jsonPrimitive?.contentOrNull
            ?: error("未签名交易缺少 txID")

    val rawDataHex: String
        get() = payload["raw_data_hex"]?.jsonPrimitive?.contentOrNull
            ?: error("未签名交易缺少 raw_data_hex")

    /** 被签名的 32 字节：`SHA256(raw_data)`。 */
    val txIdBytes: ByteArray get() = Bytes.fromHex(txId)

    /** 由原始字节解码出的交易内容 —— 确认页只应展示它。 */
    val rawData: TronRawData get() = TronRawData.decode(rawDataHex)

    /** 附加签名，得到可直接广播的对象。 */
    fun withSignature(signatureHex: String): JsonObject =
        JsonObject(payload.toMutableMap().apply {
            put("signature", JsonArray(listOf(JsonPrimitive(signatureHex))))
        })
}

/**
 * 签名并广播的完整流程，附带广播前的自检。
 */
object TronSigningFlow {

    /**
     * 对未签名交易签名。
     *
     * @return 十六进制签名字符串（130 个字符）
     * @throws IllegalStateException 当签名无法恢复到期望地址时（说明私钥或交易不匹配）
     */
    fun sign(unsigned: TronUnsignedTransaction, privateKey: ByteArray, expectedOwnerAddress: String): String {
        val txId = unsigned.txIdBytes
        val signature = TronSignature.sign(txId, privateKey)
        check(TronSignature.verify(txId, signature, expectedOwnerAddress)) {
            "签名校验失败：恢复出的地址与发起地址不一致，已中止广播"
        }
        return Bytes.toHex(signature)
    }
}

// ------------------------------------------------------------------ 响应模型

@Serializable
data class TronFrozen(
    /** `BANDWIDTH` 或 `ENERGY`；旧账户的默认质押可能为空值。 */
    val type: String? = null,
    /** 质押量（sun）。 */
    val amount: Long = 0,
)

@Serializable
data class TronAccount(
    val address: String = "",
    /** 可用余额（sun）。 */
    val balance: Long = 0,
    @SerialName("create_time") val createTime: Long = 0,
    /** V2 质押（`FreezeBalanceV2` 之后）。 */
    @SerialName("frozenV2") val frozenV2: List<TronFrozen> = emptyList(),
    @SerialName("net_usage") val netUsage: Long = 0,
    @SerialName("energy_usage") val energyUsage: Long = 0,
    @SerialName("totalNetWeight") val totalNetWeight: Long = 0,
    @SerialName("totalEnergyWeight") val totalEnergyWeight: Long = 0,
) {
    /** 已质押总量（sun）。 */
    val totalFrozenSun: Long get() = frozenV2.sumOf { it.amount }

    val activated: Boolean get() = createTime > 0
}

@Serializable
data class TronBlockRawData(
    val number: Long = 0,
    val timestamp: Long = 0,
)

@Serializable
data class TronBlockHeader(
    @SerialName("raw_data") val rawData: TronBlockRawData = TronBlockRawData(),
)

@Serializable
data class TronBlock(
    @SerialName("block_header") val blockHeader: TronBlockHeader? = null,
    @SerialName("blockID") val blockId: String? = null,
)

@Serializable
data class TronReceipt(
    @SerialName("energy_usage") val energyUsage: Long = 0,
    @SerialName("energy_fee") val energyFee: Long = 0,
    @SerialName("net_usage") val netUsage: Long = 0,
    @SerialName("net_fee") val netFee: Long = 0,
    val result: String? = null,
)

@Serializable
data class TronTransactionInfo(
    val id: String = "",
    /** 实际消耗的手续费（sun）。 */
    val fee: Long = 0,
    @SerialName("blockNumber") val blockNumber: Long = 0,
    @SerialName("blockTimeStamp") val blockTimeStamp: Long = 0,
    val receipt: TronReceipt? = null,
    @SerialName("contractResult") val contractResult: List<String> = emptyList(),
    val result: String? = null,
    @SerialName("resMessage") val resMessage: String? = null,
    @SerialName("contract_address") val contractAddress: String? = null,
) {
    /** 是否执行成功。 */
    val success: Boolean get() = (result == null || result == "SUCCESS") && (receipt?.result != "FAILED")
}

@Serializable
data class TronBroadcastResult(
    val result: Boolean = false,
    val txid: String? = null,
    val code: String? = null,
    val message: String? = null,
)
