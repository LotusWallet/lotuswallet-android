package com.lotuswallet.app.di

import com.lotuswallet.chain.bitcoin.EsploraApi
import com.lotuswallet.chain.evm.EvmRpc
import com.lotuswallet.chain.tron.TronRpc
import com.lotuswallet.core.network.rpc.JsonRpcClient
import com.lotuswallet.core.network.rpc.RestClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * 三条链族的 RPC 客户端。
 *
 * 全部是无状态、无鉴权、可复用的只读客户端，因此做成单例。
 * 注意这里**没有**任何「链服务」映射表 —— 具体某个 chainId 走哪个客户端，
 * 由 [com.lotuswallet.core.network.chain.ChainConfig.family] 在调用处 `when` 分发，
 * 新增 EVM 链不需要动这里。
 */
@Module
@InstallIn(SingletonComponent::class)
object ChainModule {

    @Provides
    @Singleton
    fun provideJsonRpcClient(rest: RestClient, json: Json): JsonRpcClient = JsonRpcClient(rest, json)

    @Provides
    @Singleton
    fun provideEvmRpc(client: JsonRpcClient): EvmRpc = EvmRpc(client)

    @Provides
    @Singleton
    fun provideEsploraApi(rest: RestClient): EsploraApi = EsploraApi(rest)

    @Provides
    @Singleton
    fun provideTronRpc(rest: RestClient): TronRpc = TronRpc(rest)
}
