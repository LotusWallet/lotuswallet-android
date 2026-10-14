package com.lotuswallet.core.network.chain

import kotlinx.serialization.json.Json

/**
 * 链配置注册表：内置清单 + 远端清单 + 用户自定义（逐层覆盖）。
 *
 * 数据来源优先级：
 * ```
 * 内置 chains.json  <  远端清单（GitHub raw，可覆盖同 id 的配置、也可新增链）
 *                  <  用户自定义（本机「链管理」里新增/修改的）
 * ```
 * 任意一层缺失或损坏时**静默降级**到下一层，不影响可用性 —— 这是 serverless 的前提：
 * 没有网络也必须能看自己的资产；用户改坏了一条自定义链，也只是那一条不生效。
 */
object ChainRegistry {

    /** 宽松解析：允许未来出现本版本还不认识的链类型（跳过而不是整份失败）。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        classDiscriminator = "family"
    }

    private const val BUILTIN_RESOURCE = "/chains.json"

    private val builtinCache: List<ChainConfig> by lazy { loadBuiltin() }

    /** 内置链清单（随 APK 发布，离线可用）。 */
    fun builtin(): List<ChainConfig> = builtinCache

    /** 内置链 id 集合，用于区分「用户自建的链」与「用户覆盖的内置链」。 */
    fun builtinIds(): Set<String> = builtinCache.map { it.id }.toSet()

    /** 解析一段 JSON 清单。格式非法时抛异常，便于测试暴露问题。 */
    fun parse(raw: String): List<ChainConfig> = json.decodeFromString(ChainManifest.serializer(), raw).chains

    /** 宽松解析：失败返回 null 而不是抛异常（用于远端/用户数据这类不可控输入）。 */
    fun parseOrNull(raw: String?): List<ChainConfig>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { parse(raw) }.getOrNull()
    }

    // ------------------------------------------------------------ 用户覆盖

    fun parseUserOverrides(raw: String?): UserChainOverrides {
        if (raw.isNullOrBlank()) return UserChainOverrides()
        return runCatching { json.decodeFromString(UserChainOverrides.serializer(), raw) }
            .getOrElse { UserChainOverrides() }
    }

    fun encodeUserOverrides(overrides: UserChainOverrides): String =
        json.encodeToString(UserChainOverrides.serializer(), overrides)

    // ------------------------------------------------------------ 合并

    /**
     * 合并三层清单。顺序即优先级：后写覆盖先写，且**保持首次出现的相对顺序**，
     * 因此内置链的排列稳定，新加的链追加在末尾。
     *
     * 注意 [user] 的格式与 [remote] **不同**：
     * - 远端是 `ChainManifest`（`{"chains":[…]}`）；
     * - 用户是本机的 `UserChainOverrides`（`{"modified":[…]}`）。
     * 两者曾在这里被当成同一种结构解析，结果用户新建/修改的链接不上字段、
     * 整层覆盖**静默失效**（`chains` 取不到就得到一个空列表，不报错）。
     */
    fun merge(remote: String?, user: String? = null): List<ChainConfig> {
        val base = LinkedHashMap<String, ChainConfig>()
        builtin().forEach { base[it.id] = it }
        parseOrNull(remote)?.forEach { base[it.id] = it }
        parseUserOverrides(user).modified.forEach { base[it.id] = it }
        return base.values.toList()
    }

    private fun loadBuiltin(): List<ChainConfig> {
        val stream = ChainRegistry::class.java.getResourceAsStream(BUILTIN_RESOURCE)
            ?: error("缺少内置链清单资源 $BUILTIN_RESOURCE")
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(raw)
    }
}

/** `chains.json` 的顶层结构。 */
@kotlinx.serialization.Serializable
data class ChainManifest(
    val version: Int = 1,
    val chains: List<ChainConfig> = emptyList(),
)
