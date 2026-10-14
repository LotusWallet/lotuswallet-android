package com.lotuswallet.app.ui.otp

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.BackupRepository
import com.lotuswallet.app.data.OtpRepository
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.util.rememberClipboard
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.crypto.OtpEngine
import com.lotuswallet.core.database.entity.OtpEntryEntity
import com.lotuswallet.core.database.entity.OtpType
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.CountdownRing
import com.lotuswallet.core.designsystem.component.EmptyState
import com.lotuswallet.core.designsystem.component.LoadingBox
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusTopBar
import com.lotuswallet.core.designsystem.component.MonoText
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 每 500ms 推一次时间 —— 每秒刷新会让倒计时在视觉上偶尔「跳两格」。 */
private val ticker = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(500)
    }
}

/**
 * 一次性口令（TOTP / HOTP）—— 本产品**必须保留**的两大模块之一。
 *
 * ## 性能取舍
 * 验证码每秒都在变，但**不能每秒去解密数据库**。
 * 因此这里在条目集合变化时一次性解出全部密钥放在内存里，
 * 每秒只对内存中的密钥做 HMAC 计算（微秒级）。
 *
 * ## 为什么不用 `System.currentTimeMillis()` 直接算
 * 时间被抽成 Flow 是为了让「同一时刻所有条目用同一个时间戳」——
 * 否则列表里不同卡片会在边界处出现半秒的错位，看起来像是「有的码错了」。
 */
