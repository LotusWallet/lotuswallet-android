package com.lotuswallet.core.network.chain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 「链管理」表单的草稿 → 配置转换。
 *
 * 这一层是用户在「链管理」里自己加链/改链的唯一入口，校验漏一处就会把坏配置写进
 * DataStore，然后以「余额永远是 —」或「地址派生不对」的形式表现出来。
 * 因此这里逐族覆盖：默认值、必填项、以及**编辑已有链时不能丢字段**。
 */
class ChainDraftTest {

    // ------------------------------------------------------------ 默认值与必填

    @Test
    fun `新建草稿按家族给出合理初值`() {
        val evm = ChainDraft.empty(ChainConfig.Family.EVM)
        assertThat(evm.decimals).isEqualTo("18")
        assertThat(evm.coinType).isEqualTo("60")
        assertThat(evm.isNew).isTrue()

        val utxo = ChainDraft.empty(ChainConfig.Family.UTXO)
        assertThat(utxo.decimals).isEqualTo("8")
        assertThat(utxo.hrp).isEqualTo("bc")
        assertThat(utxo.dustThreshold).isEqualTo("546")

        val solana = ChainDraft.empty(ChainConfig.Family.ED25519)
        assertThat(solana.variant).isEqualTo(Ed25519Variant.SOLANA)
        assertThat(solana.derivation).isEqualTo(Ed25519Derivation.SLIP10)
        assertThat(solana.path).isEqualTo("m/44'/501'/0'/0'")
        assertThat(solana.addressPrefix).isEmpty()

        val monero = ChainDraft.empty(ChainConfig.Family.ED25519)
            .copy(variant = Ed25519Variant.MONERO, coinType = "128", path = "m/44'/128'/0'", addressPrefix = "18")
        val moneroConfig = monero.copy(name = "Monero", symbol = "xmr", rpcUrls = listOf("https://x")).build()
        assertThat(moneroConfig).isInstanceOf(Ed25519ChainConfig::class.java)
        assertThat((moneroConfig as Ed25519ChainConfig).addressPrefix).isEqualTo(18)

        val filecoin = ChainDraft.empty(ChainConfig.Family.FILECOIN)
        assertThat(filecoin.path).isEqualTo("m/44'/461'/0'/0/0")
        assertThat(filecoin.network).isEqualTo("f")

        val chia = ChainDraft.empty(ChainConfig.Family.CHIA)
        assertThat(chia.path).isEqualTo("m/12381/8444/2/0")
        assertThat(chia.chiaHrp).isEqualTo("xch")
    }

    @Test
    fun `EVM 缺链 ID 时构建失败并给出可读原因`() {
        val draft = ChainDraft.empty(ChainConfig.Family.EVM)
            .copy(name = "Test", symbol = "T", rpcUrls = listOf("https://rpc.test"))
        assertThat(draft.build()).isNull()
        assertThat(draft.validationError()).isEqualTo("链 ID 必须是数字")
    }

    @Test
    fun `名称 符号 精度 节点地址缺一不可`() {
        val base = ChainDraft.empty(ChainConfig.Family.EVM)
            .copy(name = "Test", symbol = "T", chainId = "137", rpcUrls = listOf("https://rpc.test"))

        assertThat(base.build()).isNotNull()
        assertThat(base.copy(name = "  ").build()).isNull()
        assertThat(base.copy(name = "  ").validationError()).isEqualTo("名称不能为空")
        assertThat(base.copy(symbol = "").validationError()).isEqualTo("符号不能为空")
        assertThat(base.copy(decimals = "abc").validationError()).isEqualTo("精度必须是数字")
        assertThat(base.copy(rpcUrls = listOf("", "   ")).validationError()).isEqualTo("至少需要一条节点地址")
    }

    @Test
    fun `节点地址必须有协议头——否则运行时才发现连不上`() {
        val bad = ChainDraft.empty(ChainConfig.Family.EVM)
            .copy(name = "Test", symbol = "T", chainId = "137", rpcUrls = listOf("rpc.test"))
        assertThat(bad.build()).isNull()

        val mixed = bad.copy(rpcUrls = listOf("", "https://ok.test", "  "))
        assertThat(mixed.build()).isNotNull()
        assertThat(mixed.build()!!.rpcUrls).containsExactly("https://ok.test")
    }

    @Test
    fun `多节点地址按列表保留并去空白`() {
        val draft = ChainDraft.empty(ChainConfig.Family.EVM).copy(
            name = "Test", symbol = "T", chainId = "137",
            rpcUrls = listOf("  https://a.test ", "https://b.test", ""),
        )
        val config = draft.build()!!
        assertThat(config.rpcUrls).containsExactly("https://a.test", "https://b.test").inOrder()
    }

