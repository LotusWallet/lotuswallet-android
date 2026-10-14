package com.lotuswallet.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体排印（v2）。
 *
 * ## 上一版的问题
 * 上一版直接 `Typography()` —— 也就是 Material 3 的**全套默认值**。
 * 默认值本身不难看，但它没有为「钱包」这个场景做任何取舍：
 * 标题不够重、正文偏大、数字与文字没有视觉分层，页面因此显得平、没有骨架。
 *
 * 这一版的原则：
 * 1. **收紧字号区间**（11–40sp），拉开字重对比（SemiBold 标题 / Regular 正文），
 *    用「重量的落差」而不是「颜色的落差」建立层级 —— 这是 OKX / Bitget 的做法；
 * 2. **负字距用在标题上**，中英文混排时更紧凑、更现代；
 * 3. **金额与地址必须等宽**，否则列表里的数字会跳动、地址难以逐位校对；
 * 4. 字号一律用 `sp`，随系统字号缩放（不做 fontScale 锁定）—— 无障碍要求。
 */

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Double = 0.0,
): TextStyle = TextStyle(
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val LotusTypography = Typography(
    // 超大金额（首页总资产）
    displayLarge = style(40, 48, FontWeight.SemiBold, -1.0),
    displayMedium = style(34, 42, FontWeight.SemiBold, -0.8),
    displaySmall = style(30, 38, FontWeight.SemiBold, -0.6),

    // 区块级标题
    headlineLarge = style(28, 36, FontWeight.SemiBold, -0.5),
    headlineMedium = style(26, 34, FontWeight.SemiBold, -0.4),
    headlineSmall = style(24, 32, FontWeight.SemiBold, -0.4),

    // 页面 / 卡片标题
    titleLarge = style(20, 28, FontWeight.SemiBold, -0.3),
    titleMedium = style(16, 24, FontWeight.SemiBold, -0.2),
    titleSmall = style(14, 20, FontWeight.SemiBold, -0.1),

    // 正文
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),

    // 标签 / 按钮 / 角标
    labelLarge = style(14, 20, FontWeight.Medium, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.2),
    labelSmall = style(11, 14, FontWeight.Medium, 0.3),
)

/** 等宽样式：金额、地址、哈希、口令。 */
val MonospaceBody = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.sp,
)

val MonospaceSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)

/** 大号金额（首页总资产）。 */
val AmountDisplay = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 36.sp,
    lineHeight = 44.sp,
    letterSpacing = (-1.0).sp,
)