@HiltViewModel
class OtpViewModel @Inject constructor(
    private val repository: OtpRepository,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    data class Row(
        val entity: OtpEntryEntity,
        val code: String,
        val remainingSeconds: Int,
        val progress: Float,
        val failed: Boolean = false,
    ) {
        /** 6 位按 3-3 分组、8 位按 4-4 分组，肉眼核对更快。 */
        val groupedCode: String
            get() = if (failed || code.isEmpty()) {
                "—"
            } else {
                code.chunked(if (code.length % 3 == 0) 3 else 4).joinToString(" ")
            }
    }

    data class UiState(
        val rows: List<Row> = emptyList(),
        val loading: Boolean = true,
        val query: String = "",
    )

    private val secrets = MutableStateFlow<List<OtpRepository.UnlockedEntry>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val query = MutableStateFlow("")

    private val now: StateFlow<Long> = ticker.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        System.currentTimeMillis(),
    )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<UiState> = combine(secrets, loading, query, now) { list, isLoading, q, timestamp ->
        val keyword = q.trim()
        val filtered = if (keyword.isEmpty()) {
            list
        } else {
            list.filter {
                it.entity.accountName.contains(keyword, ignoreCase = true) ||
                    it.entity.issuer?.contains(keyword, ignoreCase = true) == true
            }
        }
        UiState(
            rows = filtered.map { it.toRow(timestamp) },
            loading = isLoading,
            query = q,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            // 观察数据库：新增 / 删除 / 改算法后自动重新解密，不需要手动刷新
            repository.entries().collect {
                runCatching { repository.entriesWithSecrets() }
                    .onSuccess { list ->
                        secrets.value = list
                        loading.value = false
                    }
                    .onFailure {
                        _message.value = it.toUserMessage()
                        loading.value = false
                    }
            }
        }
    }

    private fun OtpRepository.UnlockedEntry.toRow(timestamp: Long): Row {
        val algorithm = OtpEngine.Algorithm.fromOtpAuth(entity.algorithm)
        return runCatching {
            if (entity.type == OtpType.HOTP) {
                Row(
                    entity = entity,
                    code = OtpEngine.hotp(secret, entity.counter, entity.digits, algorithm),
                    remainingSeconds = -1,
                    progress = 1f,
                )
            } else {
                val remaining = OtpEngine.remainingSeconds(timestamp, entity.periodSeconds)
                Row(
                    entity = entity,
                    code = OtpEngine.totp(
                        secret = secret,
                        timeMillis = timestamp,
                        periodSeconds = entity.periodSeconds,
                        digits = entity.digits,
                        algorithm = algorithm,
                    ),
                    remainingSeconds = remaining,
                    progress = remaining.toFloat() / entity.periodSeconds,
                )
            }
        }.getOrElse {
            Row(entity = entity, code = "", remainingSeconds = 0, progress = 0f, failed = true)
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggleFavorite(entity: OtpEntryEntity) {
        viewModelScope.launch {
            runCatching { repository.setFavorite(entity.id, !entity.favorite) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun delete(entity: OtpEntryEntity) {
        viewModelScope.launch {
            runCatching { repository.delete(entity.id) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    /** HOTP：推进计数器。每次「使用」后必须推进，否则会一直生成同一个码。 */
    fun advanceCounter(entity: OtpEntryEntity) {
        viewModelScope.launch {
            runCatching { repository.incrementCounter(entity.id) }
                .onSuccess { _message.value = "计数器已推进到 $it" }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    /** 把全部条目导出为 otpauth 链接文本（可导入任何认证器）。 */
    fun exportUris(onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { backupRepository.exportOtpAuthUris() }
                .onSuccess { uris ->
                    if (uris.isEmpty()) {
                        _message.value = "没有可导出的条目"
                    } else {
                        onReady(uris.joinToString("\n"))
                    }
                }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    /** 从 otpauth 链接 / Base32 密钥导入。 */
    fun importFromText(raw: String) {
        viewModelScope.launch {
            runCatching {
                val parsed = repository.parseOtpAuth(raw.trim())
                repository.save(
                    entryId = null,
                    issuer = parsed.issuer,
                    accountName = parsed.label,
                    secret = parsed.secret,
                    type = if (parsed.type.equals("hotp", ignoreCase = true)) OtpType.HOTP else OtpType.TOTP,
                    algorithm = parsed.algorithm.otpauthName,
                    digits = parsed.digits,
                    periodSeconds = parsed.periodSeconds,
                    counter = parsed.counter ?: 0L,
                )
            }.onSuccess {
                _message.value = "已导入 1 个条目"
            }.onFailure {
                _message.value = it.toUserMessage()
            }
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
fun OtpScreen(
    onNewEntry: () -> Unit,
    onEditEntry: (String) -> Unit,
    viewModel: OtpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val copy = rememberClipboard()
    val snackbar = remember { SnackbarHostState() }

    var pendingDelete by remember { mutableStateOf<OtpEntryEntity?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    Scaffold(
        topBar = {
            LotusTopBar(
                title = stringResource(R.string.tab_otp),
                actions = {
                    IconButton(onClick = { showImport = true }) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.otp_import_uri),
                        )
                    }
                    IconButton(onClick = { viewModel.exportUris { exportText = it } }) {
                        Icon(
                            Icons.Outlined.Upload,
                            contentDescription = stringResource(R.string.otp_export_uris),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewEntry,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.otp_new)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                LotusTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = stringResource(R.string.action_search),
                    placeholder = stringResource(R.string.otp_search_hint),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
            }
            Spacer(Modifier.height(8.dp))

            when {
                state.loading -> LoadingBox(modifier = Modifier.fillMaxWidth(), label = null)

                state.rows.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = stringResource(R.string.otp_empty_title),
                        description = stringResource(R.string.otp_empty_desc),
                        action = {
                            com.lotuswallet.app.ui.common.PrimaryButton(
                                text = stringResource(R.string.otp_new),
                                onClick = onNewEntry,
                                modifier = Modifier.width(200.dp),
                            )
                        },
                    )
                }

                else -> ReadableContent(maxWidth = 720.dp) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.rows, key = { it.entity.id }) { row ->
                            OtpCard(
                                row = row,
                                onCopy = {
                                    copy(row.code, true)
                                    viewModel.notify(context.getString(R.string.otp_copied))
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(row.entity) },
                                onEdit = { onEditEntry(row.entity.id) },
                                onDelete = { pendingDelete = row.entity },
                                onAdvanceCounter = { viewModel.advanceCounter(row.entity) },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { entity ->
        ConfirmDialog(
            title = stringResource(R.string.otp_delete_title),
            message = stringResource(R.string.otp_delete_confirm),
            destructive = true,
            onConfirm = {
                viewModel.delete(entity)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onConfirm = { raw ->
                showImport = false
                viewModel.importFromText(raw)
            },
        )
    }

    exportText?.let { text ->
        ExportDialog(
            text = text,
            onDismiss = { exportText = null },
            onCopy = {
                copy(text, true)
                exportText = null
                viewModel.notify(context.getString(R.string.action_copied))
            },
        )
    }
}

/**
 * 单条验证码卡片。
 *
 * 点整张卡片即复制 —— 这是最高频的动作，不该要求用户先去瞄一个小图标。
 */
@Composable
private fun OtpCard(
    row: OtpViewModel.Row,
    onCopy: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAdvanceCounter: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val entity = row.entity
    val isHotp = entity.type == OtpType.HOTP

    LotusCard(onClick = onCopy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 倒计时环：TOTP 才有意义
            if (isHotp) {
                AddressAvatar(
                    seed = entity.id,
                    label = (entity.issuer ?: entity.accountName).take(2),
                    size = 42.dp,
                )
            } else {
                CountdownRing(
                    progress = row.progress,
                    modifier = Modifier.size(42.dp),
                    indicatorColor = ringColor(row.remainingSeconds),
                    strokeWidth = 3.dp,
                ) {
                    Text(
                        text = row.remainingSeconds.coerceAtLeast(0).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.issuer ?: entity.accountName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(entity.accountName)
                        append(" · ")
                        append(if (isHotp) "HOTP" else "TOTP")
                        append(" · ")
                        append(entity.algorithm)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = row.groupedCode,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (row.failed) LotusTheme.semantic.danger else MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (entity.favorite) R.string.vault_unfavorite else R.string.vault_favorite,
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (entity.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menu = false
                            onToggleFavorite()
                        },
                    )
                    if (isHotp) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.otp_counter_increment)) },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            onClick = {
                                menu = false
                                onAdvanceCounter()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.action_delete),
                                color = LotusTheme.semantic.danger,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = LotusTheme.semantic.danger,
                            )
                        },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** 剩余时间不足 5 秒时转为警示色，提醒用户别在此时复制。 */
@Composable
private fun ringColor(remainingSeconds: Int) = when {
    remainingSeconds in 0..4 -> LotusTheme.semantic.danger
    remainingSeconds in 5..9 -> LotusTheme.semantic.warning
    else -> MaterialTheme.colorScheme.primary
}

/** 导入：粘贴 otpauth 链接，或扫描二维码。 */
@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val context = LocalContext.current
    var showScanner by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.otp_import_uri)) },
        text = {
            Column {
                LotusTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = stringResource(R.string.otp_secret_label),
                    placeholder = stringResource(R.string.otp_paste_hint),
                    singleLine = false,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        com.lotuswallet.app.util.Clipboard.read(context)?.let { value = it }
                    }) {
                        Text(stringResource(R.string.action_paste))
                    }
                    TextButton(onClick = { showScanner = true }) {
                        Text(stringResource(R.string.otp_scan))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (showScanner) {
        com.lotuswallet.app.ui.scan.QrScannerDialog(
            onDecoded = {
                showScanner = false
                onConfirm(it)
            },
            onDismiss = { showScanner = false },
            title = stringResource(R.string.otp_scan),
        )
    }
}

/** 导出：展示全部 otpauth 链接文本。 */
@Composable
private fun ExportDialog(
    text: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.otp_export_uris)) },
        text = {
            Column {
                Text(
                    text = "以下文本包含全部验证码密钥的明文。导入到其他认证器后请立即删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LotusTheme.semantic.warning,
                )
                Spacer(Modifier.height(8.dp))
                MonoText(text = text, small = true, maxLines = 8)
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.action_copy_all)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
