package com.lotuswallet.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 地址 / 条目的确定性头像。
 *
 * 同一个地址永远得到同一个配色，用户可以在列表中凭颜色快速辨认，
 * 也能对「地址被悄悄替换」产生直觉上的警觉（颜色突变）。
 */
@Composable
fun AddressAvatar(
    seed: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: Dp = 40.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    val palette = avatarPalette()
    val index = abs(seed.hashCode()) % palette.size
    val base = palette[index]
    val text = label ?: seed.filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "?" }

    Box(
        modifier = modifier
            .size(size)
            .background(
                brush = Brush.linearGradient(
                    listOf(base, base.copy(alpha = 0.65f)),
                    start = Offset.Zero,
                    end = Offset(size.value * 1.4f, size.value * 1.4f),
                ),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value / 2.6f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 与主题无关的一组稳定候选色，保证明暗模式下都足够清晰。 */
private val AvatarPaletteCache = listOf(
    Color(0xFF2E7D6F),
    Color(0xFF5B4FCF),
    Color(0xFFB25E3A),
    Color(0xFF3B6FB5),
    Color(0xFF7A4FA3),
    Color(0xFF2F7D3A),
    Color(0xFFB54B6B),
    Color(0xFF8A6A2F),
)

internal fun avatarPalette(): List<Color> = AvatarPaletteCache

/**
 * 倒计时环（OTP 口令剩余时间、解锁重试倒计时）。
 *
 * @param progress 剩余比例，1.0 表示刚开始，0.0 表示已耗尽
 */
@Composable
fun CountdownRing(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    content: @Composable () -> Unit = {},
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(
                (size.width - stroke).coerceAtLeast(0f),
                (size.height - stroke).coerceAtLeast(0f),
            )
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = indicatorColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

/** 密码圆点。 */
@Composable
fun PinDots(
    length: Int,
    filled: Int,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    dotSize: Dp = 14.dp,
) {
    val active = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(length) { index ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(if (index < filled) active else inactive, CircleShape),
            )
        }
    }
}

/**
 * 数字键盘。
 *
 * 刻意不使用系统输入法：钱包的手势密码/主密码输入必须避免被第三方输入法记录，
 * 同时防止截屏预测。这是安全要求，不是交互偏好。
 */
@Composable
fun PinKeypad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    showBiometric: Boolean = false,
    onBiometric: () -> Unit = {},
) {
    val rows = listOf(
        listOf(1, 2, 3),
        listOf(4, 5, 6),
        listOf(7, 8, 9),
    )
    Column(modifier = modifier.fillMaxWidth()) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (digit in row) KeypadKey(text = digit.toString()) { onDigit(digit) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBiometric) {
                KeypadIconKey(icon = Icons.Outlined.Fingerprint, description = "生物识别", onClick = onBiometric)
            } else {
                Spacer(Modifier.size(KeypadKeySize))
            }
            KeypadKey(text = "0") { onDigit(0) }
            KeypadIconKey(icon = Icons.AutoMirrored.Outlined.Backspace, description = "删除", onClick = onBackspace)
        }
    }
}

private val KeypadKeySize = 68.dp

@Composable
private fun KeypadKey(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(KeypadKeySize)
            .padding(4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun KeypadIconKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(KeypadKeySize)
            .padding(4.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 自适应布局下的内容宽度约束。
 *
 * 大屏上不加约束会让一段文字横跨整个屏幕，阅读体验很差。
 * 所有内容的「阅读区」都应当用它包一层。
 */
@Composable
fun ReadableContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 640.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.fillMaxWidth().widthIn(max = maxWidth)) { content() }
    }
}

/** 顶部提示条（未备份助记词、网络异常等）。 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

/** 简单的行内进度条（用于引导流程）。 */
@Composable
fun StepIndicator(
    total: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (index == current) 24.dp else 12.dp)
                    .background(
                        if (index <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}
