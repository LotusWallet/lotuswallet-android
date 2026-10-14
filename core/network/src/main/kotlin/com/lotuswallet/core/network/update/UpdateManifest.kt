package com.lotuswallet.core.network.update

import com.lotuswallet.core.network.chain.RemoteEndpoints
import com.lotuswallet.core.network.rpc.RestClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 版本清单：`LotusWallet/download` 仓库里的 `versions.json`。
 *
 * 这是**唯一**的更新来源。为什么不做推送 / 不做后台服务：
 * 一个纯本地钱包不该为了「告诉你有个新版本」而在你手机上开一条长期连接，
 * 也不该有任何服务端能决定你的客户端何时该更新。用户主动点一下，链路上也只
 * 有一个静态 JSON 文件。
 *
 * 客户端只**提示**，不静默下载、不自动安装 —— 更新的决定权在用户手里。
 */
@Serializable
data class UpdateManifest(
    val schemaVersion: Int = 1,
    /** 渠道：`stable` / `beta`。客户端当前只跟随 stable。 */
    val channel: String = "stable",
    /**
     * 支持下限：**用户当前安装的** versionCode 低于这个值时，更新提示会变成强制。
     *
     * 注意比较对象是「当前安装版本」而不是「远端最新版」—— 见 [UpdateRepository.check] 的说明。
     * 0 表示没有下限（首次发布时用默认值），避免把用户逼到无版本可用的境地。
     */
    val minSupportedVersionCode: Int = 0,
    val latest: AppRelease? = null,
    val releases: List<AppRelease> = emptyList(),
    val legacy: List<AppRelease> = emptyList(),
)

/** 单个已发布的版本。 */
@Serializable
data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val releaseDate: String? = null,
    /** 是否强制更新（用户可以不从，但我们会显著提示）。 */
    val mandatory: Boolean = false,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    /** 该版本的归档直链。 */
    val downloadUrl: String? = null,
    /** 永远指向最新版的固定直链。 */
    val latestUrl: String? = null,
    /** Release 页面（展示更新说明、也给用户一个「是我自己去看的」入口）。 */
    val releasePageUrl: String? = null,
    val releaseNotes: ReleaseNotes? = null,
) {
    /** 展示给用户的下载入口：优先用固定直链，其次版本直链，最后 Release 页。 */
    val bestDownloadUrl: String?
        get() = latestUrl ?: downloadUrl ?: releasePageUrl
}

/** 分语言的更新说明。取不到当前语言时由 UI 回退到英文。 */
@Serializable
data class ReleaseNotes(
    @SerialName("zh") val chinese: List<String> = emptyList(),
    @SerialName("en") val english: List<String> = emptyList(),
) {
    fun forLanguage(isChinese: Boolean): List<String> =
        if (isChinese && chinese.isNotEmpty()) chinese else english.ifEmpty { chinese }
}

/** 一次检查更新的结果。 */
sealed interface UpdateCheckResult {

    /** 有新版本可用。 */
    data class Available(val release: AppRelease, val required: Boolean) : UpdateCheckResult

    /** 已经是最新。 */
    data object UpToDate : UpdateCheckResult

    /** 检查失败（没网 / 仓库不可达 / 数据格式不认识）。 */
    data class Failed(val cause: Throwable?) : UpdateCheckResult
}

/**
 * 检查更新。
 *
 * 设计上刻意「无状态、无缓存」：用户点一次就真去拉一次，
 * 免得出现「提示有新版本但下载页没有」这种自相矛盾的状态。
 */
class UpdateRepository(
    private val rest: RestClient,
    private val remoteUrlsProvider: () -> List<String> = { RemoteEndpoints.UPDATE_MANIFESTS },
) {

    private val json = Json {
        ignoreUnknownKeys = true   // 远端将来加字段不能把老客户端搞崩
        isLenient = true
    }

    /**
     * @param currentVersionCode 当前安装包的 versionCode（BuildConfig.VERSION_CODE）
     */
    suspend fun check(currentVersionCode: Int): UpdateCheckResult {
        val urls = remoteUrlsProvider()
        if (urls.isEmpty()) return UpdateCheckResult.UpToDate

        val manifest = try {
            json.decodeFromString(UpdateManifest.serializer(), rest.getText(urls))
        } catch (t: Throwable) {
            return UpdateCheckResult.Failed(t)
        }

        val latest = manifest.latest ?: manifest.releases.maxByOrNull { it.versionCode }
        if (latest == null || latest.versionCode <= currentVersionCode) {
            return UpdateCheckResult.UpToDate
        }

        // 两条**互相独立**的强制更新路径：
        //  1. 这个版本自己声明 mandatory；
        //  2. 用户手上这一版已经低于支持下限（minSupportedVersionCode）。
        //
        // ⚠️ 第 2 条比较的必须是**当前安装的版本**，不是远端最新版的版本号。
        //    历史实现写成了 `latest.versionCode <= minSupportedVersionCode`，
        //    等于问「最新版是否低于支持下限」—— 那永远只在一个被误配的清单上才成立，
        //    真正需要被强推的老客户端反而被放过，功能刚好被反转。
        //    下限为 0（默认值）时 currentVersionCode < 0 恒不成立，自然等于「没有下限」。
        val belowSupportFloor = currentVersionCode < manifest.minSupportedVersionCode
        return UpdateCheckResult.Available(latest, latest.mandatory || belowSupportFloor)
    }
}
