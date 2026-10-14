package com.lotuswallet.core.network.market

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.network.rpc.FakeRestClient
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [MarketRepository] 的远端 / 缓存 / 兜底语义。
 *
 * 市场清单的取舍是「**永远有内容可看**」：远端挂了、内容坏了、第一次装还没缓存，
 * 都要退回到内置精选清单，而不是给用户一个空白页。
 */
class MarketRepositoryTest {

    private val urls = listOf("https://raw.example/dapps.json")

    private val remoteManifest = """
        {
          "version": 2,
          "updatedAt": "2026-10-13T00:00:00Z",
          "source": "curated",
          "categories": [{"id": "dex", "name": "交易 / DEX", "order": 0}],
          "dapps": [
            {"id": "uniswap", "name": "Uniswap", "url": "https://app.uniswap.org",
             "category": "dex", "description": "去中心化交易", "chains": ["ethereum"],
             "tags": ["swap"], "order": 2},
            {"id": "curve", "name": "Curve", "url": "https://curve.fi",
             "category": "dex", "description": "稳定币兑换", "chains": ["ethereum"],
             "tags": ["swap", "stable"], "order": 1}
          ]
        }
    """.trimIndent()

    private fun repo(rest: FakeRestClient) = MarketRepository(rest) { urls }

    @Test
    fun `拉取成功后 current 反映远端清单`() = runTest {
        val repository = repo(FakeRestClient { remoteManifest })
        val result = repository.refresh()

        assertThat(result).isNotNull()
        assertThat(repository.current().version).isEqualTo(2)
        assertThat(repository.current().dapps.map { it.id }).containsExactly("curve", "uniswap")
    }

    @Test
    fun `远端带未知字段 source 也能解析_不能整份失败`() = runTest {
        // source 是构建脚本写入的元信息，客户端模型里没有这个字段
        val repository = repo(FakeRestClient { remoteManifest })
        assertThat(repository.refresh()?.dapps).hasSize(2)
    }

    @Test
    fun `远端不可达时退回持久化清单`() = runTest {
        val repository = repo(FakeRestClient { error("不可达（测试模拟）") })
        val result = repository.refresh(persisted = remoteManifest)

        assertThat(result?.version).isEqualTo(2)
        assertThat(repository.current().dapps.map { it.id }).containsExactly("curve", "uniswap")
    }

    @Test
    fun `远端内容损坏时丢弃而不是让 current 变空`() = runTest {
        var body = remoteManifest
        val repository = repo(FakeRestClient { body })
        repository.refresh()          // 先有一份好数据

        body = "{ 坏 JSON"
        val result = repository.refresh()

        assertThat(result).isNull()
        // 关键：坏内容不能把已经好用的缓存冲掉
        assertThat(repository.current().dapps.map { it.id }).containsExactly("curve", "uniswap")
    }

    @Test
    fun `远端与持久化都没有时 current 返回内置精选清单`() = runTest {
        val repository = repo(FakeRestClient { error("不可达（测试模拟）") })

        assertThat(repository.refresh()).isNull()
        assertThat(repository.current().dapps).isNotEmpty()
        assertThat(repository.current().dapps.map { it.id }).contains("uniswap")
    }

    @Test
    fun `没有配置远端地址时 refresh 返回 null 且不联网`() = runTest {
        val rest = FakeRestClient { remoteManifest }
        val repository = MarketRepository(rest) { emptyList() }

        assertThat(repository.refresh()).isNull()
        assertThat(rest.requestedUrls).isEmpty()
    }

    @Test
    fun `initialize 从持久化原文恢复缓存`() {
        val repository = repo(FakeRestClient { "" })
        repository.initialize(remoteManifest)

        assertThat(repository.current().version).isEqualTo(2)
    }

    @Test
    fun `initialize 收到空值或坏内容时保持内置清单`() {
        val repository = repo(FakeRestClient { "" })
        repository.initialize(null)
        assertThat(repository.current().version).isEqualTo(0)

        repository.initialize("不是 JSON")
        assertThat(repository.current().version).isEqualTo(0)
    }

    @Test
    fun `搜索命中名称 描述 与标签_且大小写不敏感`() = runTest {
        val repository = repo(FakeRestClient { remoteManifest })
        repository.refresh()

        assertThat(repository.search("UNISWAP").map { it.id }).containsExactly("uniswap")
        assertThat(repository.search("稳定币").map { it.id }).containsExactly("curve")
        assertThat(repository.search("stable").map { it.id }).containsExactly("curve")
        // 空查询 = 不过滤
        assertThat(repository.search("  ")).hasSize(2)
        // 命中不存在的词返回空
        assertThat(repository.search("zzz")).isEmpty()
    }

    @Test
    fun `按分类取用并按 order 升序`() = runTest {
        val repository = repo(FakeRestClient { remoteManifest })
        repository.refresh()

        assertThat(repository.byCategory("dex").map { it.id }).containsExactly("curve", "uniswap").inOrder()
        assertThat(repository.byCategory("lending")).isEmpty()
    }
}
