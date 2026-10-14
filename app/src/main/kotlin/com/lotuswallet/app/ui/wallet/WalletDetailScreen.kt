package com.lotuswallet.app.ui.wallet

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotuswallet.app.R
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.common.DetailRow
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.app.util.Formatters
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.MonoText

/**
 * 钱包详情：钱包级操作（改名、新增地址、备份、导出、删除）。
 *
 * 与「地址详情」的分工很明确：这里管的是**一份密钥材料**，地址详情管的是**一个地址**。
 * 旧版把两者混在一张表里，导致「删除地址」会连密钥一起删掉，是个真实的数据事故。
 */
@Composable
fun WalletDetailScreen(
    onBack: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onBackupMnemonic: (String) -> Unit,
    viewModel: WalletDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showPickChain by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    val wallet = state.wallet

    DetailScaffold(
        title = wallet?.name ?: stringResource(R.string.wallet_accounts),
        subtitle = wallet?.let {
            when (it.type) {
                WalletType.MNEMONIC -> stringResource(R.string.wallet_mnemonic_badge)
                WalletType.PRIVATE_KEY -> stringResource(R.string.wallet_private_key_badge)
                WalletType.WATCH_ONLY -> stringResource(R.string.wallet_watch_only_badge)
            }
        },
        onBack = onBack,
        snackbarHostState = snackbar,
        bottomBar = {
            if (wallet != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    if (wallet.type == WalletType.MNEMONIC && !wallet.backedUp) {
                        com.lotuswallet.app.ui.common.PrimaryButton(
                            text = stringResource(R.string.wallet_backup_now),
                            onClick = { onBackupMnemonic(wallet.id) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (wallet == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
            return@DetailScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (wallet.type == WalletType.MNEMONIC && !wallet.backedUp) {
                WarningCard(
                    text = stringResource(R.string.wallet_backup_pending),
                    actionText = stringResource(R.string.wallet_backup_now),
                    onAction = { onBackupMnemonic(wallet.id) },
                )
            } else if (wallet.type == WalletType.MNEMONIC) {
                WarningCard(
                    text = stringResource(R.string.wallet_backed_up),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = com.lotuswallet.core.designsystem.theme.LotusTheme.semantic.verified,
                )
            }

            SectionCard(title = stringResource(R.string.wallet_accounts)) {
                for (row in state.rows) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenAccount(row.account.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AddressAvatar(
                            seed = row.account.address,
                            label = row.chain?.symbol?.take(2),
                            size = 34.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.chain?.name ?: row.account.chainId, style = MaterialTheme.typography.bodyLarge)
                            MonoText(
                                text = Formatters.shorten(row.account.address, 10, 8),
                                small = true,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(onClick = { showPickChain = true }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.wallet_add_account))
                }
            }

            // ---------------------------------------------------- 管理
            LotusCard {
                DetailRow(
                    label = stringResource(R.string.wallet_name_label),
                    value = wallet.name,
                )
                DetailRow(
                    label = stringResource(R.string.wallet_accounts),
                    value = state.rows.size.toString(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showRename = true }) { Text(stringResource(R.string.action_rename)) }
                    if (wallet.type != WalletType.WATCH_ONLY) {
                        TextButton(onClick = { confirmExport = true }) {
                            Text(stringResource(R.string.wallet_export_secret))
                        }
                    }
                    TextButton(onClick = {
                        com.lotuswallet.app.util.Clipboard.copy(context, wallet.id, "wallet-id")
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.width(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("ID")
                    }
                }
            }

            com.lotuswallet.app.ui.common.DangerButton(
                text = stringResource(R.string.wallet_delete_title),
                onClick = { showDelete = true },
            )
        }
    }

    // ---------------------------------------------------------------- 对话框

    if (showRename && wallet != null) {
        var name by remember { mutableStateOf(wallet.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                LotusTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.wallet_name_label),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.rename(name); showRename = false }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showPickChain) {
        AlertDialog(
            onDismissRequest = { showPickChain = false },
            title = { Text(stringResource(R.string.wallet_add_account_chain)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for (chain in state.availableChains) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPickChain = false
                                    viewModel.addAccount(chain.id)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${chain.name} · ${chain.symbol}", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPickChain = false }) { Text(stringResource(R.string.action_close)) }
            },
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
            walletType = wallet?.type,
            secret = state.secret,
            onDismiss = { showExport = false; viewModel.hideSecret() },
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = stringResource(R.string.wallet_delete_title),
            message = stringResource(R.string.wallet_delete_confirm),
            destructive = true,
            onConfirm = { showDelete = false; viewModel.delete(); onBack() },
            onDismiss = { showDelete = false },
        )
    }
}
