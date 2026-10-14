package com.lotuswallet.core.network.rpc

import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 客户端（EVM 与 TRON 都用它）。
 *
 * 设计要点：
 * - **故障转移**：`rpcUrls` 里任何一个端点成功即返回；全部失败才抛错。
 * - **错误分流**：节点返回的业务错误（`error` 字段）与非 2xx / 网络错误都会尝试下一个端点，
 *   因为不同节点支持的方法集不同（例如部分节点不开放 `eth_getLogs` 的宽区间查询）。
 * - 只读：本客户端不用于任何需要鉴权的接口。
 */
class JsonRpcClient(
    private val rest: RestClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    },
) {

    /**
     * 发起一次调用并反序列化 `result` 字段。
     *
     * @param rpcUrls 端点列表，按顺序故障转移
     * @param params JSON-RPC 参数数组
     */
    suspend fun <T> call(
        rpcUrls: List<String>,
        method: String,
        params: JsonElement = JsonArray(emptyList()),
        deserializer: DeserializationStrategy<T>,
    ): T {
        require(rpcUrls.isNotEmpty()) { "RPC 端点列表为空" }
        var last: Throwable? = null
        for (url in rpcUrls) {
            try {
                return callSingle(url, method, params, deserializer)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
            }
        }
        throw AppException(AppError.Network(last))
    }

    /** 调用并直接返回 `result` 原始 JSON，交给调用方自行解析。 */
    suspend fun callRaw(
        rpcUrls: List<String>,
        method: String,
        params: JsonElement = JsonArray(emptyList()),
    ): JsonElement = call(rpcUrls, method, params, JsonElement.serializer())

    private suspend fun <T> callSingle(
        url: String,
        method: String,
        params: JsonElement,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", REQUEST_ID)
            put("method", method)
            put("params", params)
        }
        val responseText = rest.postJson(url, json.encodeToString(JsonObject.serializer(), payload))

        val root = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (t: Throwable) {
            throw AppException(AppError.Network(t))
        }

        val error = root["error"]
        if (error != null && error !is JsonNull) {
            val code = error.jsonObject["code"]?.jsonPrimitive?.intOrNull
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "JSON-RPC 错误"
            throw AppException(AppError.Network(IllegalStateException(message), code = code))
        }

        val result = root["result"]
            ?: throw AppException(AppError.Network(IllegalStateException("响应缺少 result 字段")))

        return try {
            json.decodeFromJsonElement(deserializer, result)
        } catch (t: Throwable) {
            throw AppException(AppError.Network(t))
        }
    }

    private companion object {
        const val REQUEST_ID = 1
    }
}
