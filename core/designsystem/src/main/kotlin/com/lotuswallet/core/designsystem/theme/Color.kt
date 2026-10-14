package com.lotuswallet.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌色板（v2，对齐主流钱包的视觉语言）。
 *
 * ## 这版和上一版的根本区别
 * 上一版把「品牌绿松石」晕染到了**每一个表面**：背景、卡片、分割线全是带绿的灰。
 * 结果是卡片和背景几乎同色、层次靠猜，整体发闷 —— 这正是「看起来土」的来源。
 *
 * 这一版改成主流钱包（OKX / Bitget / Binance）的做法：
 * - **表面一律中性色**（纯白卡片 / 近黑深色卡片），不掺品牌色；
 * - **品牌色只出现在少量高价值位置**：主按钮、选中态、链接、图标、进度条；
 * - 强调色取自品牌 LOGO 的主蓝（旧版 icon_logo.png 左笔画渐变的中间调）。
 */

// --------------------------------------------------------------- 品牌强调色
/** 品牌蓝。来源：旧版 LOGO 左笔画的垂直渐变（#598AF3 → #204FEA）的中间调。 */
val LotusBlue = Color(0xFF2C5BEE)
/** 品牌蓝的深一档（深色模式主按钮 / inversePrimary）。 */
val LotusBlueDeep = Color(0xFF204FEA)
/** 品牌蓝的浅一档（浅色容器）。 */
val LotusBlueMist = Color(0xFFE1E9FE)
/** 品牌青（LOGO 中的圆）。仅用于「已验证」语义，不参与主强调。 */
val LotusCyan = Color(0xFF39D1FF)

// --------------------------------------------------------------- 中性色
private object Neutral {
    /** 主文本（近黑，OKX 同款）。 */
    const val ink = 0xFF0B0E11
    /** 次级文本。 */
    const val inkSoft = 0xFF697077
    /** 弱化文本 / 占位符。 */
    const val inkFaint = 0xFF9BA1A8

    /** 页面背景（浅）。 */
    const val canvasLight = 0xFFF2F4F5
    /** 卡片（浅）：纯白，靠色差而不是阴影与背景分层。 */
    const val cardLight = 0xFFFFFFFF
    /** 分割线（浅）。 */
    const val hairlineLight = 0xFFE7EAEC

    /** 页面背景（深）。 */
    const val canvasDark = 0xFF0A0C0D
    /** 卡片（深）。 */
    const val cardDark = 0xFF141719
    /** 分割线（深）。 */
    const val hairlineDark = 0xFF212426
}

// --------------------------------------------------------------- 明色方案
val LotusLightScheme = lightColorScheme(
    primary = LotusBlue,
    onPrimary = Color.White,
    primaryContainer = LotusBlueMist,
    onPrimaryContainer = Color(0xFF0A2470),

    // 次级色刻意中性化：钱包里大量「次要按钮 / 分隔容器」不该带色相
    secondary = Color(0xFF4A5257),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E7EA),
    onSecondaryContainer = Color(0xFF1A1F23),

    tertiary = Color(0xFF5B5BD6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4E4FF),
    onTertiaryContainer = Color(0xFF1A1A6B),

    background = Color(Neutral.canvasLight),
    onBackground = Color(Neutral.ink),
    surface = Color(Neutral.canvasLight),
    onSurface = Color(Neutral.ink),
    surfaceVariant = Color(Neutral.hairlineLight),
    onSurfaceVariant = Color(Neutral.inkSoft),
    surfaceContainerLowest = Color(Neutral.cardLight),
    surfaceContainerLow = Color(Neutral.cardLight),
    surfaceContainer = Color(0xFFF7F8FA),
    surfaceContainerHigh = Color(0xFFF0F2F4),
    surfaceContainerHighest = Color(Neutral.hairlineLight),

    outline = Color(0xFFB7BEC6),
    outlineVariant = Color(Neutral.hairlineLight),

    error = Color(0xFFE5484D),
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E6),
    onErrorContainer = Color(0xFF8C1A1E),

    inverseSurface = Color(Neutral.ink),
    inverseOnSurface = Color(Neutral.canvasLight),
    inversePrimary = LotusBlue,
    scrim = Color.Black,
)

