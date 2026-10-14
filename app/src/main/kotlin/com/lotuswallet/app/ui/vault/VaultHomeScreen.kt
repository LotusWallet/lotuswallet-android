package com.lotuswallet.app.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.VaultRepository
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.util.Formatters
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.database.entity.VaultItemEntity
import com.lotuswallet.core.database.entity.VaultItemType
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.EmptyState
import com.lotuswallet.core.designsystem.component.LoadingBox
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusTopBar
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 加密信息（保险库）首页。
 *
 * 这是本产品**必须保留**的模块之一。与密码管理器的差异在于：
 * 它同时容纳「加密货币密钥」与「现实证件」，因为这两类东西的用户心智是
 * 同一个：「我最不能丢的东西」。
 *
 * 搜索走 [VaultRepository.search]：标题命中走 SQL，值命中需在内存里解密比对。
 */
@HiltViewModel
class VaultHomeViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    data class UiState(
        val items: List<VaultItemEntity> = emptyList(),
        val loading: Boolean = true,
        val query: String = "",
        val filter: VaultItemType? = null,
        val searching: Boolean = false,
    ) {
        val isEmpty: Boolean get() = !loading && items.isEmpty()
    }

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow<VaultItemType?>(null)
    private val searching = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<UiState> = combine(
        vaultRepository.items(),
        query,
        filter,
        searching,
        loaded,
    ) { all, q, type, isSearching, isLoaded ->
        val base = if (type == null) all else all.filter { it.type == type }
        UiState(
            items = base,
            loading = !isLoaded,
            query = q,
            filter = type,
            searching = isSearching,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    // 值级搜索是解密操作，结果无法从 Room 的 Flow 推导，因此单独维护一份
    private val _searchResult = MutableStateFlow<List<VaultItemEntity>?>(null)

    val displayed: StateFlow<List<VaultItemEntity>> = combine(state, _searchResult) { s, result ->
        result ?: s.items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { loaded.value = true }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun setQuery(value: String) {
        query.value = value
        if (value.isBlank()) {
            _searchResult.value = null
            return
        }
        viewModelScope.launch {
            searching.value = true
            _searchResult.value = runCatching { vaultRepository.search(value) }
                .onFailure { _message.value = it.toUserMessage() }
                .getOrNull()
            searching.value = false
        }
    }

    fun setFilter(type: VaultItemType?) {
        filter.value = type
    }

    fun toggleFavorite(item: VaultItemEntity) {
        viewModelScope.launch {
            runCatching { vaultRepository.setFavorite(item.id, !item.favorite) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun delete(item: VaultItemEntity) {
        viewModelScope.launch {
            runCatching { vaultRepository.deleteItem(item.id) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }
}

@Composable
fun VaultHomeScreen(
    onOpenItem: (String) -> Unit,
    onNewItem: () -> Unit,
    onOpenGenerator: () -> Unit,
    viewModel: VaultHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items by viewModel.displayed.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<VaultItemEntity?>(null) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    Scaffold(
        topBar = {
            LotusTopBar(
                title = stringResource(R.string.tab_vault),
                actions = {
                    // 生成器放在这里：用户往往是「先要一个强密码，再去注册」，
                    // 不应该强迫他先建一条保险库条目才能用到生成器。
                    IconButton(onClick = onOpenGenerator) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = stringResource(R.string.vault_generator),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewItem,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.vault_new)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---------------------------------------------------- 搜索
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.lotuswallet.app.ui.common.LotusTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = stringResource(R.string.action_search),
                    placeholder = stringResource(R.string.vault_search_hint),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
            }
            Spacer(Modifier.height(8.dp))

            // ---------------------------------------------------- 分类
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filter == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text(stringResource(R.string.vault_tab_all), style = MaterialTheme.typography.labelMedium) },
                )
                VaultItemType.entries.forEach { type ->
                    FilterChip(
                        selected = state.filter == type,
                        onClick = { viewModel.setFilter(type) },
                        label = { Text(typeLabel(type), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (state.loading) {
                LoadingBox(modifier = Modifier.fillMaxWidth(), label = null)
                return@Column
            }

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.vault_empty_title),
                        description = stringResource(R.string.vault_empty_desc),
                        action = {
                            com.lotuswallet.app.ui.common.PrimaryButton(
                                text = stringResource(R.string.vault_new),
                                onClick = onNewItem,
                                modifier = Modifier.width(200.dp),
                            )
                        },
                    )
                }
                return@Column
            }

            ReadableContent(maxWidth = 720.dp) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        LotusCard(onClick = { onOpenItem(item.id) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AddressAvatar(
                                    seed = item.id,
                                    label = item.title.take(2),
                                    size = 38.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = buildString {
                                            append(typeLabel(item.type))
                                            item.subtitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = Formatters.formatTime(item.updatedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                IconButton(onClick = { viewModel.toggleFavorite(item) }) {
                                    Icon(
                                        imageVector = if (item.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                        contentDescription = stringResource(
                                            if (item.favorite) R.string.vault_unfavorite else R.string.vault_favorite,
                                        ),
                                        tint = if (item.favorite) LotusTheme.semantic.warning else MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        com.lotuswallet.app.ui.common.ConfirmDialog(
            title = stringResource(R.string.vault_delete_title),
            message = stringResource(R.string.vault_delete_confirm),
            destructive = true,
            onConfirm = { viewModel.delete(item); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 分类中文名。 */
@Composable
fun typeLabel(type: VaultItemType): String = stringResource(
    when (type) {
        VaultItemType.SECRET_KEY -> R.string.vault_type_secret_key
        VaultItemType.IDENTITY -> R.string.vault_type_identity
        VaultItemType.BANK_CARD -> R.string.vault_type_bank_card
        VaultItemType.WEBSITE_LOGIN -> R.string.vault_type_website_login
        VaultItemType.CUSTOM -> R.string.vault_type_custom
    },
)

/** 分类图标。 */
fun typeIcon(type: VaultItemType) = when (type) {
    VaultItemType.SECRET_KEY -> Icons.Outlined.Key
    VaultItemType.IDENTITY -> Icons.Outlined.Language
    VaultItemType.BANK_CARD -> Icons.Outlined.CreditCard
    VaultItemType.WEBSITE_LOGIN -> Icons.Outlined.Language
    VaultItemType.CUSTOM -> Icons.Outlined.Key
}
