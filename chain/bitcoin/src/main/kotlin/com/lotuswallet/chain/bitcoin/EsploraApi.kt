package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.network.rpc.RestClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Esplora 兼容 API 客户端。
 *
 * 为什么不用比特币 P2P 协议而是用 Esplora REST：
 * - 全节点需要同步数百 GB 数据，手机不可能承担；
 * - Esplora（blockstream.info / mempool.space / litecoinspace）提供标准的只读 HTTP 接口，
 *   无鉴权、无账号、不绑定用户 —— 完全符合 serverless 与隐私要求；
 * - 端点写在链配置里，可随时增删，客户端自动故障转移。
 */
class EsploraApi(private val rest: RestClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** 地址统计（余额、交易数）。 */
    suspend fun address(baseUrls: List<String>, address: String): EsploraAddress =
        getJson(baseUrls, "address/$address", EsploraAddress.serializer())

    /** 该地址的未花费输出。 */
    suspend fun utxos(baseUrls: List<String>, address: String): List<EsploraUtxo> =
        getJson(baseUrls, "address/$address/utxo", kotlinx.serialization.builtins.ListSerializer(EsploraUtxo.serializer()))

    /** 该地址的交易（Esplora 最多返回最近 50 笔，分页需带 last_seen_txid）。 */
    suspend fun transactions(baseUrls: List<String>, address: String): List<EsploraTx> =
        getJson(baseUrls, "address/$address/txs", kotlinx.serialization.builtins.ListSerializer(EsploraTx.serializer()))

    suspend fun transaction(baseUrls: List<String>, txId: String): EsploraTx =
        getJson(baseUrls, "tx/$txId", EsploraTx.serializer())

    /** 当前链尖高度。 */
    suspend fun tipHeight(baseUrls: List<String>): Long = getText(baseUrls, "blocks/tip/height").trim().toLong()

    /**
     * 广播原始交易，返回交易哈希。
     * Esplora 用纯文本返回 txid；失败时返回 4xx 与错误说明。
     */
    suspend fun broadcast(baseUrls: List<String>, rawHex: String): String = withFailover(baseUrls) { base ->
        rest.postText("${base.trimEnd('/')}/tx", rawHex.trim(), "text/plain").trim()
    }

    /** 费率估算表：`目标确认块数 → 聪/vB`。 */
    suspend fun feeEstimates(baseUrls: List<String>): Map<Int, Double> {
        val text = getText(baseUrls, "fee-estimates")
        val obj = runCatching { json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            val blocks = k.toIntOrNull() ?: return@mapNotNull null
            val rate = (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                ?: return@mapNotNull null
            blocks to rate
        }.toMap()
    }

    /**
     * 给出建议费率：取最接近 [targetBlocks] 的目标费率，向上取整且不低于 1 聪/vB。
     * 网络不可用时回退到 [fallback]。
     */
    suspend fun recommendedFeeRate(
        baseUrls: List<String>,
        targetBlocks: Int = 6,
        fallback: Long = 2,
    ): Long {
        val estimates = runCatching { feeEstimates(baseUrls) }.getOrDefault(emptyMap())
        if (estimates.isEmpty()) return fallback
        val candidate = estimates.entries
            .filter { it.key <= targetBlocks }
            .maxByOrNull { it.key }
            ?: estimates.minByOrNull { it.key }
            ?: return fallback
        return candidate.value.let { Math.ceil(it).toLong() }.coerceAtLeast(1L)
    }

    private suspend fun <T> getJson(
        baseUrls: List<String>,
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val text = getText(baseUrls, path)
        return try {
            json.decodeFromString(deserializer, text)
        } catch (t: Throwable) {
            throw AppException(AppError.Network(t))
        }
    }

    private suspend fun getText(baseUrls: List<String>, path: String): String =
        withFailover(baseUrls) { base -> rest.getText("${base.trimEnd('/')}/$path") }

    private suspend fun <T> withFailover(baseUrls: List<String>, block: suspend (String) -> T): T {
        require(baseUrls.isNotEmpty()) { "Esplora 端点列表为空" }
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
}

// ------------------------------------------------------------------ 响应模型

@Serializable
data class EsploraStatus(
    val confirmed: Boolean = false,
    @SerialName("block_height") val blockHeight: Int? = null,
    @SerialName("block_time") val blockTime: Long? = null,
)

@Serializable
data class EsploraUtxo(
    val txid: String,
    val vout: Long,
    /** 金额（聪）。 */
    val value: Long,
    val status: EsploraStatus = EsploraStatus(),
)

@Serializable
data class EsploraStats(
    @SerialName("funded_txo_sum") val fundedTxoSum: Long = 0,
    @SerialName("spent_txo_sum") val spentTxoSum: Long = 0,
    @SerialName("tx_count") val txCount: Int = 0,
)

@Serializable
data class EsploraAddress(
    val address: String = "",
    @SerialName("chain_stats") val chainStats: EsploraStats = EsploraStats(),
    @SerialName("mempool_stats") val mempoolStats: EsploraStats = EsploraStats(),
) {
    /** 已确认余额（聪）。 */
    val confirmedBalance: Long get() = chainStats.fundedTxoSum - chainStats.spentTxoSum

    /** 待确认净额（可为负，表示正在花出）。 */
    val pendingBalance: Long get() = mempoolStats.fundedTxoSum - mempoolStats.spentTxoSum

    val totalBalance: Long get() = confirmedBalance + pendingBalance

    val transactionCount: Int get() = chainStats.txCount + mempoolStats.txCount
}

@Serializable
data class EsploraVout(
    @SerialName("scriptpubkey") val scriptPubKey: String = "",
    @SerialName("scriptpubkey_address") val address: String? = null,
    /** 金额（聪）。 */
    val value: Long = 0,
)

@Serializable
data class EsploraVin(
    val txid: String? = null,
    val vout: Long? = null,
    val prevout: EsploraVout? = null,
    @SerialName("is_coinbase") val isCoinbase: Boolean = false,
)

@Serializable
data class EsploraTx(
    val txid: String,
    val version: Int = 0,
    val locktime: Long = 0,
    val vin: List<EsploraVin> = emptyList(),
    val vout: List<EsploraVout> = emptyList(),
    val size: Long = 0,
    val weight: Long = 0,
    /** 手续费（聪）。 */
    val fee: Long = 0,
    val status: EsploraStatus = EsploraStatus(),
) {
    /** 该交易从 [address] 得到的金额。 */
    fun receivedBy(address: String): Long =
        vout.filter { it.address == address }.sumOf { it.value }

    /** 该交易从 [address] 花出的金额。 */
    fun spentBy(address: String): Long =
        vin.filter { it.prevout?.address == address }.sumOf { it.prevout?.value ?: 0 }

    /** 净变化（正为收入，负为支出）。 */
    fun netFor(address: String): Long = receivedBy(address) - spentBy(address)

    fun confirmations(tipHeight: Long): Int {
        val height = status.blockHeight ?: return 0
        return (tipHeight - height + 1).coerceAtLeast(0).toInt()
    }
}