// --------------------------------------------------------------- 暗色方案
val LotusDarkScheme = darkColorScheme(
    primary = Color(0xFF97B2F9),
    onPrimary = Color(0xFF0A2456),
    primaryContainer = Color(0xFF2846B8),
    onPrimaryContainer = LotusBlueMist,

    secondary = Color(0xFFC6CDD3),
    onSecondary = Color(0xFF1A1F23),
    secondaryContainer = Color(0xFF2B3134),
    onSecondaryContainer = Color(0xFFE3E7EA),

    tertiary = Color(0xFFB7B3F5),
    onTertiary = Color(0xFF23236B),
    tertiaryContainer = Color(0xFF3A3A8C),
    onTertiaryContainer = Color(0xFFE4E4FF),

    background = Color(Neutral.canvasDark),
    onBackground = Color(0xFFF0F2F3),
    surface = Color(Neutral.canvasDark),
    onSurface = Color(0xFFF0F2F3),
    surfaceVariant = Color(Neutral.hairlineDark),
    onSurfaceVariant = Color(Neutral.inkFaint),
    surfaceContainerLowest = Color(Neutral.cardDark),
    surfaceContainerLow = Color(Neutral.cardDark),
    surfaceContainer = Color(0xFF1A1D1F),
    surfaceContainerHigh = Color(0xFF212426),
    surfaceContainerHighest = Color(0xFF282B2E),

    outline = Color(0xFF4A5158),
    outlineVariant = Color(Neutral.hairlineDark),

    error = Color(0xFFFF9598),
    onError = Color(0xFF4C0005),
    errorContainer = Color(0xFF8C1A1E),
    onErrorContainer = Color(0xFFFFE5E6),

    inverseSurface = Color(0xFFF0F2F3),
    inverseOnSurface = Color(Neutral.canvasDark),
    inversePrimary = LotusBlueDeep,
    scrim = Color.Black,
)

/**
 * 语义色（Material 3 色板之外，加密钱包必需的）。
 *
 * ⚠️ 涨跌配色遵循**中国市场惯例：涨为红、跌为绿**。
 * 若面向海外用户，只需交换 [rise] 与 [fall] 两个值即可，其余代码不用改。
 */
data class LotusSemanticColors(
    /** 价格上涨。 */
    val rise: Color,
    /** 价格下跌。 */
    val fall: Color,
    /** 价格持平。 */
    val flat: Color,
    /** 链图标底色（无图标时的回退）。 */
    val chainFallback: Color,
    /** 显著提示（例如「未备份助记词」）。 */
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** 安全/已验证标识。 */
    val verified: Color,
    /** 危险操作（删除、清空）。 */
    val danger: Color,
)

private val LightSemantic = LotusSemanticColors(
    rise = Color(0xFFE5484D),
    fall = Color(0xFF30A46C),
    flat = Color(Neutral.inkSoft),
    chainFallback = LotusBlue,
    warning = Color(0xFFB45309),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFF0D4),
    onWarningContainer = Color(0xFF6B3A02),
    verified = Color(0xFF0284A8),
    danger = Color(0xFFE5484D),
)

private val DarkSemantic = LotusSemanticColors(
    rise = Color(0xFFFF7A80),
    fall = Color(0xFF6BD5A0),
    flat = Color(Neutral.inkFaint),
    chainFallback = LotusBlue,
    warning = Color(0xFFF5A524),
    onWarning = Color(0xFF2A1A00),
    warningContainer = Color(0xFF4A3300),
    onWarningContainer = Color(0xFFFFF0D4),
    verified = LotusCyan,
    danger = Color(0xFFFF9598),
)

internal fun semanticColors(darkTheme: Boolean): LotusSemanticColors =
    if (darkTheme) DarkSemantic else LightSemantic
