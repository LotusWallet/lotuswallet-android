package com.lotuswallet.core.network.chain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChainConfigTest {

    @Test
    fun `内置清单可解析且覆盖全部已实现的链族`() {
        val chains = ChainRegistry.builtin()
        assertThat(chains).isNotEmpty()

        val families = chains.map { it.family }.toSet()
        assertThat(families).containsAtLeast(
            ChainConfig.Family.EVM,
            ChainConfig.Family.UTXO,
            ChainConfig.Family.TRON,
            ChainConfig.Family.ED25519,
            ChainConfig.Family.FILECOIN,
            ChainConfig.Family.CHIA,
        )

        val ids = chains.map { it.id }
        assertThat(ids).containsAtLeast(
            "ethereum", "bsc", "bitcoin", "litecoin", "dash",
            "solana", "icp", "monero", "filecoin", "chia", "tron",
        )
    }

    @Test
    fun `每条链都必须有 id 名称 符号 精度 与至少一个端点`() {
        for (chain in ChainRegistry.builtin()) {
            assertThat(chain.id).isNotEmpty()
            assertThat(chain.name).isNotEmpty()
            assertThat(chain.symbol).isNotEmpty()
            assertThat(chain.decimals).isAtLeast(0)
            // Chia 没有公认稳定的公共节点，清单里留空是刻意的；
            // 其余链若没有端点，用户会看到「链在、余额永远是—」的坏体验。
            if (chain !is ChiaChainConfig) {
                assertThat(chain.rpcUrls).isNotEmpty()
            }
        }
        // id 必须唯一，否则数据库外键会串链
        val ids = ChainRegistry.builtin().map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `所有 EVM 链共用 coinType 60 因而地址可复用`() {
        val evmChains = ChainRegistry.builtin().filterIsInstance<EvmChainConfig>()
        assertThat(evmChains).isNotEmpty()
        for (chain in evmChains) {
            // 这是「加链零代码」的关键前提：派生路径第二个参数固定为 60。
            assertThat(chain.coinType).isEqualTo(60)
        }
        // chainId 不能重复
        assertThat(evmChains.map { it.chainId }).containsNoDuplicates()
    }

    @Test
    fun `比特币默认使用原生隔离见证 hrp=bc`() {
        val btc = ChainRegistry.builtin().filterIsInstance<UtxoChainConfig>().first { it.id == "bitcoin" }
        assertThat(btc.addressType).isEqualTo(UtxoAddressType.NATIVE_SEGWIT)
        assertThat(btc.hrp).isEqualTo("bc")
        assertThat(btc.coinType).isEqualTo(0)
        assertThat(btc.decimals).isEqualTo(8)
        assertThat(btc.addressType.purpose).isEqualTo(84)
    }

    @Test
    fun `新增 EVM 链只需一段 JSON`() {
        val custom = """
            {
              "version": 1,
              "chains": [
                {
                  "family": "evm",
                  "id": "custom-chain",
                  "name": "Custom Chain",
                  "symbol": "CUS",
                  "chainId": 99999,
                  "rpcUrls": ["https://rpc.custom.example"]
                }
              ]
            }
        """.trimIndent()

        val parsed = ChainRegistry.parse(custom)
        assertThat(parsed).hasSize(1)
        val chain = parsed.single()
        assertThat(chain).isInstanceOf(EvmChainConfig::class.java)
        val evm = chain as EvmChainConfig
        assertThat(evm.chainId).isEqualTo(99999L)
        // 未写的字段全部走默认值 —— 这就是「零代码加链」的含义
        assertThat(evm.coinType).isEqualTo(60)
        assertThat(evm.decimals).isEqualTo(18)
        assertThat(evm.enabled).isTrue()
        assertThat(evm.supportsEip1559).isTrue()
    }

    @Test
    fun `远端清单可覆盖同 id 配置并新增链`() {
        val remote = """
            {
              "version": 1,
              "chains": [
                {
                  "family": "evm",
                  "id": "ethereum",
                  "name": "Ethereum (自定义 RPC)",
                  "symbol": "ETH",
                  "chainId": 1,
                  "rpcUrls": ["https://my-own-node.example"]
                },
                {
                  "family": "evm",
                  "id": "new-chain",
                  "name": "New Chain",
                  "symbol": "NEW",
                  "chainId": 12345,
                  "rpcUrls": ["https://rpc.new.example"]
                }
              ]
            }
        """.trimIndent()

        val merged = ChainRegistry.merge(remote)
        val eth = merged.first { it.id == "ethereum" }
        assertThat(eth.name).isEqualTo("Ethereum (自定义 RPC)")
        assertThat(eth.rpcUrls).containsExactly("https://my-own-node.example")
        // 未被覆盖的链仍在
        assertThat(merged.map { it.id }).contains("bitcoin")
        assertThat(merged.map { it.id }).contains("new-chain")
    }

    @Test
    fun `远端清单非法时静默降级为内置`() {
        val merged = ChainRegistry.merge("{ 这不是合法 JSON")
        assertThat(merged.map { it.id }).containsAtLeast("ethereum", "bitcoin", "tron")
    }

    @Test
    fun `浏览器链接拼接正确`() {
        val eth = ChainRegistry.builtin().filterIsInstance<EvmChainConfig>().first { it.id == "ethereum" }
        assertThat(eth.txUrl("0xabc")).isEqualTo("https://etherscan.io/tx/0xabc")
        assertThat(eth.addressUrl("0xabc")).isEqualTo("https://etherscan.io/address/0xabc")
    }

    // ------------------------------------------------------------ 用户自定义链

    @Test
    fun `用户自定义链优先级最高_可覆盖内置也能新增`() {
        val user = """
            {
              "version": 1,
              "modified": [
                {
                  "family": "evm",
                  "id": "ethereum",
                  "name": "Ethereum（我的节点）",
                  "symbol": "ETH",
                  "chainId": 1,
                  "rpcUrls": ["https://my-node.example", "https://backup.example"]
                },
                {
                  "family": "evm",
                  "id": "my-rollup",
                  "name": "My Rollup",
                  "symbol": "MYR",
                  "chainId": 777001,
                  "rpcUrls": ["https://rpc.my-rollup.example"]
                }
              ]
            }
        """.trimIndent()

        val merged = ChainRegistry.merge(remote = null, user = user)

        val eth = merged.first { it.id == "ethereum" }
        assertThat(eth.name).isEqualTo("Ethereum（我的节点）")
        assertThat(eth.rpcUrls).containsExactly("https://my-node.example", "https://backup.example")
        assertThat(merged.map { it.id }).contains("my-rollup")
        assertThat(merged.map { it.id }).contains("bitcoin")
        assertThat(merged.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `用户覆盖优先于远端清单`() {
        val remote = """
            { "version": 1, "chains": [
              { "family": "evm", "id": "ethereum", "name": "Remote ETH", "symbol": "ETH",
                "chainId": 1, "rpcUrls": ["https://remote.example"] } ] }
        """.trimIndent()
        val user = """
            { "version": 1, "modified": [
              { "family": "evm", "id": "ethereum", "name": "Mine", "symbol": "ETH",
                "chainId": 1, "rpcUrls": ["https://mine.example"] } ] }
        """.trimIndent()

        val merged = ChainRegistry.merge(remote, user)
        assertThat(merged.first { it.id == "ethereum" }.name).isEqualTo("Mine")
    }

    @Test
    fun `用户覆盖损坏时静默降级_不影响其余清单`() {
        val merged = ChainRegistry.merge(remote = null, user = "{ 半截 JSON")
        assertThat(merged.map { it.id }).containsAtLeast("ethereum", "bitcoin", "tron")
    }

    @Test
    fun `用户覆盖可把内置链停用_但链本身仍在清单里`() {
        val user = """
            { "version": 1, "modified": [
              { "family": "utxo", "id": "litecoin", "name": "Litecoin", "symbol": "LTC",
                "coinType": 2, "rpcUrls": ["https://litecoinspace.org/api"], "enabled": false } ] }
        """.trimIndent()

        val merged = ChainRegistry.merge(remote = null, user = user)
        val ltc = merged.first { it.id == "litecoin" }
        assertThat(ltc.enabled).isFalse()
        // 「隐藏」不是「删除」：链还在，用户可以再打开
        assertThat(merged.map { it.id }).contains("litecoin")
    }

    @Test
    fun `用户覆盖序列化后可原样解析回来`() {
        val overrides = UserChainOverrides(
            modified = listOf(
                UtxoChainConfig(
                    id = "litecoin",
                    name = "Litecoin",
                    symbol = "LTC",
                    coinType = 2,
                    rpcUrls = listOf("https://litecoinspace.org/api"),
                ),
            ),
        )
        val encoded = ChainRegistry.encodeUserOverrides(overrides)
        val decoded = ChainRegistry.parseUserOverrides(encoded)
        assertThat(decoded.modified.map { it.id }).containsExactly("litecoin")
        assertThat(decoded.modified.single()).isInstanceOf(UtxoChainConfig::class.java)
    }
}
