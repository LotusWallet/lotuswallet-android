package com.lotuswallet.app.di

import android.content.Context
import androidx.room.Room
import com.lotuswallet.app.BuildConfig
import com.lotuswallet.core.common.DefaultDispatcher
import com.lotuswallet.core.common.DefaultDispatcherProvider
import com.lotuswallet.core.common.DispatcherProvider
import com.lotuswallet.core.common.IoDispatcher
import com.lotuswallet.core.common.MainDispatcher
import com.lotuswallet.core.database.LotusDatabase
import com.lotuswallet.core.database.Migrations
import com.lotuswallet.core.database.dao.AccountDao
import com.lotuswallet.core.database.dao.AddressBookDao
import com.lotuswallet.core.database.dao.OtpDao
import com.lotuswallet.core.database.dao.TokenDao
import com.lotuswallet.core.database.dao.VaultDao
import com.lotuswallet.core.database.dao.WalletDao
import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.network.market.MarketRepository
import com.lotuswallet.core.network.rpc.HttpTransport
import com.lotuswallet.core.network.rpc.OkHttpRestClient
import com.lotuswallet.core.network.rpc.RestClient
import com.lotuswallet.core.network.update.UpdateRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * 全局依赖图。
 *
 * 这里没有网络客户端的鉴权拦截器、没有 token 刷新、没有埋点 ——
 * 因为本产品不存在账号体系，对外只有「只读公共节点」这一类请求。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ---------------------------------------------------------------- 基础

    @Provides
    @Singleton
    fun provideDispatchers(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @IoDispatcher
    fun provideIo(provider: DispatcherProvider): CoroutineDispatcher = provider.io

    @Provides
    @DefaultDispatcher
    fun provideDefault(provider: DispatcherProvider): CoroutineDispatcher = provider.default

    @Provides
    @MainDispatcher
    fun provideMain(provider: DispatcherProvider): CoroutineDispatcher = provider.main

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ---------------------------------------------------------------- 网络

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = HttpTransport.defaultClient(debugLogging = BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun provideRestClient(http: OkHttpClient): RestClient = OkHttpRestClient(http)

    @Provides
    @Singleton
    fun provideChainRepository(rest: RestClient): ChainRepository = ChainRepository(rest)

    @Provides
    @Singleton
    fun provideMarketRepository(rest: RestClient): MarketRepository = MarketRepository(rest)

    @Provides
    @Singleton
    fun provideUpdateRepository(rest: RestClient): UpdateRepository = UpdateRepository(rest)

    // ---------------------------------------------------------------- 本地存储

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LotusDatabase =
        Room.databaseBuilder(context, LotusDatabase::class.java, LotusDatabase.NAME)
            // 刻意不提供 fallbackToDestructiveMigration：
            // 钱包与保险库的数据一旦被"顺手清掉"就是不可逆的事故，
            // 宁可启动时明确崩溃，也不要静默丢数据。
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides
    fun provideWalletDao(db: LotusDatabase): WalletDao = db.walletDao()

    @Provides
    fun provideAccountDao(db: LotusDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideVaultDao(db: LotusDatabase): VaultDao = db.vaultDao()

    @Provides
    fun provideOtpDao(db: LotusDatabase): OtpDao = db.otpDao()

    @Provides
    fun provideTokenDao(db: LotusDatabase): TokenDao = db.tokenDao()

    @Provides
    fun provideAddressBookDao(db: LotusDatabase): AddressBookDao = db.addressBookDao()

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)
}
