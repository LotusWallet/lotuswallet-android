package com.lotuswallet.core.network.contract

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainRegistry
import com.lotuswallet.core.network.chain.EvmChainConfig
import com.lotuswallet.core.network.chain.UtxoChainConfig
import com.lotuswallet.core.network.market.MarketManifest
import com.lotuswallet.core.network.update.UpdateManifest
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.LocalDate

/**
 * **跨仓库契约**测试：把发布侧的真实产物喂给客户端真正的解析器。
 *
 * 这三份 JSON 是客户端唯一的远端输入，出错的表现全是**静默失效**：
 * 链拉不到 → 只看到内置的那几条；市场清单坏了 → 退回内置；版本清单坏了 →
 * 永远显示「已是最新」，用户再也收不到升级提示。这些在真机上几乎无法复现，
 * 所以在这里用夹具把它们按住。
 *
 * 夹具由 `tools/sync_contract_fixtures.py` 从 `repos/assets`、`repos/download-src`
 * 同步而来（该脚本的 `--check` 模式可放进 CI，防止夹具过期）。
 */
class PublishedManifestContractTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/contract/$name")
            ?: error("缺少契约夹具 /contract/$name，跑 `python tools/sync_contract_fixtures.py` 生成")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    // ------------------------------------------------------------- 链清单

    @Test
    fun `发布出去的链清单能被客户端解析器完整接收`() {
        val chains = ChainRegistry.parse(fixture("chains.json"))

        assertThat(chains.size).isAtLeast(40)

        // 每一族的链一条都不能少，否则用户会发现「我的币不见了」
        val families = chains.map { it.family }.toSet()
        assertThat(families).containsAtLeast(
            ChainConfig.Family.EVM,
            ChainConfig.Family.UTXO,
            ChainConfig.Family.TRON,
            ChainConfig.Family.ED25519,
            ChainConfig.Family.FILECOIN,
            ChainConfig.Family.CHIA,
        )

        for (chain in chains) {
            assertThat(chain.id).isNotEmpty()
            assertThat(chain.name).isNotEmpty()
            assertThat(chain.symbol).isNotEmpty()
            assertThat(chain.decimals).isIn(0..18)
            // Chia 的公网节点没有公认稳定入口，清单里允许留空
            if (chain !is com.lotuswallet.core.network.chain.ChiaChainConfig) {
                assertThat(chain.rpcUrls).isNotEmpty()
            }
            assertThat(chain.rpcUrls.all { it.startsWith("https://") }).isTrue()
            assertThat(chain.coinType).isAtLeast(0)
        }
    }

    @Test
    fun `非 EVM 新链族的必填字段必须齐全_否则地址会派生错`() {
        val chains = ChainRegistry.parse(fixture("chains.json"))

        val ed25519 = chains.filterIsInstance<com.lotuswallet.core.network.chain.Ed25519ChainConfig>()
        val filecoin = chains.filterIsInstance<com.lotuswallet.core.network.chain.FilecoinChainConfig>()
        val chia = chains.filterIsInstance<com.lotuswallet.core.network.chain.ChiaChainConfig>()

        // 用户点名要有的链，一条都不能少
        assertThat(chains.map { it.id }).containsAtLeast(
            "bitcoin", "litecoin", "dash", "solana", "icp", "monero", "filecoin", "chia",
        )

        for (chain in ed25519) {
            assertThat(chain.path).isNotEmpty()
            // SLIP-0010 只支持硬化派生，路径里出现非硬化层本身就说明配置错了
            if (chain.derivation == com.lotuswallet.core.network.chain.Ed25519Derivation.SLIP10) {
                val segments = chain.path.removePrefix("m/").split("/").filter { it.isNotBlank() }
                assertThat(segments.all { it.endsWith("'") || it.endsWith("h") || it.endsWith("H") }).isTrue()
            }
        }

        for (chain in filecoin) {
            assertThat(chain.path).isNotEmpty()
            assertThat(chain.network).isIn(listOf("f", "t"))
        }

        for (chain in chia) {
            assertThat(chain.hrp).isNotEmpty()
            assertThat(chain.path).isNotEmpty()
        }

        // Monero 必须带网络前缀字节，否则地址编出来是错的
        val monero = ed25519.first { it.id == "monero" }
        assertThat(monero.addressPrefix).isEqualTo(18)
    }

    @Test
    fun `链 id 与 EVM chainId 都必须唯一`() {
        val chains = ChainRegistry.parse(fixture("chains.json"))

        // id 是数据库外键，重复会让两条链的数据串在一起
        assertThat(chains.map { it.id }).containsNoDuplicates()

        // chainId 决定签名时的 EIP-155 字段，重复意味着同一笔交易可以在两条链上重放
        val evmChainIds = chains.filterIsInstance<EvmChainConfig>().map { it.chainId }
        assertThat(evmChainIds).containsNoDuplicates()
    }

    @Test
    fun `所有 EVM 链必须共用 coinType 60_否则同一助记词地址会变`() {
        val evm = ChainRegistry.parse(fixture("chains.json")).filterIsInstance<EvmChainConfig>()

        assertThat(evm).isNotEmpty()
        for (chain in evm) {
            assertThat(chain.coinType).isEqualTo(60)
            assertThat(chain.chainId).isGreaterThan(0)
        }
    }

    @Test
    fun `UTXO 链的地址类型与浏览器配置必须自洽`() {
        val utxo = ChainRegistry.parse(fixture("chains.json")).filterIsInstance<UtxoChainConfig>()

        assertThat(utxo).isNotEmpty()
        for (chain in utxo) {
            assertThat(chain.decimals).isEqualTo(8)
            // 非 SegWit 地址类型不该配 bech32 前缀；反之 SegWit 必须有
            val needsHrp = chain.addressType.purpose > 44
            if (needsHrp) assertThat(chain.hrp).isNotNull()
        }
        // 比特币的基础参数一旦漂了，所有用户的钱包地址都会变
        val btc = utxo.first { it.id == "bitcoin" }
        assertThat(btc.hrp).isEqualTo("bc")
        assertThat(btc.coinType).isEqualTo(0)
        assertThat(btc.p2pkhVersion).isEqualTo(0)
    }

    @Test
    fun `远端链清单不会挤掉任何一条内置链`() {
        val builtin = ChainRegistry.builtin().map { it.id }
        val merged = ChainRegistry.merge(fixture("chains.json")).map { it.id }

        assertThat(merged).containsAtLeastElementsIn(builtin)
        assertThat(merged).containsNoDuplicates()
    }

    @Test
    fun `远端与内置对同一条链的资产关键参数必须一致_否则联网与否行为不同`() {
        // 同一个 id 在内置清单与远端清单里若有差异，用户会看到
        //「开着网余额正常、断网余额变了」这类灵异现象。这里只比对**一旦不一致就会
        // 造成资产显示/地址错误**的字段；rpcUrls 本来就该随远端刷新而变化，不参与比对。
        val builtin = ChainRegistry.builtin().associateBy { it.id }
        val remote = ChainRegistry.parse(fixture("chains.json")).associateBy { it.id }

        val shared = builtin.keys intersect remote.keys
        assertThat(shared).isNotEmpty()
        assertThat(shared.size).isAtLeast(40)

        for (id in shared) {
            val local = builtin.getValue(id)
            val online = remote.getValue(id)

            assertThat(online.family).isEqualTo(local.family)
            assertThat(online.symbol).isEqualTo(local.symbol)
            assertThat(online.decimals).isEqualTo(local.decimals)
            // coinType 漂了 → 派生路径变了 → 同一助记词算出来的地址都不一样
            assertThat(online.coinType).isEqualTo(local.coinType)

            if (local is EvmChainConfig && online is EvmChainConfig) {
                // chainId 漂了 → 交易签名里的 EIP-155 链号变了 → 交易直接失败或跨链重放
                assertThat(online.chainId).isEqualTo(local.chainId)
            }
            if (local is UtxoChainConfig && online is UtxoChainConfig) {
                assertThat(online.hrp).isEqualTo(local.hrp)
                assertThat(online.addressType).isEqualTo(local.addressType)
            }
        }
    }

    // ------------------------------------------------------------- 市场清单

    @Test
    fun `发布出去的市场清单能被客户端解析且条目自洽`() {
        val manifest = json.decodeFromString(MarketManifest.serializer(), fixture("dapps.json"))

        assertThat(manifest.dapps).isNotEmpty()
        assertThat(manifest.dapps.map { it.id }).containsNoDuplicates()

        val categoryIds = manifest.categories.map { it.id }.toSet()
        for (dapp in manifest.dapps) {
            assertThat(dapp.id).isNotEmpty()
            assertThat(dapp.name).isNotEmpty()
            assertThat(dapp.category).isIn(categoryIds)
            // 只在 https 上打开，避免从钱包里跳到一个明文地址
            assertThat(dapp.url.startsWith("https://")).isTrue()
            assertThat(dapp.order).isAtLeast(0)
        }
    }

    // ------------------------------------------------------------- 版本清单

    @Test
    fun `发布出去的版本清单能被客户端解析且字段齐全`() {
        val manifest = json.decodeFromString(UpdateManifest.serializer(), fixture("versions.json"))

        assertThat(manifest.channel).isEqualTo("stable")

        val latest = manifest.latest
        assertThat(latest).isNotNull()
        requireNotNull(latest)

        // 版本号规则：versionCode = major*10000 + minor*100 + patch
        val parts = latest.versionName.split(".")
        assertThat(parts).hasSize(3)
        val expectedCode = parts[0].toInt() * 10_000 + parts[1].toInt() * 100 + parts[2].toInt()
        assertThat(latest.versionCode).isEqualTo(expectedCode)

        assertThat(latest.sizeBytes ?: 0L).isGreaterThan(0L)
        assertThat(requireNotNull(latest.sha256)).matches("[0-9a-f]{64}")
        assertThat(requireNotNull(latest.downloadUrl).startsWith("https://")).isTrue()
        assertThat(requireNotNull(latest.latestUrl).startsWith("https://")).isTrue()

        // releaseDate 必须是 ISO 日期，否则 UI 上的日期会变成乱码或空
        val date = requireNotNull(latest.releaseDate)
        LocalDate.parse(date)
    }

    @Test
    fun `版本清单里的 latest 与 releases 必须指向同一个版本`() {
        val manifest = json.decodeFromString(UpdateManifest.serializer(), fixture("versions.json"))
        val latest = requireNotNull(manifest.latest)

        val sameCode = manifest.releases.filter { it.versionCode == latest.versionCode }
        assertThat(sameCode).isNotEmpty()
        assertThat(sameCode.single().sha256).isEqualTo(latest.sha256)
    }

    @Test
    fun `支持下限不能高过最新版本_否则会把所有用户逼进强制更新`() {
        val manifest = json.decodeFromString(UpdateManifest.serializer(), fixture("versions.json"))
        val latest = requireNotNull(manifest.latest)

        assertThat(manifest.minSupportedVersionCode).isAtMost(latest.versionCode)
    }
}
