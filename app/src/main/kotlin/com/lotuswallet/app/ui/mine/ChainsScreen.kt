package com.lotuswallet.app.ui.mine

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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.ChainEditor
import com.lotuswallet.app.data.ChainService
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.SwitchRow
import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusChip
import com.lotuswallet.core.designsystem.component.MonoText
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.EvmChainConfig
import com.lotuswallet.core.network.chain.supportsKeyDerivation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 链管理。
 *
 * ## 这一页现在做什么
 * 早期版本把链配置当成「只读的声明式数据」，只允许用户看。但 chainlist 转存出来的
 * 公共清单并不完整：小链常只有一两个公共节点、甚至没有；节点挂掉、被墙、限流都很常见。
 * 因此这里改为**允许用户新增 / 修改 / 停用链**，把 40+ 条现成的链当作起点，
 * 而不是天花板。
 *
 * ## 写入的位置
 * 用户的修改只存在**本机**（DataStore），不与远端仓库同步。
 * 覆盖优先级：内置 < 远端 < 用户自定义。想回到官方配置，用「恢复默认」即可。
 */
@HiltViewModel
class ChainsViewModel @Inject constructor(
    private val chainService: ChainService,
    private val editor: ChainEditor,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class UiState(
        val chains: List<ChainEditor.Row> = emptyList(),
        val showTestnets: Boolean = false,
        val verifyingId: String? = null,
    )

    private val verifyingId = MutableStateFlow<String?>(null)
    private val revision = MutableStateFlow(0)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<UiState> = combine(
        settings.settings,
        verifyingId,
        revision,
    ) { appSettings, verifying, _ ->
        UiState(
            chains = editor.rows().filter { appSettings.showTestnets || !it.config.testnet },
            showTestnets = appSettings.showTestnets,
            verifyingId = verifying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setShowTestnets(show: Boolean) {
        viewModelScope.launch {
            settings.setShowTestnets(show)
            revision.value++
        }
    }

    /** 校验节点返回的 chainId。连错链会把用户带到一个「余额为 0」的世界。 */
    fun verify(chain: ChainConfig) {
        if (chain !is EvmChainConfig) return
        viewModelScope.launch {
            verifyingId.value = chain.id
            val actual = chainService.verifyEvmChainId(chain)
            verifyingId.value = null
            _message.value = when (actual) {
                null -> "节点未返回链 ID，请检查 RPC 可达性"
                chain.chainId -> "链 ID 校验通过：${chain.chainId}"
                else -> "链 ID 不匹配：配置为 ${chain.chainId}，节点返回 $actual"
            }
        }
    }

    fun setEnabled(chain: ChainConfig, enabled: Boolean) {
        viewModelScope.launch {
            editor.setEnabled(chain.id, enabled)
            revision.value++
            _message.value = if (enabled) "${chain.name} 已启用" else "${chain.name} 已停用"
        }
    }

    fun remove(chain: ChainConfig) {
        viewModelScope.launch {
            val userDefined = editor.rows().firstOrNull { it.config.id == chain.id }?.userDefined == true
            editor.remove(chain.id)
            revision.value++
            _message.value = if (userDefined) "${chain.name} 已删除" else "${chain.name} 已从列表隐藏（可恢复）"
        }
    }

    fun restore(chain: ChainConfig) {
        viewModelScope.launch {
            editor.restoreDefault(chain.id)
            revision.value++
            _message.value = "${chain.name} 已恢复为内置配置"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

@Composable
fun ChainsScreen(
    onBack: () -> Unit,
    onAddChain: () -> Unit,
    onEditChain: (String) -> Unit,
    viewModel: ChainsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ChainConfig?>(null) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    DetailScaffold(
        title = stringResource(R.string.chain_management),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = onAddChain) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.chain_add))
            }
        },
    ) { padding ->
        ReadableContent(maxWidth = 720.dp) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 16.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "__note__") {
                    Text(
                        text = stringResource(R.string.chain_editable_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item(key = "__testnet_switch__") {
                    SectionCard {
                        SwitchRow(
                            title = stringResource(R.string.settings_show_testnets),
                            subtitle = stringResource(R.string.settings_show_testnets_desc),
                            checked = state.showTestnets,
                            onCheckedChange = viewModel::setShowTestnets,
                        )
                    }
                }

                item(key = "__count__") {
                    Text(
                        text = stringResource(R.string.chain_enabled_count, state.chains.count { it.config.enabled }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(state.chains, key = { it.config.id }) { row ->
                    ChainCard(
                        row = row,
                        verifying = state.verifyingId == row.config.id,
                        onVerify = { viewModel.verify(row.config) },
                        onEdit = { onEditChain(row.config.id) },
                        onToggle = { viewModel.setEnabled(row.config, !row.config.enabled) },
                        onRestore = { viewModel.restore(row.config) },
                        onDelete = { pendingDelete = row.config },
                    )
                }
            }
        }
    }

    pendingDelete?.let { chain ->
        ConfirmDialog(
            title = stringResource(R.string.chain_delete_title),
            message = stringResource(R.string.chain_delete_message, chain.name),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.remove(chain)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ChainCard(
    row: ChainEditor.Row,
    verifying: Boolean,
    onVerify: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val chain = row.config
    var menuOpen by remember { mutableStateOf(false) }

    LotusCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AddressAvatar(seed = chain.id, label = chain.symbol.take(2), size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chain.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${chain.symbol} · 精度 ${chain.decimals} · 币种 ${chain.coinType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!chain.enabled) {
                    LotusChip(
                        text = stringResource(R.string.chain_disabled),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chain is EvmChainConfig) {
                    IconButton(onClick = onVerify, enabled = !verifying) {
                        if (verifying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = stringResource(R.string.wallet_verify_chain_id),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (chain.enabled) R.string.chain_action_disable else R.string.chain_action_enable,
                                    ),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (chain.enabled) Icons.Outlined.ToggleOff else Icons.Outlined.ToggleOn,
                                    contentDescription = null,
                                )
                            },
                            onClick = { menuOpen = false; onToggle() },
                        )
                        if (row.overridden) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chain_action_restore)) },
                                leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                                onClick = { menuOpen = false; onRestore() },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (row.userDefined) R.string.action_delete else R.string.chain_action_hide,
                                    ),
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LotusChip(
                    text = if (chain.testnet) {
                        stringResource(R.string.chain_testnet)
                    } else {
                        stringResource(R.string.chain_mainnet)
                    },
                    containerColor = if (chain.testnet) {
                        LotusTheme.semantic.warningContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (chain.testnet) {
                        LotusTheme.semantic.onWarningContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                LotusChip(text = familyLabel(chain))
                if (row.userDefined) {
                    LotusChip(
                        text = stringResource(R.string.chain_custom),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                } else if (row.overridden) {
                    LotusChip(
                        text = stringResource(R.string.chain_modified),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (!chain.supportsKeyDerivation) {
                    LotusChip(text = stringResource(R.string.chain_watch_only_only))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (chain is EvmChainConfig) {
                KeyValueLine(stringResource(R.string.chain_id_label), chain.chainId.toString())
            }
            KeyValueLine(stringResource(R.string.chain_coin_type), chain.coinType.toString())
            KeyValueLine(
                stringResource(R.string.chain_rpc),
                chain.rpcUrls.firstOrNull()?.let { hostOf(it) } ?: stringResource(R.string.label_none),
            )
            if (chain.rpcUrls.size > 1) {
                // 多条 RPC 是有意为之：单点故障时自动故障转移
                KeyValueLine(stringResource(R.string.chain_rpc_backup), "${chain.rpcUrls.size - 1}")
            }
            chain.explorerAddressUrl?.let {
                KeyValueLine(stringResource(R.string.chain_explorer), hostOf(it))
            }
        }
    }
}

/** 家族的中文标签（技术名词不翻译：EVM / UTXO / ed25519 / BLS，翻译反而看不懂）。 */
@Composable
private fun familyLabel(chain: ChainConfig): String = when (chain.family) {
    ChainConfig.Family.EVM -> "EVM"
    ChainConfig.Family.UTXO -> "UTXO"
    ChainConfig.Family.TRON -> "TRON"
    ChainConfig.Family.ED25519 -> "ed25519"
    ChainConfig.Family.FILECOIN -> "Filecoin"
    ChainConfig.Family.CHIA -> "BLS12-381"
}

@Composable
private fun KeyValueLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        MonoText(
            text = value,
            small = true,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun hostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").substringBefore('/')