    @Test
    fun `SegWit 地址类型必须填 bech32 前缀`() {
        val segwit = ChainDraft.empty(ChainConfig.Family.UTXO).copy(
            name = "Test", symbol = "T", rpcUrls = listOf("https://e.test"), hrp = "",
        )
        assertThat(segwit.build()).isNull()

        // 传统地址不需要前缀，可以为空
        val legacy = segwit.copy(addressType = UtxoAddressType.LEGACY)
        assertThat(legacy.build()).isNotNull()
        assertThat((legacy.build() as UtxoChainConfig).hrp).isNull()
    }

    @Test
    fun `ed25519 家族必须填派生路径`() {
        val draft = ChainDraft.empty(ChainConfig.Family.ED25519).copy(
            name = "Test", symbol = "T", rpcUrls = listOf("https://r.test"), path = "",
        )
        assertThat(draft.build()).isNull()
        assertThat(draft.validationError()).isEqualTo("派生路径不能为空")
    }

    @Test
    fun `Filecoin 与 Chia 的路径留空时回落到标准路径`() {
        val filecoin = ChainDraft.empty(ChainConfig.Family.FILECOIN).copy(
            name = "Filecoin", symbol = "FIL", rpcUrls = listOf("https://f.test"), path = "",
        )
        assertThat((filecoin.build() as FilecoinChainConfig).path).isEqualTo("m/44'/461'/0'/0/0")

        val chia = ChainDraft.empty(ChainConfig.Family.CHIA).copy(
            name = "Chia", symbol = "XCH", rpcUrls = listOf("https://c.test"), path = "",
        )
        assertThat((chia.build() as ChiaChainConfig).path).isEqualTo("m/12381/8444/2/0")
    }

    // ------------------------------------------------------------ 规范化

    @Test
    fun `id 自动生成——EVM 用 chainId UTXO 用 coinType`() {
        val evm = ChainDraft.empty(ChainConfig.Family.EVM)
            .copy(name = "Test", symbol = "T", chainId = "137", rpcUrls = listOf("https://r.test"))
            .build()!!
        assertThat(evm.id).isEqualTo("evm-137")

        val utxo = ChainDraft.empty(ChainConfig.Family.UTXO)
            .copy(name = "Test", symbol = "T", coinType = "2", rpcUrls = listOf("https://r.test"))
            .build()!!
        assertThat(utxo.id).isEqualTo("utxo-2")

        val filecoin = ChainDraft.empty(ChainConfig.Family.FILECOIN)
            .copy(name = "Test", symbol = "FIL", rpcUrls = listOf("https://r.test"))
            .build()!!
        assertThat(filecoin.id).isEqualTo("filecoin")
    }

    @Test
    fun `id 小写化 符号大写化——避免同一条链出现两个 id`() {
        val config = ChainDraft.empty(ChainConfig.Family.EVM)
            .copy(
                id = "  MyChain ", name = "  My Chain  ", symbol = "mych", chainId = "137",
                rpcUrls = listOf("https://r.test"),
            )
            .build()!!
        assertThat(config.id).isEqualTo("mychain")
        assertThat(config.name).isEqualTo("My Chain")
        assertThat(config.symbol).isEqualTo("MYCH")
    }

    @Test
    fun `空的可选字段落成 null 而不是空串`() {
        val config = ChainDraft.empty(ChainConfig.Family.EVM)
            .copy(
                name = "Test", symbol = "T", chainId = "137", rpcUrls = listOf("https://r.test"),
                explorerTxUrl = "", explorerAddressUrl = "   ", iconUrl = "",
            )
            .build()!!
        assertThat(config.explorerTxUrl).isNull()
        assertThat(config.explorerAddressUrl).isNull()
        assertThat(config.iconUrl).isNull()
    }

    @Test
    fun `精度与币种编号的非法值被拦下`() {
        val base = ChainDraft.empty(ChainConfig.Family.EVM)
            .copy(name = "Test", symbol = "T", chainId = "137", rpcUrls = listOf("https://r.test"))
        assertThat(base.copy(decimals = "37").build()).isNull()
        assertThat(base.copy(decimals = "-1").build()).isNull()
        assertThat(base.copy(coinType = "-5").build()).isNull()
        assertThat(base.copy(chainId = "0").build()).isNull()
    }

