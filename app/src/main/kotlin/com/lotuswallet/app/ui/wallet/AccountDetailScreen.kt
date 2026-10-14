package com.lotuswallet.app.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import com.lotuswallet.app.ui.receive.ReceiveDialog
import com.lotuswallet.app.ui.theme.AppDisplay
import com.lotuswallet.app.util.Clipboard
import com.lotuswallet.app.util.Formatters
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.LoadingBox
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.MonoText
import com.lotuswallet.core.designsystem.component.SectionHeader
import com.lotuswallet.core.designsystem.theme.LotusTheme
import kotlinx.coroutines.launch

/**
 * 地址详情页：余额、收款、转账、代币、历史、导出密钥。
 *
 * 「导出密钥」放在二级菜单而不是首屏按钮，因为它是**不可逆的高风险操作**；
 * 但也不能藏得太深 —— 自持钱包必须让用户随时能拿走自己的密钥，否则就成了伪自持。
 */
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onSend: (accountId: String) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val display = AppDisplay.policy

    var showMenu by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAddToken by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    val account = state.account
    val chainName = state.chain?.name ?: account?.chainId.orEmpty()

    DetailScaffold(
        title = chainName,
        subtitle = account?.let { Formatters.shorten(it.address, 12, 8) },
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (state.canSign) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.wallet_export_secret)) },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                            onClick = { showMenu = false; confirmExport = true },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wallet_add_account)) },
                        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            state.chain?.let { viewModel.addAccount(it.id) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wallet_delete_account)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = LotusTheme.semantic.danger)
                        },
                        onClick = { showMenu = false; confirmDelete = true },
                    )
                }
            }
        },
    ) { padding ->
        if (state.loading || account == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingBox(modifier = Modifier.align(Alignment.Center))
            }
            return@DetailScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------------------------------------------------- 余额
            item("balance") {
                LotusCard {
                    Text(
                        text = stringResource(R.string.wallet_balance),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            display.hideBalances -> "••••••"
                            state.balance == null -> stringResource(R.string.wallet_balance_loading)
                            state.balance?.unavailable == true -> stringResource(R.string.wallet_balance_unavailable)
                            else -> state.balance?.formatted.orEmpty()
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (state.balance?.unavailable == true) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.chain?.symbol.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.balance?.note?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = LotusTheme.semantic.warning,
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { showReceive = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.wallet_receive))
                        }
                        PrimaryButton(
                            text = stringResource(R.string.wallet_send),
                            onClick = { onSend(account.id) },
                            modifier = Modifier.weight(1f),
                            enabled = state.canSign,
                        )
                    }
                    if (!state.canSign) {
                        Spacer(Modifier.height(10.dp))
                        WarningCard(text = stringResource(R.string.wallet_send_watch_only))
                    }
                }
            }

            // ---------------------------------------------------------- 地址
            item("address") {
                SectionCard(title = stringResource(R.string.wallet_address)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AddressAvatar(
                            seed = account.address,
                            label = state.chain?.symbol?.take(2),
                            size = 40.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        MonoText(
                            text = account.address,
                            modifier = Modifier.weight(1f),
                            small = true,
                            maxLines = 3,
                        )
                        IconButton(onClick = {
                            Clipboard.copy(context, account.address, "address")
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.action_copied)) }
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                        }
                    }
                    DetailRow(stringResource(R.string.wallet_derivation_path), account.derivationPath, modifier = Modifier.padding(horizontal = 16.dp))
                    account.publicKeyHex?.let {
                        DetailRow("公钥", Formatters.shorten(it, 16, 8), mono = true, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }

            // ---------------------------------------------------------- 代币
            if (state.chain?.let { it is com.lotuswallet.core.network.chain.EvmChainConfig } == true) {
                item("tokens") {
                    SectionCard(title = stringResource(R.string.wallet_tokens)) {
                        if (state.tokens.isEmpty()) {
                            Text(
                                text = stringResource(R.string.wallet_tokens_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        } else {
                            for (row in state.tokens) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AddressAvatar(seed = row.token.contractAddress, label = row.token.symbol.take(2), size = 32.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(row.token.symbol, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            text = row.token.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = if (display.hideBalances) {
                                            "••••"
                                        } else if (row.balance == null) {
                                            stringResource(R.string.wallet_balance_unavailable)
                                        } else {
                                            Formatters.formatUnits(row.balance, row.token.decimals)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = { showAddToken = true },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.wallet_add_token))
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 历史
            item("history-header") {
                SectionHeader(title = stringResource(R.string.wallet_history))
            }
            if (!state.historySupported) {
                item("history-na") {
                    WarningCard(
                        text = stringResource(R.string.wallet_history_unavailable),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionText = state.chain?.explorerAddressUrl?.let { stringResource(R.string.action_open_browser) },
                        onAction = {
                            val url = state.chain?.addressUrl(account.address)
                            if (url != null) com.lotuswallet.app.util.Browser.open(context, url)
                        },
                    )
                }
            } else if (state.history.isEmpty()) {
                item("history-empty") {
                    Text(
                        text = stringResource(R.string.wallet_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(state.history, key = { it.txId }) { item ->
                    LotusCard(
                        onClick = {
                            val url = state.chain?.txUrl(item.txId)
                            if (url != null) com.lotuswallet.app.util.Browser.open(context, url)
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        item.direction > 0 -> stringResource(R.string.wallet_received)
                                        item.direction < 0 -> stringResource(R.string.wallet_sent)
                                        else -> stringResource(R.string.label_unknown)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = Formatters.formatTime(item.timestamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        if (item.confirmations > 0) R.string.wallet_history_confirmations
                                        else R.string.wallet_history_pending,
                                        item.confirmations,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = if (display.hideBalances) {
                                    "••••"
                                } else {
                                    (if (item.direction > 0) "+" else "") +
                                        Formatters.formatUnits(item.delta, item.decimals)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    item.direction > 0 -> LotusTheme.semantic.rise
                                    item.direction < 0 -> LotusTheme.semantic.fall
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 对话框

    if (showReceive) {
        ReceiveDialog(
            address = account?.address.orEmpty(),
            chainName = chainName,
            onDismiss = { showReceive = false },
        )
    }

    if (confirmExport) {
        ConfirmDialog(
            title = stringResource(R.string.wallet_export_confirm_title),
            message = stringResource(R.string.wallet_export_confirm_desc),
            confirmText = stringResource(R.string.action_continue),
            destructive = true,
            onConfirm = { confirmExport = false; showExport = true; viewModel.revealSecret() },
            onDismiss = { confirmExport = false },
        )
    }

    if (showExport) {
        SecretRevealDialog(
            walletType = state.wallet?.type,
            secret = state.secret,
            onDismiss = { showExport = false; viewModel.hideSecret() },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.wallet_delete_account),
            message = stringResource(R.string.wallet_delete_account_confirm),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteAccount()
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (showAddToken) {
        AddTokenDialog(
            onAdd = { symbol, name, contract, decimals ->
                showAddToken = false
                viewModel.addToken(symbol, name, contract, decimals)
            },
            onDismiss = { showAddToken = false },
        )
    }
}

/** 明文密钥展示。全程禁止截屏。 */
@Composable
internal fun SecretRevealDialog(
    walletType: WalletType?,
    secret: com.lotuswallet.core.database.entity.WalletSecret?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }

    val text = when {
        secret == null -> ""
        !secret.mnemonic.isNullOrBlank() -> secret.mnemonic.orEmpty()
        !secret.privateKeyHex.isNullOrBlank() -> secret.privateKeyHex.orEmpty()
        else -> ""
    }
    val label = when (walletType) {
        WalletType.MNEMONIC -> stringResource(R.string.wallet_secret_mnemonic)
        WalletType.PRIVATE_KEY -> stringResource(R.string.wallet_secret_private_key)
        else -> stringResource(R.string.label_none)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column {
                WarningCard(text = stringResource(R.string.wallet_backup_warning))
                Spacer(Modifier.height(12.dp))
                if (text.isEmpty()) {
                    Text(stringResource(R.string.label_none))
                } else {
                    MonoText(
                        text = if (revealed) text else "•".repeat(text.length.coerceAtMost(48)),
                        small = false,
                        maxLines = 8,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Clipboard.copySensitive(context, text)
                onDismiss()
            }) { Text(stringResource(R.string.action_copy)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(stringResource(if (revealed) R.string.action_hide else R.string.action_show))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

/** 手动添加 ERC-20 代币。 */
@Composable
private fun AddTokenDialog(
    onAdd: (symbol: String, name: String, contract: String, decimals: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var contract by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var decimals by remember { mutableStateOf("18") }

    val contractValid = com.lotuswallet.chain.evm.EvmAddress.isValid(contract)
    val canAdd = contractValid && symbol.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_add_token)) },
        text = {
            Column {
                LotusTextField(
                    value = contract,
                    onValueChange = { contract = it },
                    label = stringResource(R.string.wallet_token_contract),
                    isError = contract.isNotEmpty() && !contractValid,
                )
                Spacer(Modifier.height(10.dp))
                LotusTextField(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase() },
                    label = stringResource(R.string.wallet_token_symbol),
                )
                Spacer(Modifier.height(10.dp))
                LotusTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.wallet_token_name),
                )
                Spacer(Modifier.height(10.dp))
                LotusTextField(
                    value = decimals,
                    onValueChange = { decimals = it.filter { c -> c.isDigit() }.take(2) },
                    label = stringResource(R.string.wallet_token_decimals),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canAdd,
                onClick = { onAdd(symbol.trim(), name.trim().ifBlank { symbol.trim() }, contract.trim(), decimals.toIntOrNull() ?: 18) },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
