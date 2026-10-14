package com.lotuswallet.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lotuswallet.app.R
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusTopBar
import com.lotuswallet.core.designsystem.theme.LotusTheme

/**
 * 统一确认对话框。
 *
 * 危险操作（删除钱包、导出明文）必须用 [destructive]，把确认按钮染成警示色 ——
 * 这是让用户在「肌肉记忆点确认」之前有半秒犹豫的唯一手段。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = androidx.compose.ui.res.stringResource(R.string.action_confirm),
    dismissText: String = androidx.compose.ui.res.stringResource(R.string.action_cancel),
    destructive: Boolean = false,
    /** 只有一个按钮（用于纯提示）。 */
    singleAction: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (destructive) LotusTheme.semantic.danger else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = if (singleAction) null else {
            { TextButton(onClick = onDismiss) { Text(dismissText) } }
        },
    )
}

/**
 * 把一次性消息（"已复制"、错误提示）转成 Snackbar。
 *
 * @param message 为 null 时什么都不做；展示完成后回调 [onConsumed] 把它清空，
 *                否则重组会反复弹同一条提示。
 */
@Composable
fun SnackbarEffect(
    hostState: SnackbarHostState,
    message: String?,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            hostState.showSnackbar(message)
            onConsumed()
        }
    }
}

/** 顶部/底部提示承载。 */
@Composable
fun rememberSnackbarHost() = remember { SnackbarHostState() }

/**
 * 二级页面骨架。
 *
 * 统一处理：返回箭头、顶栏动作、Snackbar 承载、键盘避让（[imePadding]）。
 * 键盘避让放在这里而不是每个页面各自处理 —— 忘记加的结果是输入框被键盘挡住，
 * 而这在实际使用中是最烦人的问题之一。
 */
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { LotusTopBar(title = title, subtitle = subtitle, onBack = onBack, actions = actions) },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // 刻意**不**在这里消费 [padding]：把内边距交给内容自己处理，
        // 因为滚动容器需要它作为 LazyColumn 的 contentPadding（而不是 modifier），
        // 否则列表最后一项会被底部按钮挡住。若此处再消费一次，页面会出现双倍内边距。
        Box(modifier = Modifier.imePadding()) { content(padding) }
    }
}

/** 可滚动的内容容器，统一底部留白。 */
@Composable
fun ScrollColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        content = content,
    )
}

/** 普通文本输入。 */
@Composable
fun LotusTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.medium,
    )
}

/** 密码 / 密钥输入：默认隐藏，可切换明文。 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    initiallyVisible: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Password,
) {
    var visible by remember { mutableStateOf(initiallyVisible) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (visible) KeyboardType.Text else keyboardType,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                )
            }
        },
        shape = MaterialTheme.shapes.medium,
    )
}

/** 带开关的设置行。 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 警示卡片（未备份、风险提示、离线状态）。 */
@Composable
fun WarningCard(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = LotusTheme.semantic.warningContainer,
    contentColor: Color = LotusTheme.semantic.onWarningContainer,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (actionText != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAction) { Text(actionText, color = contentColor) }
            }
        }
    }
}

/** 主操作按钮（整宽）。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(50.dp),
        enabled = enabled,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/** 危险操作按钮（删除）。 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(50.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = LotusTheme.semantic.danger,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/** 一行「标签 — 值」。 */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        if (mono) {
            com.lotuswallet.core.designsystem.component.MonoText(
                text = value,
                modifier = Modifier.weight(1f),
                small = true,
                color = valueColor,
                maxLines = 4,
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                modifier = Modifier.weight(1f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分组卡片：标题 + 内容。 */
@Composable
fun SectionCard(
    // modifier 必须是**第一个可选参数**（Compose 规范）：调用方按位置传参时
    // 才不会把 Modifier 误当成 title。本文件所有调用点都用具名参数，改动无影响。
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    LotusCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        content()
    }
}

/** 空状态占位（薄封装，统一文案带图标）。 */
@Composable
fun FullScreenEmpty(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        com.lotuswallet.core.designsystem.component.EmptyState(
            title = title,
            description = description,
            action = action,
        )
    }
}

/** 分隔线。 */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** 横向间隔。 */
@Composable
fun HSpace(width: Int) = Spacer(Modifier.size(width.dp))

/** 纵向间隔。 */
@Composable
fun VSpace(height: Int) = Spacer(Modifier.height(height.dp))

/** 用于「可选」标记的小标签行。 */
@Composable
fun OptionalHint(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 保证间距一致性的一组排列参数。 */
@Composable
fun verticalArrangementTight() = Arrangement.spacedBy(8.dp)
