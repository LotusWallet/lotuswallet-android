package com.lotuswallet.core.network.rpc

/**
 * 纯内存的 [RestClient] 假实现。
 *
 * 只覆盖单地址的 [getText]，因此**天然继承了接口里的多端点失败转移语义**
 * —— 测试里只要让前几个地址抛错，就能验证上层的降级路径，不必碰网络。
 *
 * 设计成「按 URL 给响应」而不是「固定响应」，是因为清单类代码几乎全部逻辑
 * 都体现在「第一个地址挂了之后怎么办」上。
 */
class FakeRestClient(
    private val handler: (String) -> String,
) : RestClient {

    /** 按顺序记录被请求过的地址，用于断言失败转移与「有没有真的联网」。 */
    val requestedUrls: MutableList<String> = mutableListOf()

    override suspend fun getText(url: String): String {
        requestedUrls += url
        return handler(url)
    }

    override suspend fun postText(url: String, body: String, mediaType: String): String {
        requestedUrls += url
        return handler(url)
    }
}

/** 便捷构造：所有地址都返回同一份内容。 */
fun FakeRestClient.alwaysReturns(body: String): FakeRestClient = FakeRestClient { body }

/** 便捷构造：[failing] 里的地址抛错，其余返回 [body]。 */
fun fakeWithDeadEndpoints(failing: Set<String>, body: String): FakeRestClient = FakeRestClient { url ->
    if (url in failing) error("端点不可达（测试模拟）：$url") else body
}
