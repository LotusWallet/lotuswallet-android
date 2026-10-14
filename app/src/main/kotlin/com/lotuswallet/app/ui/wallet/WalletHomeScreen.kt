package com.lotuswallet.app.ui.wallet

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotuswallet.app.R
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.app.ui.theme.AppDisplay
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.EmptyState
import com.lotuswallet.core.designsystem.component.LoadingBox
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusChip
import com.lotuswallet.core.designsystem.component.LotusTopBar
import com.lotuswallet.core.designsystem.component.ReadableContent

/**
 * 钱包 Tab 首页。
 *
 * 结构上刻意不做「总资产折算法币」的大数字：本产品**不接入行情源**，
 * 把不同链的原生币加总成一个数字既没有意义，也会误导用户。
 * 于是首页的真实信息密度放在「每个地址各自的余额」上。
 */
@Composable
fun WalletHomeScreen(
    onOpenAccount: (String) -> Unit,
    onAddWallet: () -> Unit,
    onOpenWallet: (String) -> Unit,
    onBackupWallet: (String) -> Unit,
    onLock: () -> Unit,
    viewModel: WalletHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val display = AppDisplay.policy

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    Scaffold(
        topBar = {
            LotusTopBar(
                title = stringResource(R.string.tab_wallet),
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.refreshing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = onLock) {
                        Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.action_lock))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!state.isEmpty) {
                ExtendedFloatingActionButton(
                    onClick = onAddWallet,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.wallet_create)) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingBox(modifier = Modifier.align(Alignment.Center))
            }
            return@Scaffold
        }

        if (state.isEmpty) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.wallet_empty_title),
                    description = stringResource(R.string.wallet_empty_desc),
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            com.lotuswallet.app.ui.common.PrimaryButton(
                                text = stringResource(R.string.wallet_create),
                                onClick = onAddWallet,
                                modifier = Modifier.width(220.dp),
                            )
                        }
                    },
                )
            }
            return@Scaffold
        }

        ReadableContent(maxWidth = 720.dp) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val unbackedUp = state.groups.filter { it.wallet.type == WalletType.MNEMONIC && !it.wallet.backedUp }
                if (unbackedUp.isNotEmpty()) {
                    item(key = "backup-warning") {
                        WarningCard(
                            text = stringResource(R.string.wallet_backup_pending) + "：" +
                                unbackedUp.joinToString("、") { it.wallet.name },
                            actionText = stringResource(R.string.wallet_backup_now),
                            onAction = { onBackupWallet(unbackedUp.first().wallet.id) },
                        )
                    }
                }

                items(state.groups, key = { it.wallet.id }) { group ->
                    LotusCard(onClick = { onOpenWallet(group.wallet.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.wallet.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.wallet_accounts_count, group.rows.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LotusChip(
                                text = when (group.wallet.type) {
                                    WalletType.MNEMONIC -> stringResource(R.string.wallet_mnemonic_badge)
                                    WalletType.PRIVATE_KEY -> stringResource(R.string.wallet_private_key_badge)
                                    WalletType.WATCH_ONLY -> stringResource(R.string.wallet_watch_only_badge)
                                },
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        if (group.rows.isEmpty()) {
                            Text(
                                text = stringResource(R.string.wallet_no_account),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            for (row in group.rows) {
                                AccountRowItem(row = row, hidden = display.hideBalances) {
                                    onOpenAccount(row.account.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRowItem(
    row: WalletHomeViewModel.AccountRow,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    val chain = row.chain
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AddressAvatar(
            seed = row.account.address,
            label = chain?.symbol?.take(2) ?: row.account.chainId.take(2).uppercase(),
            size = 36.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chain?.name ?: row.account.chainId,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = com.lotuswallet.app.util.Formatters.shorten(row.account.address, 10, 8),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            val balance = row.balance
            Text(
                text = when {
                    hidden -> "••••"
                    balance == null -> stringResource(R.string.wallet_balance_loading)
                    balance.unavailable -> stringResource(R.string.wallet_balance_unavailable)
                    else -> balance.formatted
                },
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = if (balance?.unavailable == true) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = chain?.symbol ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
