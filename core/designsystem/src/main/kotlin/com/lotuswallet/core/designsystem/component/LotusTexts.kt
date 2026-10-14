package com.lotuswallet.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lotuswallet.core.designsystem.theme.AmountDisplay
import com.lotuswallet.core.designsystem.theme.LotusTheme
import com.lotuswallet.core.designsystem.theme.MonospaceBody
import com.lotuswallet.core.designsystem.theme.MonospaceSmall

/** 等宽文本。密码学数据（地址、哈希、助记词）一律用它。 */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        style = if (small) MonospaceSmall else MonospaceBody,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 金额显示。
 *
 * @param deltaPercent 相对变化（0.012 表示 +1.2%）。为 null 时不显示涨跌。
 *                    配色遵循中国市场惯例：**涨红跌绿**。
 */
@Composable
fun AmountText(
    amount: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    deltaPercent: Double? = null,
    hidden: Boolean = false,
    large: Boolean = false,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (hidden) "••••••" else amount,
                style = if (large) AmountDisplay else MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            if (symbol != null && !hidden) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (large) 6.dp else 2.dp),
                )
            }
        }
        if (deltaPercent != null && !hidden) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatDelta(deltaPercent),
                style = MaterialTheme.typography.bodySmall,
                color = deltaColor(deltaPercent),
            )
        }
    }
}

@Composable
private fun deltaColor(delta: Double): Color = when {
    delta > 0 -> LotusTheme.semantic.rise
    delta < 0 -> LotusTheme.semantic.fall
    else -> LotusTheme.semantic.flat
}

private fun formatDelta(delta: Double): String {
    val pct = delta * 100
    val sign = if (pct > 0) "+" else ""
    return "$sign${"%.2f".format(pct)}%"
}

/**
 * 敏感值显示：默认打码，点击眼睛图标显示。
 * 用于助记词、私钥、OTP 密钥等。
 */
@Composable
fun SensitiveValue(
    value: String,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    maskChar: String = "•",
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (revealed) value else maskChar.repeat(value.length.coerceAtMost(48)),
            style = MonospaceBody,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleReveal) {
            Icon(
                imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (revealed) "隐藏" else "显示",
            )
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
            }
        }
    }
}

/** 标签 + 值的键值对展示。 */
@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        if (mono) {
            MonoText(
                text = value,
                modifier = Modifier.weight(1f),
                small = true,
                color = valueColor,
                maxLines = 2,
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 小圆角标签（链名、代币符号、分类）。 */
@Composable
fun LotusChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 横向排列的标签组（用于展示一个条目的所有分类）。 */
@Composable
fun ChipRow(
    texts: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (text in texts) LotusChip(text)
    }
}
