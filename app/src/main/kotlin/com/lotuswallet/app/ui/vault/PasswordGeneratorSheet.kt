package com.lotuswallet.app.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lotuswallet.app.R
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.SwitchRow
import com.lotuswallet.app.util.rememberClipboard
import com.lotuswallet.core.crypto.PasswordGenerator
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.MonoText
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import kotlin.math.roundToInt

private const val MIN_LENGTH = 6
private const val MAX_LENGTH = 48

/**
 * 随机密码生成器。
 *
 * ## 为什么单独做一个（而不是让用户自己想）
 * 用户自创的密码几乎总是「单词 + 数字 + 感叹号」，熵极低且跨站重复。
 * 生成器把「强密码」变成一次点击，而且默认开启全部四种字符集。
 *
 * ## 生成逻辑在 core:crypto
 * 这里只负责交互。[PasswordGenerator] 使用 [com.lotuswallet.core.crypto.SecureRandom]，
 * 并保证每个被选中的字符集至少出现一次（旧版基于 `java.util.Random`，可预测）。
 */
@Composable
fun PasswordGeneratorBody(
    modifier: Modifier = Modifier,
    onUse: ((String) -> Unit)? = null,
    onCopied: (() -> Unit)? = null,
) {
    val copy = rememberClipboard()

    var length by remember { mutableIntStateOf(18) }
    var digits by remember { mutableStateOf(true) }
    var lowercase by remember { mutableStateOf(true) }
    var uppercase by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var avoidAmbiguous by remember { mutableStateOf(false) }
    var nonce by remember { mutableIntStateOf(0) }

    val options = PasswordGenerator.Options(
        length = length,
        digits = digits,
        lowercase = lowercase,
        uppercase = uppercase,
        symbols = symbols,
        avoidAmbiguous = avoidAmbiguous,
    )
    val password = remember(options, nonce) {
        runCatching { PasswordGenerator.generate(options) }.getOrDefault("")
    }
    val entropy = remember(password) { PasswordGenerator.entropyBits(password).toInt() }

    /** 不允许把最后一个字符集也关掉 —— 那会让生成器无字符可用。 */
    fun toggle(current: Boolean, set: (Boolean) -> Unit, othersOn: Boolean) {
        if (current && !othersOn) return
        set(!current)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------------------ 生成结果
        LotusCard {
            Column {
                MonoText(
                    text = password.ifEmpty { "—" },
                    small = true,
                    maxLines = 4,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.vault_entropy, entropy),
                    style = MaterialTheme.typography.labelSmall,
                    color = entropyColor(entropy),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = stringResource(R.string.action_generate),
                onClick = { nonce++ },
                modifier = Modifier.weight(1f),
            )
            if (onUse != null) {
                PrimaryButton(
                    text = stringResource(R.string.vault_generator_use),
                    onClick = { if (password.isNotEmpty()) onUse(password) },
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        PrimaryButton(
            text = stringResource(R.string.action_copy),
            onClick = {
                if (password.isNotEmpty()) {
                    copy(password, true)
                    onCopied?.invoke()
                }
            },
            enabled = password.isNotEmpty(),
        )

        // ------------------------------------------------------------ 长度
        SectionCard(title = stringResource(R.string.vault_generator_length)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(text = length.toString(), small = false)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "/ $MAX_LENGTH",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = length.toFloat(),
                    onValueChange = { length = it.roundToInt().coerceIn(MIN_LENGTH, MAX_LENGTH) },
                    valueRange = MIN_LENGTH.toFloat()..MAX_LENGTH.toFloat(),
                    steps = MAX_LENGTH - MIN_LENGTH - 1,
                )
            }
        }

        // ------------------------------------------------------------ 字符集
        SectionCard(title = stringResource(R.string.vault_generator_charset)) {
            val charsetCount = listOf(digits, lowercase, uppercase, symbols).count { it }

            SwitchRow(
                title = stringResource(R.string.vault_option_digits),
                checked = digits,
                onCheckedChange = { toggle(digits, { v -> digits = v }, charsetCount > 1) },
            )
            SwitchRow(
                title = stringResource(R.string.vault_option_lower),
                checked = lowercase,
                onCheckedChange = { toggle(lowercase, { v -> lowercase = v }, charsetCount > 1) },
            )
            SwitchRow(
                title = stringResource(R.string.vault_option_upper),
                checked = uppercase,
                onCheckedChange = { toggle(uppercase, { v -> uppercase = v }, charsetCount > 1) },
            )
            SwitchRow(
                title = stringResource(R.string.vault_option_symbols),
                checked = symbols,
                onCheckedChange = { toggle(symbols, { v -> symbols = v }, charsetCount > 1) },
            )
            SwitchRow(
                title = stringResource(R.string.vault_option_avoid_ambiguous),
                checked = avoidAmbiguous,
                onCheckedChange = { avoidAmbiguous = !avoidAmbiguous },
            )
        }
    }
}

@Composable
private fun entropyColor(bits: Int) = when {
    bits >= 90 -> LotusTheme.semantic.verified
    bits >= 60 -> MaterialTheme.colorScheme.primary
    bits >= 40 -> LotusTheme.semantic.warning
    else -> LotusTheme.semantic.danger
}

/** 独立页面形态：作为随手可用的工具箱，不需要先建一条保险库条目。 */
@Composable
fun PasswordGeneratorScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val copiedText = stringResource(R.string.action_copied)
    var message by remember { mutableStateOf<String?>(null) }

    SnackbarEffect(snackbar, message) { message = null }

    DetailScaffold(
        title = stringResource(R.string.vault_generator),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) { padding ->
        ReadableContent(maxWidth = 640.dp) {
            Column(modifier = Modifier.padding(padding)) {
                PasswordGeneratorBody(onCopied = { message = copiedText })
            }
        }
    }
}

/**
 * 底部弹层形态：在编辑保险库条目时直接「生成并填入」某个密码字段。
 * 让用户跳到独立页面再回来会丢掉正在填写的表单上下文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorSheet(
    onDismiss: () -> Unit,
    onUse: (String) -> Unit,
    onCopied: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ReadableContent(maxWidth = 640.dp) {
            PasswordGeneratorBody(
                onUse = { password ->
                    onUse(password)
                    onDismiss()
                },
                onCopied = onCopied,
            )
        }
    }
}
