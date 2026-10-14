package com.lotuswallet.app.ui.vault

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.VaultRepository
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.app.util.rememberClipboard
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.database.entity.VaultFieldType
import com.lotuswallet.core.database.entity.VaultItemEntity
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.MonoText
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 保险库条目详情。
 *
 * ## 默认全部打码
 * 进入详情页时所有敏感字段都是 `••••`。这不是保守，而是必要的：
 * 用户在地铁里打开一条银行卡只是为了看卡号后四位，没必要把 CVV 也暴露出来。
 * 解密动作发生在「点击眼睛」的那一刻，而不是进入页面时。
 */
@HiltViewModel
class VaultDetailViewModel @Inject constructor(
    private val repository: VaultRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId").orEmpty()

    data class UiState(
        val item: VaultItemEntity? = null,
        val fields: List<VaultRepository.PlainField> = emptyList(),
        val loading: Boolean = true,
        val revealed: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _gone = MutableStateFlow(false)

    /** 条目已被删除（或本来就不存在），UI 应自行退出。 */
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            runCatching {
                val item = repository.item(itemId)
                if (item == null) {
                    _gone.value = true
                    return@runCatching
                }
                val plain = repository.plainFields(itemId)
                _state.value = _state.value.copy(item = item, fields = plain, loading = false)
            }.onFailure {
                _message.value = it.toUserMessage()
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun toggleReveal(fieldId: String) {
        val current = _state.value.revealed
        _state.value = _state.value.copy(
            revealed = if (fieldId in current) current - fieldId else current + fieldId,
        )
    }

    fun setAllRevealed(reveal: Boolean) {
        val all = _state.value.fields.map { it.id }.toSet()
        _state.value = _state.value.copy(revealed = if (reveal) all else emptySet())
    }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching {
                repository.setFavorite(item.id, !item.favorite)
                _state.value = _state.value.copy(item = item.copy(favorite = !item.favorite))
            }.onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { repository.deleteItem(itemId) }
                .onSuccess { _gone.value = true }
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
fun VaultDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: VaultDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val gone by viewModel.gone.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val copy = rememberClipboard()
    val snackbar = remember { SnackbarHostState() }

    var pendingDelete by remember { mutableStateOf(false) }
    var allRevealed by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    // 条目被删除后自动退出（可能是本页删的，也可能是别处删的）
    if (gone) {
        onBack()
        return
    }

    DetailScaffold(
        title = state.item?.title ?: stringResource(R.string.tab_vault),
        subtitle = state.item?.let { typeLabel(it.type) },
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { viewModel.toggleFavorite() }) {
                val favorite = state.item?.favorite == true
                Icon(
                    imageVector = if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (favorite) R.string.vault_unfavorite else R.string.vault_favorite,
                    ),
                    tint = if (favorite) LotusTheme.semantic.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.item?.let { item ->
                IconButton(onClick = { onEdit(item.id) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
            IconButton(onClick = { pendingDelete = true }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = LotusTheme.semantic.danger,
                )
            }
        },
    ) { padding ->
        ReadableContent(maxWidth = 720.dp) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "__banner__") {
                    WarningCard(
                        text = "字段值默认隐藏。点击眼睛图标显示，点击复制图标写入剪贴板（已标记为敏感内容，系统不会预览）。",
                    )
                }

                state.item?.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    item(key = "__subtitle__") {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.fields.isEmpty() && !state.loading) {
                    item(key = "__empty__") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.vault_no_fields),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(state.fields, key = { it.id }) { field ->
                    FieldCard(
                        label = field.label,
                        value = field.value,
                        sensitive = field.sensitive,
                        fieldType = field.type,
                        revealed = field.id in state.revealed,
                        onToggleReveal = { viewModel.toggleReveal(field.id) },
                        onCopy = {
                            copy(field.value, field.sensitive)
                            viewModel.notify(context.getString(R.string.vault_value_copied, field.label))
                        },
                    )
                }

                if (state.fields.size > 1) {
                    item(key = "__reveal_all__") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            TextButton(onClick = {
                                allRevealed = !allRevealed
                                viewModel.setAllRevealed(allRevealed)
                            }) {
                                Icon(
                                    imageVector = if (allRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(
                                        if (allRevealed) R.string.action_hide else R.string.action_show,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.vault_delete_title),
            message = stringResource(R.string.vault_delete_confirm),
            destructive = true,
            onConfirm = {
                pendingDelete = false
                viewModel.delete()
            },
            onDismiss = { pendingDelete = false },
        )
    }
}

/**
 * 单个字段卡片。
 *
 * 非敏感字段（姓名、发卡行）直接明文显示 —— 把它们也打码只会让用户
 * 每次都要多点一次眼睛，而它们本来就不构成风险。
 */
@Composable
private fun FieldCard(
    label: String,
    value: String,
    sensitive: Boolean,
    fieldType: VaultFieldType,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit,
) {
    LotusCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(18.dp),
                    )
                }
                if (sensitive) {
                    IconButton(onClick = onToggleReveal) {
                        Icon(
                            imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (revealed) R.string.action_hide else R.string.action_reveal,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            val shown = if (sensitive && !revealed) maskOf(value) else value
            when (fieldType) {
                VaultFieldType.MULTILINE, VaultFieldType.TOTP_SECRET -> MonoText(
                    text = shown,
                    small = true,
                    maxLines = 12,
                )

                VaultFieldType.URL, VaultFieldType.EMAIL, VaultFieldType.PHONE, VaultFieldType.NUMBER ->
                    MonoText(text = shown, small = true, maxLines = 3)

                else -> Text(
                    text = shown,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                )
            }
        }
    }
}

/** 打码：保留长度信息（用户能靠长度核对自己抄的对不对），但不泄露内容。 */
private fun maskOf(value: String): String =
    "•".repeat(value.length.coerceIn(6, 32))
