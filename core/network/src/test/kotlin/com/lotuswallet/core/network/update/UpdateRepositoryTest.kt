package com.lotuswallet.core.network.update

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.network.rpc.FakeRestClient
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 「检查更新」这条链路的回归测试。
 *
 * 为什么值得单独测：这是全应用**唯一**会改变用户手上 Apk 版本的决策逻辑，
 * 而且它读的是远端文件 —— 出错的表现是「永远提示已是最新」或「反复提示更新」，
 * 在真机上几乎不可能复现。这里把每个分支钉死。
 */
class UpdateRepositoryTest {

    private val urls = listOf("https://raw.example/versions.json", "https://cdn.example/versions.json")

    private fun repo(json: String) = UpdateRepository(FakeRestClient { json }) { urls }

    private fun manifest(
        latestCode: Int?,
        latestName: String = "9.9.9",
        minSupported: Int = 0,
        mandatory: Boolean = false,
        releases: List<Int> = listOf(latestCode ?: 0),
        extra: String = "",
    ): String {
        val latestJson = latestCode?.let {
            """
            "latest": {"versionName": "$latestName", "versionCode": $it, "mandatory": $mandatory,
                       "sizeBytes": 123, "sha256": "abc",
                       "downloadUrl": "https://dl.example/v$it", "latestUrl": "https://dl.example/latest"}
            """.trimIndent()
        } ?: "\"latest\": null"

        val releasesJson = releases.joinToString(",") { c ->
            """{"versionName": "1.0.$c", "versionCode": $c}"""
        }

        return """
            {
              "schemaVersion": 1,
              "channel": "stable",
              "minSupportedVersionCode": $minSupported,
              $latestJson,
              "releases": [$releasesJson],
              "legacy": []
              $extra
            }
        """.trimIndent()
    }

    @Test
    fun `远端更高版本时提示可更新且非强制`() = runTest {
        val result = repo(manifest(latestCode = 30002, latestName = "3.0.2")).check(currentVersionCode = 30001)

        assertThat(result).isInstanceOf(UpdateCheckResult.Available::class.java)
        val available = result as UpdateCheckResult.Available
        assertThat(available.release.versionName).isEqualTo("3.0.2")
        assertThat(available.release.versionCode).isEqualTo(30002)
        assertThat(available.required).isFalse()
    }

    @Test
    fun `同版本或更低版本一律视为已是最新`() = runTest {
        assertThat(repo(manifest(latestCode = 30001)).check(30001)).isEqualTo(UpdateCheckResult.UpToDate)
        // 用户装的是「更新」的包（例如从别处拿到的新版），不能反过来提示降级
        assertThat(repo(manifest(latestCode = 30000)).check(30001)).isEqualTo(UpdateCheckResult.UpToDate)
    }

    @Test
    fun `latest 缺失时回退到 releases 里 versionCode 最大的那个`() = runTest {
        val json = manifest(latestCode = null, releases = listOf(30001, 30005, 30003))

        val result = repo(json).check(currentVersionCode = 30001)
        assertThat(result).isInstanceOf(UpdateCheckResult.Available::class.java)
        assertThat((result as UpdateCheckResult.Available).release.versionCode).isEqualTo(30005)
    }

    @Test
    fun `latest 与 releases 都为空时视为已是最新而不是失败`() = runTest {
        val json = manifest(latestCode = null, releases = emptyList())
        assertThat(repo(json).check(30001)).isEqualTo(UpdateCheckResult.UpToDate)
    }

    @Test
    fun `mandatory 标记会升级为强制更新`() = runTest {
        val result = repo(manifest(latestCode = 30002, mandatory = true)).check(30001)
        assertThat((result as UpdateCheckResult.Available).required).isTrue()
    }

    @Test
    fun `当前版本低于支持下限时强制更新_判据是当前版本而不是最新版号`() = runTest {
        // 下限 30003，最新 30005
        // 用户 30002（低于下限）→ 强制
        val below = repo(manifest(latestCode = 30005, minSupported = 30003)).check(30002)
        assertThat((below as UpdateCheckResult.Available).required).isTrue()

        // 用户恰好等于下限 → 文档说的是「低于」才算不受支持，等于仍然受支持
        val onFloor = repo(manifest(latestCode = 30005, minSupported = 30003)).check(30003)
        assertThat((onFloor as UpdateCheckResult.Available).required).isFalse()

        // 回归锁：最新版号与下限的关系绝不能影响判定。
        // 历史实现把 latest.versionCode 当成了比较对象，于是「最新版高于下限」
        // 会让本该强推的老客户端反而被放过 —— 下面这条就是钉住那个反转。
        val hugeLatest = repo(manifest(latestCode = 99999, minSupported = 30003)).check(30002)
        assertThat((hugeLatest as UpdateCheckResult.Available).required).isTrue()
    }

