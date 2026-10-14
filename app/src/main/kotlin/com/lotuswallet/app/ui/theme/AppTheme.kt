package com.lotuswallet.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.lotuswallet.core.datastore.AppSettings
import com.lotuswallet.core.designsystem.theme.LotusTheme

/**
 * 应用主题入口。
 *
 * 把「偏好设置」翻译成设计系统需要的参数。`hideBalances` 等**展示策略**
 * 也一并通过 CompositionLocal 下发 —— 这样任何一个显示金额的组件都不需要
 * 自己去注入 ViewModel，减少重复订阅。
 */
@Composable
fun AppTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val themeMode = when (settings.themeMode) {
        com.lotuswallet.core.datastore.ThemeMode.SYSTEM -> com.lotuswallet.core.designsystem.theme.ThemeMode.SYSTEM
        com.lotuswallet.core.datastore.ThemeMode.LIGHT -> com.lotuswallet.core.designsystem.theme.ThemeMode.LIGHT
        com.lotuswallet.core.datastore.ThemeMode.DARK -> com.lotuswallet.core.designsystem.theme.ThemeMode.DARK
    }

    CompositionLocalProvider(
        LocalDisplayPolicy provides DisplayPolicy(
            hideBalances = settings.hideBalances,
            fiatCurrency = settings.fiatCurrency,
            showTestnets = settings.showTestnets,
        ),
    ) {
        LotusTheme(themeMode = themeMode, dynamicColor = settings.dynamicColor, content = content)
    }
}

/**
 * 全局展示策略。刻意做成静态的 CompositionLocal：
 * 它在一次组合内不会频繁变化，用 static 版本可以省掉大量无效重组。
 */
data class DisplayPolicy(
    val hideBalances: Boolean,
    val fiatCurrency: String,
    val showTestnets: Boolean,
)

val LocalDisplayPolicy = staticCompositionLocalOf {
    DisplayPolicy(hideBalances = false, fiatCurrency = "CNY", showTestnets = false)
}

/** 便捷读取。 */
object AppDisplay {
    val policy: DisplayPolicy
        @Composable get() = LocalDisplayPolicy.current
}
