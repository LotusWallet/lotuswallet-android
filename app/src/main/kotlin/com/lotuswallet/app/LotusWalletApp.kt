package com.lotuswallet.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.lotuswallet.core.datastore.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 应用入口。
 *
 * 没有 SDK、没有 SDK 初始化、没有崩溃上报、没有推送 —— 启动路径上的副作用只有两个：
 * Hilt 依赖图构建，以及把用户选的语言应用到运行时。
 *
 * ## 语言为什么要在这里应用
 * 应用内语言切换（Android 13 以下必须靠 `AppCompatDelegate.setApplicationLocales`）
 * 的覆盖值**只存在于当前进程的静态字段里**，进程被杀就丢了。
 * 所以采用「DataStore 为持久真相、AppCompatDelegate 为运行时载体」的双层结构：
 * - 冷启动时在这里把 DataStore 里的值重新灌回去；
 * - 运行期间监听变化，`AppCompatDelegate` 会自动重建前台 Activity，
 *   用户能立刻看到界面换语言，无需手动重启应用。
 */
@HiltAndroidApp
class LotusWalletApp : Application() {

    @Inject lateinit var settings: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val saved = readLanguageBlocking()
        applyLanguage(saved)

        appScope.launch {
            settings.settings
                .map { it.language }
                .distinctUntilChanged()
                .collectLatest { applyLanguage(it) }
        }
    }

    /**
     * 首帧之前同步读一次语言。
     *
     * 若改成异步，第一帧会先按系统语言 inflate，再切换成用户语言，造成明显闪烁；
     * 这里宁可阻塞主线程几毫秒读一个小 Preferences 文件，也不接受首帧语言跳动。
     * 加超时兜底：万一 DataStore 异常，宁可回到系统语言也不能卡死启动。
     */
    private fun readLanguageBlocking(): String? = runBlocking {
        withTimeoutOrNull(timeMillis = 500L) { settings.current().language }
    }

    /** null = 跟随系统（清空覆盖值）；"zh-CN" / "en" = 显式语言。 */
    private fun applyLanguage(tag: String?) {
        val locales = if (tag.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
