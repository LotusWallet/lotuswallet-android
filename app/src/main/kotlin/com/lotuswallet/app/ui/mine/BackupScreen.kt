package com.lotuswallet.app.ui.mine

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.BackupRepository
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SecretTextField
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.common.IoDispatcher
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.component.SettingRow
import com.lotuswallet.core.designsystem.theme.LotusTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 备份与恢复。
 *
 * ## 两条通道的语义差别（这是本页最需要说清的事）
 * - **加密备份**：导出的是密钥信封 + 全部密文行。文件离开主密码就是一串随机字节，
 *   可以放心放到 U 盘、网盘。换机后用它 + 主密码即可完整还原。
 * - **明文导出**：助记词、私钥、OTP 密钥、保险库字段值。**极高风险**，
 *   只用于「迁移到别的工具」或「从旧版搬过来」，用完必须销毁文件。
 *
 * 文件读写走 SAF（系统文件选择器），因此不需要申请任何存储权限，
 * 用户始终清楚文件落在哪里。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupRepository,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    data class UiState(val busy: Boolean = false)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun exportSnapshot(uri: Uri) = write(uri) { backup.snapshot() }

    fun exportOtpUris(uri: Uri) = write(uri) {
        val uris = backup.exportOtpAuthUris()
        if (uris.isEmpty()) "（暂无验证码条目）\n" else uris.joinToString("\n")
    }

    fun exportVaultPlaintext(uri: Uri) = write(uri) { backup.exportVaultPlaintext() }

    fun exportWalletSecrets(uri: Uri) = write(uri) { backup.exportWalletSecrets() }

    private fun write(uri: Uri, produce: suspend () -> String) {
        viewModelScope.launch {
            _state.value = UiState(busy = true)
            runCatching {
                val text = produce()
                withContext(io) {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("无法写入所选位置")
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                _message.value = "文件已保存"
            }.onFailure {
                _message.value = it.toUserMessage()
            }
            _state.value = UiState(busy = false)
        }
    }

    fun restore(uri: Uri, password: CharArray, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.value = UiState(busy = true)
            runCatching {
                val raw = withContext(io) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选文件")
                    stream.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                backup.restore(raw, password)
            }.onSuccess { summary ->
                val text = "恢复完成：钱包 ${summary.wallets}、地址 ${summary.accounts}、" +
                    "密库条目 ${summary.vaultItems}、验证码 ${summary.otpEntries}"
                _message.value = text
                onDone(text)
            }.onFailure {
                _message.value = it.toUserMessage()
            }
            password.fill('\u0000')
            _state.value = UiState(busy = false)
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
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var pendingExport by remember { mutableStateOf<ExportKind?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    // ------------------------------------------------------------ 导出通道
    val encryptedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportSnapshot(it) } }

    val otpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.exportOtpUris(it) } }

    val vaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.exportVaultPlaintext(it) } }

    val secretsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.exportWalletSecrets(it) } }

    // ------------------------------------------------------------ 恢复通道
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingRestoreUri = uri }

    DetailScaffold(
        title = stringResource(R.string.backup_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 16.dp).size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        },
    ) { padding ->
        ReadableContent(maxWidth = 720.dp) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WarningCard(
                    text = "备份文件请自行保管。加密备份可以放在网盘；明文导出请只存在离线介质上，用完立即销毁。",
                )

                // ------------------------------------------------ 导出
                SectionCard(title = stringResource(R.string.backup_export_section)) {
                    SettingRow(
                        title = stringResource(R.string.backup_export_encrypted),
                        subtitle = stringResource(R.string.backup_export_encrypted_desc),
                        onClick = { encryptedLauncher.launch(fileName("backup", "json")) },
                    )
                    SettingRow(
                        title = stringResource(R.string.backup_export_otp),
                        subtitle = stringResource(R.string.backup_export_otp_desc),
                        onClick = { otpLauncher.launch(fileName("otp", "txt")) },
                    )
                    SettingRow(
                        title = stringResource(R.string.backup_export_vault),
                        subtitle = stringResource(R.string.backup_export_vault_desc),
                        onClick = { pendingExport = ExportKind.VAULT },
                    )
                    SettingRow(
                        title = stringResource(R.string.backup_export_wallets),
                        subtitle = stringResource(R.string.backup_export_wallets_desc),
                        onClick = { pendingExport = ExportKind.WALLETS },
                    )
                }

                // ------------------------------------------------ 恢复
                SectionCard(title = stringResource(R.string.backup_restore_section)) {
                    SettingRow(
                        title = stringResource(R.string.backup_restore),
                        subtitle = stringResource(R.string.backup_restore_desc),
                        onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                }

                WarningCard(
                    text = stringResource(R.string.backup_restore_overwrite_warning),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ------------------------------------------------ 明文导出二次确认
    pendingExport?.let { kind ->
        val (title, body) = when (kind) {
            ExportKind.VAULT -> R.string.backup_export_vault to R.string.backup_export_vault_desc
            ExportKind.WALLETS -> R.string.backup_export_wallets to R.string.wallet_export_confirm_desc
        }
        ConfirmDialog(
            title = stringResource(title),
            message = stringResource(body),
            confirmText = stringResource(R.string.action_export),
            destructive = true,
            onConfirm = {
                pendingExport = null
                when (kind) {
                    ExportKind.VAULT -> vaultLauncher.launch(fileName("vault-plain", "txt"))
                    ExportKind.WALLETS -> secretsLauncher.launch(fileName("wallets-secret", "txt"))
                }
            },
            onDismiss = { pendingExport = null },
        )
    }

    // ------------------------------------------------ 恢复：输入备份主密码
    pendingRestoreUri?.let { uri ->
        RestorePasswordDialog(
            onDismiss = { pendingRestoreUri = null },
            onConfirm = { password ->
                val target = uri
                pendingRestoreUri = null
                viewModel.restore(target, password) { }
            },
        )
    }
}

private enum class ExportKind { VAULT, WALLETS }

/** 文件名带时间戳，避免多次导出互相覆盖，也便于用户分辨哪份是最新的。 */
private fun fileName(prefix: String, extension: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "lotuswallet-$prefix-$stamp.$extension"
}

@Composable
private fun RestorePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_restore_overwrite_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = LotusTheme.semantic.danger,
                )
                Spacer(Modifier.height(10.dp))
                SecretTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.backup_restore_password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password.toCharArray()) },
                enabled = password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
