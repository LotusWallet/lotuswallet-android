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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.ChainService
import com.lotuswallet.app.data.newId
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.util.Formatters
import com.lotuswallet.app.util.rememberClipboard
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.database.dao.AddressBookDao
import com.lotuswallet.core.database.entity.AddressBookEntity
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.EmptyState
import com.lotuswallet.core.designsystem.component.LoadingBox
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusChip
import com.lotuswallet.core.designsystem.component.MonoText
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 地址簿。
 *
 * 地址在保存时**按链校验格式** —— 把 ERC-20 地址存进比特币条目里，
 * 转账时才发现不对，是最容易造成不可逆损失的一类错误。
 */
@HiltViewModel
class AddressBookViewModel @Inject constructor(
    private val dao: AddressBookDao,
    private val chainRepository: ChainRepository,
    private val chainService: ChainService,
) : ViewModel() {

    data class UiState(
        val entries: List<AddressBookEntity> = emptyList(),
        val chains: List<ChainConfig> = emptyList(),
        val loading: Boolean = true,
    )

    val state: StateFlow<UiState> = dao.observeAll()
        .map { UiState(entries = it, chains = chainRepository.enabled, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun chainOf(id: String?): ChainConfig? = id?.let { chainRepository.findById(it) }

    fun save(
        id: String?,
        name: String,
        address: String,
        chainId: String?,
        memo: String?,
        onSaved: () -> Unit,
    ) {
        val trimmedAddress = address.trim()
        if (name.isBlank()) {
            _message.value = "请填写名称"
            return
        }
        if (trimmedAddress.isEmpty()) {
            _message.value = "请填写地址"
            return
        }
        // 绑定了链就校验地址格式；没绑链时不做判断（用户可能存的是任何东西）
        val chain = chainOf(chainId)
        if (chain != null && !chainService.isValidAddress(chain, trimmedAddress)) {
            _message.value = "地址格式与 ${chain.name} 不匹配"
            return
        }
        viewModelScope.launch {
            runCatching {
                dao.upsert(
                    AddressBookEntity(
                        id = id ?: newId(),
                        name = name.trim(),
                        address = trimmedAddress,
                        chainId = chainId,
                        memo = memo?.trim()?.takeIf { it.isNotEmpty() },
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }.onSuccess {
                _message.value = "已保存"
                onSaved()
            }.onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { dao.deleteById(id) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun notify(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }
}

@Composable
fun AddressBookScreen(
    onBack: () -> Unit,
    viewModel: AddressBookViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val copy = rememberClipboard()
    val snackbar = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf<AddressBookEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AddressBookEntity?>(null) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    DetailScaffold(
        title = stringResource(R.string.mine_address_book),
        onBack = onBack,
        snackbarHostState = snackbar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.address_book_add)) },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(
                modifier = Modifier.fillMaxSize().padding(padding),
                label = null,
            )

            state.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = stringResource(R.string.address_book_empty),
                    description = stringResource(R.string.address_book_empty_desc),
                )
            }

            else -> ReadableContent(maxWidth = 720.dp) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        val chain = viewModel.chainOf(entry.chainId)
                        LotusCard(onClick = {
                            copy(entry.address, false)
                            viewModel.notify(context.getString(R.string.action_copied))
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AddressAvatar(seed = entry.address, label = entry.name.take(2), size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    MonoText(
                                        text = Formatters.shorten(entry.address, 12, 8),
                                        small = true,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    entry.memo?.takeIf { it.isNotBlank() }?.let { memo ->
                                        Text(
                                            text = memo,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (chain != null) LotusChip(text = chain.name)
                                IconButton(onClick = { editing = entry }) {
                                    Icon(
                                        Icons.Outlined.Edit,
                                        contentDescription = stringResource(R.string.action_edit),
                                    )
                                }
                                IconButton(onClick = { pendingDelete = entry }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.action_delete),
                                        tint = LotusTheme.semantic.danger,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val target = editing
        AddressEditorDialog(
            chains = state.chains,
            initial = target,
            onDismiss = {
                creating = false
                editing = null
            },
            onConfirm = { name, address, chainId, memo ->
                viewModel.save(target?.id, name, address, chainId, memo) {
                    creating = false
                    editing = null
                }
            },
        )
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.action_delete),
            message = stringResource(R.string.address_book_delete_confirm),
            destructive = true,
            onConfirm = {
                viewModel.delete(entry.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun AddressEditorDialog(
    chains: List<ChainConfig>,
    initial: AddressBookEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, address: String, chainId: String?, memo: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var memo by remember { mutableStateOf(initial?.memo.orEmpty()) }
    var chainId by remember { mutableStateOf(initial?.chainId) }
    var expanded by remember { mutableStateOf(false) }

    val chainLabel = chains.firstOrNull { it.id == chainId }?.name
        ?: stringResource(R.string.label_none)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.address_book_add else R.string.action_edit,
                ),
            )
        },
        text = {
            Column {
                LotusTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.address_book_name),
                )
                Spacer(Modifier.height(10.dp))
                LotusTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = stringResource(R.string.address_book_address),
                    singleLine = false,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.address_book_chain),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = chainLabel, style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            Icons.Outlined.ArrowDropDown,
                            contentDescription = stringResource(R.string.address_book_chain),
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.label_none)) },
                            onClick = {
                                chainId = null
                                expanded = false
                            },
                        )
                        chains.forEach { chain ->
                            DropdownMenuItem(
                                text = { Text(chain.name) },
                                onClick = {
                                    chainId = chain.id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LotusTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = stringResource(R.string.address_book_memo),
                    placeholder = stringResource(R.string.label_optional),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, address, chainId, memo) },
                enabled = name.isNotBlank() && address.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