    @Test
    fun `下限为 0 时任何人都不会被强制更新`() = runTest {
        // 0 是清单的默认值（首次发布），必须等于「没有下限」
        val result = repo(manifest(latestCode = 30002, minSupported = 0)).check(30001)
        assertThat((result as UpdateCheckResult.Available).required).isFalse()
    }

    @Test
    fun `远端将来新增字段不能把老客户端搞崩`() = runTest {
        val json = manifest(latestCode = 30002, extra = ""","futureField": {"a": [1,2,3]}, "another": null""")
        val result = repo(json).check(30001)
        assertThat(result).isInstanceOf(UpdateCheckResult.Available::class.java)
    }

    @Test
    fun `JSON 非法时返回 Failed 而不是抛异常`() = runTest {
        val result = repo("{ 这不是合法 JSON").check(30001)
        assertThat(result).isInstanceOf(UpdateCheckResult.Failed::class.java)
    }

    @Test
    fun `所有端点都不可达时返回 Failed`() = runTest {
        val rest = FakeRestClient { error("网络不可达（测试模拟）") }
        val result = UpdateRepository(rest) { urls }.check(30001)

        assertThat(result).isInstanceOf(UpdateCheckResult.Failed::class.java)
        // 两个等价地址都要试过，才谈得上「失败」
        assertThat(rest.requestedUrls).containsExactlyElementsIn(urls)
    }

    @Test
    fun `第一个端点挂掉时会自动换到第二个`() = runTest {
        val rest = FakeRestClient { url ->
            if (url == urls[0]) error("raw.githubusercontent.com 超时（测试模拟）")
            else manifest(latestCode = 30002)
        }

        val result = UpdateRepository(rest) { urls }.check(30001)
        assertThat(result).isInstanceOf(UpdateCheckResult.Available::class.java)
        assertThat(rest.requestedUrls).containsExactly(urls[0], urls[1]).inOrder()
    }

    @Test
    fun `没有配置任何远端地址时视为已是最新而不是失败`() = runTest {
        assertThat(UpdateRepository(FakeRestClient { "" }) { emptyList() }.check(30001))
            .isEqualTo(UpdateCheckResult.UpToDate)
    }

    @Test
    fun `下载入口按 固定直链 版本直链 Release页 依次回退`() {
        fun release(latest: String? = null, download: String? = null, page: String? = null) =
            AppRelease(
                versionName = "1.0.0", versionCode = 1,
                latestUrl = latest, downloadUrl = download, releasePageUrl = page,
            )

        assertThat(release("L", "D", "P").bestDownloadUrl).isEqualTo("L")
        assertThat(release(null, "D", "P").bestDownloadUrl).isEqualTo("D")
        assertThat(release(null, null, "P").bestDownloadUrl).isEqualTo("P")
    }

    @Test
    fun `更新说明按语言回退_中文缺失时退回英文`() {
        val notes = ReleaseNotes(chinese = emptyList(), english = listOf("Fixed crash"))
        assertThat(notes.forLanguage(isChinese = true)).containsExactly("Fixed crash")

        val both = ReleaseNotes(chinese = listOf("修复崩溃"), english = listOf("Fixed crash"))
        assertThat(both.forLanguage(isChinese = true)).containsExactly("修复崩溃")
        assertThat(both.forLanguage(isChinese = false)).containsExactly("Fixed crash")
    }

    @Test
    fun `更新说明能正确反序列化 zh 与 en 两个键`() = runTest {
        val json = manifest(
            latestCode = 30002,
            extra = ""","releasesExtra": 0""",
        ).replace(
            "\"sizeBytes\": 123",
            "\"sizeBytes\": 123, \"releaseNotes\": {\"zh\": [\"修复崩溃\"], \"en\": [\"Fixed crash\"]}",
        )

        val result = repo(json).check(30001) as UpdateCheckResult.Available
        assertThat(result.release.releaseNotes?.chinese).containsExactly("修复崩溃")
        assertThat(result.release.releaseNotes?.english).containsExactly("Fixed crash")
    }
}
