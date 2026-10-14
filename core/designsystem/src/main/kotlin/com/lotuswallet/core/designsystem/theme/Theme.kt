package com.lotuswallet.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 应用主题模式。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 统一圆角。
 *
 * 卡片 20dp、控件 12–16dp：偏大的圆角是「现代钱包」观感的关键一环，
 * 配合纯色卡片 + 无阴影，形成干净的面块层次。
 */
val LotusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LocalLotusSemantic = staticCompositionLocalOf { semanticColors(darkTheme = false) }

/** 语义色访问入口：`LotusTheme.semantic.rise`。 */
object LotusTheme {
    val semantic: LotusSemanticColors
        @Composable @ReadOnlyComposable
        get() = LocalLotusSemantic.current

    val colors: ColorScheme
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme
}

/**
 * 应用主题。
 *
 * - **动态取色默认关闭**：壁纸取色会把品牌青替换成随机颜色，所有页面观感不可控，
 *   这是上一版「看起来不统一」的主要原因之一。需要个性化时用户可在设置里手动打开；
 * - 明暗模式由 [themeMode] 决定，默认跟随系统；
 * - 语义色（涨跌、警示、验证）通过 CompositionLocal 下发，避免每个页面自己定义常量。
 */
@Composable
fun LotusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LotusDarkScheme
        else -> LotusLightScheme
    }

    CompositionLocalProvider(LocalLotusSemantic provides semanticColors(darkTheme)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LotusTypography,
            shapes = LotusShapes,
            content = content,
        )
    }
}
