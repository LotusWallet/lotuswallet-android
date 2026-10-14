package com.lotuswallet.core.network.rpc

import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 全应用唯一的 HTTP 传输层。
 *
 * 约束（与 serverless 目标一致）：
 * - **只出站**，不携带任何用户标识、不发送 Cookie、不做鉴权；
 * - 不使用 OkHttp 连接池之外的任何持久化；
 * - 所有请求都带 UA 版本号，便于公共服务做限流统计（不含设备指纹）。
 */
object HttpTransport {

    const val USER_AGENT = "LotusWallet/0.1 (Android; +no-tracking)"

    fun defaultClient(debugLogging: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
        if (debugLogging) {
            builder.addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply {
                level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
            })
        }
        return builder.build()
    }
}

/**
 * 普通 REST 客户端接口，供 UTXO 链（Esplora）、RPC 与远端清单拉取使用。
 *
 * **为什么是接口**：链清单 / 市场清单 / 版本清单这三个仓库的**处理逻辑**
 * （多端点失败转移、失败降级、版本比较）才是真正容易出错的地方，
 * 而它们出错的后果是「静默不生效」——真机上极难复现。
 * 抽成接口后可以用一个纯内存的假实现把这些分支全部覆盖，不需要联网、不需要模拟器。
 *
 * 默认实现已把「按顺序尝试多个等价地址」的失败转移放在这里，
 * 因此假实现只需覆盖单地址的 [getText] 即可获得同样的语义。
 */
interface RestClient {

    suspend fun getText(url: String): String

    /**
     * 按顺序尝试多个等价地址，第一个成功的返回。
     *
     * 为什么需要：仓库内容在 GitHub 上有多个可读的前缀（`raw.githubusercontent.com`
     * 是官方源、`cdn.jsdelivr.net` 是第三方 CDN 镜像），它们在国内的可达性经常此消彼长。
     * 让失败转移发生在传输层，上层就只需要关心「拿到了什么内容」。
     */
    suspend fun getText(urls: List<String>): String = withFailover(urls) { getText(it) }

    suspend fun postText(url: String, body: String, mediaType: String = "application/json"): String

    suspend fun postJson(url: String, body: String): String =
        postText(url, body, "application/json; charset=utf-8")
}

/** [RestClient] 的 OkHttp 实现 —— 应用里唯一真正出网的地方。 */
class OkHttpRestClient(private val http: OkHttpClient) : RestClient {

    override suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        execute(request)
    }

    override suspend fun postText(url: String, body: String, mediaType: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).post(body.toRequestBody(mediaType.toMediaType())).build()
            execute(request)
        }

    private fun execute(request: Request): String {
        val response = try {
            http.newCall(request).execute()
        } catch (t: Throwable) {
            throw AppException(AppError.Network(t))
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw AppException(AppError.Network(IllegalStateException(text.take(512)), code = it.code))
            }
            return text
        }
    }
}

/**
 * 按顺序尝试多个端点，全部失败才抛最后一个错误。
 *
 * 为什么需要：公共 RPC 节点不可靠（限流、停机、地区封锁）。多条链配置里都写了
 * 备用端点，故障转移要在传输层做掉，业务层不该关心。
 */
internal suspend fun <T> withFailover(urls: List<String>, block: suspend (String) -> T): T {
    require(urls.isNotEmpty()) { "端点列表为空" }
    var last: Throwable? = null
    for (url in urls) {
        try {
            return block(url)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            last = t
        }
    }
    throw AppException(AppError.Network(last))
}