    @Test
    fun `校验失败时必须有可读原因——否则保存按钮会变成没反应的死键`() {
        val base = ChainDraft.empty(ChainConfig.Family.EVM)
            .copy(name = "Test", symbol = "T", chainId = "137", rpcUrls = listOf("https://r.test"))

        val broken = listOf(
            base.copy(name = " ") to "名称不能为空",
            base.copy(symbol = "") to "符号不能为空",
            base.copy(decimals = "abc") to "精度必须是数字",
            base.copy(decimals = "37") to "精度应在 0..36 之间",
            base.copy(coinType = "-5") to "币种编号不能为负",
            base.copy(rpcUrls = listOf("  ")) to "至少需要一条节点地址",
            base.copy(rpcUrls = listOf("rpc.test")) to "节点地址必须以 http:// 或 https:// 开头",
            base.copy(chainId = "") to "链 ID 必须是数字",
            base.copy(chainId = "0") to "链 ID 必须大于 0",
        )
        for ((draft, expected) in broken) {
            assertThat(draft.build()).isNull()
            assertThat(draft.validationError()).isEqualTo(expected)
        }

        // 反过来：能建出来的草稿，validationError 必须是 null（两者同源）
        assertThat(base.build()).isNotNull()
        assertThat(base.validationError()).isNull()
    }

    // ------------------------------------------------------------ 编辑回填

    @Test
    fun `编辑已有链时字段不丢失——各族往返一致`() {
        val configs = listOf(
            EvmChainConfig(
                id = "polygon", name = "Polygon", symbol = "POL", chainId = 137,
                rpcUrls = listOf("https://polygon-rpc.com"), decimals = 18, coinType = 60,
                enabled = false, testnet = false, explorerTxUrl = "https://polygonscan.com/tx",
                explorerAddressUrl = "https://polygonscan.com/address", iconUrl = null,
                supportsEip1559 = true,
            ),
            UtxoChainConfig(
                id = "litecoin", name = "Litecoin", symbol = "LTC", coinType = 2,
                rpcUrls = listOf("https://litecoinspace.org/api"), decimals = 8,
                addressType = UtxoAddressType.NATIVE_SEGWIT, hrp = "ltc",
                p2pkhVersion = 48, p2shVersion = 50, dustThreshold = 5460,
            ),
            Ed25519ChainConfig(
                id = "monero", name = "Monero", symbol = "XMR", coinType = 128,
                rpcUrls = listOf("https://x.test"), variant = Ed25519Variant.MONERO,
                derivation = Ed25519Derivation.SLIP10, path = "m/44'/128'/0'",
                decimals = 12, addressPrefix = 18,
            ),
            FilecoinChainConfig(
                id = "filecoin", name = "Filecoin", symbol = "FIL", coinType = 461,
                rpcUrls = listOf("https://f.test"), path = "m/44'/461'/0'/0/0",
                addressPrefix = 1, network = "f", decimals = 18,
            ),
            ChiaChainConfig(
                id = "chia", name = "Chia", symbol = "XCH", coinType = 8444,
                rpcUrls = listOf("https://c.test"), path = "m/12381/8444/2/0",
                hrp = "xch", decimals = 12,
            ),
        )
        for (config in configs) {
            val rebuilt = ChainDraft.from(config).build()
            assertThat(rebuilt).isEqualTo(config)
        }
    }

    @Test
    fun `编辑时家族不会变——避免「把波场改成 UTXO」这种事故`() {
        val tron = TronChainConfig(
            id = "tron", name = "Tron", symbol = "TRX",
            rpcUrls = listOf("https://api.trongrid.io"), decimals = 6, coinType = 195,
        )
        val draft = ChainDraft.from(tron)
        assertThat(draft.family).isEqualTo(ChainConfig.Family.TRON)
        assertThat(draft.build()).isEqualTo(tron)
        // Tron 没有路径/链 ID 之类的字段，回填后必须是空的，否则会串到别的族
        assertThat(draft.path).isEmpty()
        assertThat(draft.chainId).isEmpty()
    }

    // ------------------------------------------------------------ 能力矩阵

    @Test
    fun `Chia 不支持助记词派生——只能以观察地址导入`() {
        val chia = ChainRegistry.builtin().filterIsInstance<ChiaChainConfig>().first()
        assertThat(chia.supportsKeyDerivation).isFalse()

        // 其余五族都应当支持派生，否则「创建钱包」里会少链
        for (chain in ChainRegistry.builtin()) {
            if (chain !is ChiaChainConfig) {
                assertThat(chain.supportsKeyDerivation).isTrue()
            }
        }
    }

    @Test
    fun `当前只有 EVM UTXO TRON 能发交易`() {
        assertThat(
            EvmChainConfig("a", "A", "A", 1, listOf("https://r")).supportsSending
        ).isTrue()
        assertThat(
            TronChainConfig("t", "T", "T", listOf("https://r")).supportsSending
        ).isTrue()
        assertThat(
            Ed25519ChainConfig(
                "m", "M", "M", 128, listOf("https://r"), Ed25519Variant.MONERO, path = "m/44'/128'/0'",
            ).supportsSending
        ).isFalse()
        assertThat(
            FilecoinChainConfig("f", "F", "F", rpcUrls = listOf("https://r")).supportsSending
        ).isFalse()
        assertThat(
            ChiaChainConfig("c", "C", "C", rpcUrls = listOf("https://r")).supportsSending
        ).isFalse()
    }
}
