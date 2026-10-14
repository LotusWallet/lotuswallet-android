package com.lotuswallet.core.network.chain

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.network.rpc.FakeRestClient
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [ChainRepository] 的刷新 / 降级语义。
 *
 * 这里最要紧的一条是「force 时必须先联网」：早期实现把持久化清单放在最前面，
 * 结果第一次同步成功之后就永远不再联网，远端加新链也拉不到，
 * 「加链不用发版」这条核心设计直接失效。本测试把它钉死，防止回归。
 */
class ChainRepositoryTest {

    private val urls = listOf("https://raw.example/chains.json")

    private val remoteWithNewChain = """
        {
          "version": 1,
          "chains": [
            {"family": "evm", "id": "brand-new", "name": "Brand New", "symbol": "BNW",
             "chainId": 77777, "rpcUrls": ["https://rpc.brand-new.example"]}
          ]
        }
    """.trimIndent()

    private val remoteOverridingEthereum = """
        {
          "version": 1,
          "chains": [
            {"family": "evm", "id": "ethereum", "name": "ETH (自建节点)", "symbol": "ETH",
             "chainId": 1, "rpcUrls": ["https://my-node.example"]}
          ]
        }
    """.trimIndent()

    private fun repo(rest: FakeRestClient) = ChainRepository(rest) { urls }

    @Test
    fun `force 刷新时必须联网_不能被持久化清单短路`() = runTest {
        val rest = FakeRestClient { remoteWithNewChain }
        val repository = repo(rest)

        // 先假装上次拉到过一份「什么都不加」的清单
        val first = repository.refresh(force = true)
        assertThat(first.map { it.id }).contains("brand-new")

        // 关键：即使传入了持久化清单，force 也必须再联网一次
        rest.requestedUrls.clear()
        val second = repository.refresh(force = true, persistedRemote = """{"version":1,"chains":[]}""")

        assertThat(rest.requestedUrls).isNotEmpty()
        assertThat(second.map { it.id }).contains("brand-new")
    }

    @Test
    fun `非 force 刷新优先用持久化清单_不联网`() = runTest {
        val rest = FakeRestClient { remoteWithNewChain }
        val repository = repo(rest)

        val result = repository.refresh(force = false, persistedRemote = remoteOverridingEthereum)

        assertThat(rest.requestedUrls).isEmpty()
        val eth = result.first { it.id == "ethereum" }
        assertThat(eth.name).isEqualTo("ETH (自建节点)")
        // 持久化清单里没有的链，仍由内置清单兜住
        assertThat(result.map { it.id }).containsAtLeast("bitcoin", "tron")
    }

    @Test
    fun `远端不可达时退回持久化清单`() = runTest {
        val rest = FakeRestClient { error("不可达（测试模拟）") }

        val result = repo(rest).refresh(force = true, persistedRemote = remoteOverridingEthereum)

        assertThat(result.first { it.id == "ethereum" }.name).isEqualTo("ETH (自建节点)")
        assertThat(result.map { it.id }).contains("bitcoin")
    }

    @Test
    fun `远端与持久化都不可用时静默降级为内置清单`() = runTest {
        val rest = FakeRestClient { error("不可达（测试模拟）") }

        val result = repo(rest).refresh(force = true, persistedRemote = "{ 坏 JSON")

        assertThat(result.map { it.id }).containsAtLeast("ethereum", "bitcoin", "litecoin", "tron")
        assertThat(result).isNotEmpty()
    }

    @Test
    fun `远端清单可新增链并覆盖同 id 的内置链`() = runTest {
        val added = repo(FakeRestClient { remoteWithNewChain }).refresh(force = true)
        assertThat(added.map { it.id }).contains("brand-new")
        assertThat(added.map { it.id }).contains("bitcoin")   // 内置的没被挤掉
        assertThat(added.map { it.id }).containsNoDuplicates()

        val overridden = repo(FakeRestClient { remoteOverridingEthereum }).refresh(force = true)
        val eth = overridden.first { it.id == "ethereum" }
        assertThat(eth.rpcUrls).containsExactly("https://my-node.example")
        assertThat(overridden.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `成功拉取的原文会被留存供上层持久化`() = runTest {
        val repository = repo(FakeRestClient { remoteWithNewChain })
        repository.refresh(force = true)
        assertThat(repository.cachedRemoteJson).isEqualTo(remoteWithNewChain)
    }

    @Test
    fun `拉取失败时不会污染已留存的原文`() = runTest {
        var broken = false
        val repository = repo(FakeRestClient { if (broken) error("不可达（测试模拟）") else remoteWithNewChain })

        repository.refresh(force = true)
        val good = repository.cachedRemoteJson

        // 网络挂掉之后再刷新一次：留存的原文必须还是上一次成功的那份，不能被清空
        broken = true
        repository.refresh(force = true)
        assertThat(repository.cachedRemoteJson).isEqualTo(good)
    }

    @Test
    fun `已加载后的普通刷新会命中内存缓存`() = runTest {
        val rest = FakeRestClient { remoteWithNewChain }
        val repository = repo(rest)
        repository.refresh(force = true)
        rest.requestedUrls.clear()

        repository.refresh(force = false)

        assertThat(rest.requestedUrls).isEmpty()
    }

    @Test
    fun `按 id 与 chainId 查找_并且只返回启用的链`() = runTest {
        val repository = repo(FakeRestClient { remoteWithNewChain })
        repository.refresh(force = true)

        assertThat(repository.findById("brand-new")).isNotNull()
        assertThat(repository.findEvm(77777)?.id).isEqualTo("brand-new")
        assertThat(repository.findEvm(424242)).isNull()
        assertThat(repository.enabled).isNotEmpty()
        assertThat(repository.enabled.all { it.enabled }).isTrue()
    }
}
