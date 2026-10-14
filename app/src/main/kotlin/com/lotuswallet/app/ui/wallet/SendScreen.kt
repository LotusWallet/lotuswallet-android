package com.lotuswallet.app.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotuswallet.app.R
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.common.DetailRow
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.app.ui.scan.QrScannerDialog
import com.lotuswallet.app.util.Clipboard
import com.lotuswallet.app.util.Formatters
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.designsystem.component.MonoText
import com.lotuswallet.core.designsystem.theme.LotusTheme
import com.lotuswallet.core.network.chain.UtxoChainConfig

/**
 * 转账页。
 *
 * 三段式：填写 → 报价 → 确认弹窗（展示完整交易信息）→ 广播。
 * 确认弹窗是**唯一**的签名入口，且在里面把收款地址完整展示（不缩略）——
 * 地址替换类攻击的唯一防线就是让用户能逐位核对。
 */
@Composable
fun SendScreen(
    onBack: () -> Unit,
    viewModel: SendViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val broadcast by viewModel.broadcast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showScanner by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var blockedReason by remember { mutableStateOf<String?>(null) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    // 输入变化后自动报价（带防抖）
    LaunchedEffect(state.to, state.amountText, state.feeRateText) {
        if (state.to.isNotBlank() && state.amountText.isNotBlank()) viewModel.requestQuote()
    }

    DetailScaffold(
        title = stringResource(R.string.wallet_send_title),
        subtitle = state.symbol.takeIf { it.isNotBlank() },
        onBack = onBack,
        snackbarHostState = snackbar,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.wallet_send_confirm),
                    enabled = !state.sending && state.to.isNotBlank() && state.amountText.isNotBlank(),
                    onClick = {
                        val error = viewModel.validate()
                        if (error != null) blockedReason = error else showConfirm = true
                    },
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
            return@DetailScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // -------------------------------------------------------- 付款方
            SectionCard(title = stringResource(R.string.wallet_send_from)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    MonoText(text = state.account?.address.orEmpty(), small = true, maxLines = 2)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.wallet_send_balance) + "：" +
                            (state.balance?.formatted ?: stringResource(R.string.wallet_balance_loading)) +
                            " " + state.symbol,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!state.canSign) {
                WarningCard(text = stringResource(R.string.wallet_send_watch_only))
            }

            // -------------------------------------------------------- 收款方
            LotusTextField(
                value = state.to,
                onValueChange = viewModel::setTo,
                label = stringResource(R.string.wallet_send_to),
                placeholder = stringResource(R.string.wallet_send_to_hint),
                singleLine = false,
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            Clipboard.read(context)?.let(viewModel::setTo)
                        }) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.action_paste))
                        }
                        IconButton(onClick = { showScanner = true }) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.action_scan))
                        }
                    }
                },
            )

            // -------------------------------------------------------- 金额
            LotusTextField(
                value = state.amountText,
                onValueChange = viewModel::setAmount,
                label = stringResource(R.string.wallet_send_amount) + "（${state.symbol}）",
                placeholder = stringResource(R.string.wallet_send_amount_hint),
                keyboardType = KeyboardType.Decimal,
                trailingIcon = {
                    TextButton(onClick = { viewModel.fillMax() }) {
                        Text(stringResource(R.string.wallet_send_max))
                    }
                },
            )

            if (state.chain is UtxoChainConfig) {
                LotusTextField(
                    value = state.feeRateText,
                    onValueChange = viewModel::setFeeRate,
                    label = stringResource(R.string.wallet_send_fee_rate),
                    keyboardType = KeyboardType.Number,
                    supportingText = "留空则由钱包按当前网络状况自动估算",
                )
            }

            // -------------------------------------------------------- 报价
            SectionCard(title = stringResource(R.string.wallet_send_fee)) {
                val quote = state.quote
                when {
                    state.quoting -> Text(
                        text = stringResource(R.string.wallet_send_quoting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    quote == null -> Text(
                        text = "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    !quote.ok -> Text(
                        text = quote.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LotusTheme.semantic.danger,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    else -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        DetailRow(
                            label = "预计手续费",
                            value = "${Formatters.formatUnits(quote.fee, quote.decimals)} ${quote.symbol}",
                            mono = true,
                        )
                        quote.note?.let {
                            DetailRow(label = "说明", value = it)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onDecoded = { viewModel.setTo(it); showScanner = false },
            onDismiss = { showScanner = false },
        )
    }

    blockedReason?.let { reason ->
        ConfirmDialog(
            title = stringResource(R.string.wallet_send_confirm_title),
            message = reason,
            confirmText = stringResource(R.string.action_got_it),
            singleAction = true,
            onConfirm = { blockedReason = null },
            onDismiss = { blockedReason = null },
        )
    }

    if (showConfirm) {
        val quote = state.quote
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.wallet_send_confirm_title)) },
            text = {
                Column {
                    DetailRow(stringResource(R.string.wallet_send_to), state.to, mono = true)
                    DetailRow(
                        label = stringResource(R.string.wallet_send_amount),
                        value = "${state.amountText} ${state.symbol}",
                        mono = true,
                    )
                    if (quote != null && quote.ok) {
                        DetailRow(
                            label = stringResource(R.string.wallet_send_fee),
                            value = "${Formatters.formatUnits(quote.fee, quote.decimals)} ${quote.symbol}",
                            mono = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    WarningCard(
                        text = "请逐位核对上面的收款地址。交易广播后不可撤销。",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; viewModel.send() }) {
                    Text(stringResource(R.string.wallet_send_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    broadcast?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeBroadcast(); onBack() },
            title = { Text(stringResource(R.string.wallet_send_broadcast)) },
            text = {
                Column {
                    Text(stringResource(R.string.wallet_send_success))
                    Spacer(Modifier.height(10.dp))
                    MonoText(text = result.txId, small = true, maxLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeBroadcast(); onBack() }) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                if (result.explorerUrl != null) {
                    TextButton(onClick = {
                        com.lotuswallet.app.util.Browser.open(context, result.explorerUrl)
                    }) { Text(stringResource(R.string.wallet_send_view_explorer)) }
                }
            },
        )
    }
}
