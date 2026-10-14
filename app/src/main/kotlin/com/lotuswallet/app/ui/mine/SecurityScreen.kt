package com.lotuswallet.app.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.security.BiometricHelper
import com.lotuswallet.app.security.VaultManager
import com.lotuswallet.app.security.findActivity
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SecretTextField
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.SwitchRow
import com.lotuswallet.app.ui.lock.LockViewModel
import com.lotuswallet.core.datastore.AppSettings
import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 安全设置里的非生物识别部分。 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    settings: SettingsRepository,
    private val vaultManager: VaultManager,
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 修改主密码：只重新包裹 DEK，已加密数据一条都不用动。 */
    fun changePassword(current: CharArray, next: CharArray, onDone: () -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { vaultManager.changePassword(current, next) }.getOrDefault(false)
            _message.value = if (ok) "主密码已修改" else "修改失败，请检查当前主密码"
            current.fill('\u0000')
            next.fill('\u0000')
            if (ok) onDone()
        }
    }

    fun notify(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }
}

/**
 * 安全设置。
 *
 * ## 生物识别是「真的参与解密」
 * 开启时会把当前 DEK 用 Android Keystore 里不可导出的硬件密钥重新包裹后落盘，
 * 而该密钥要求一次真实的用户认证才会放行解密（见 [BiometricHelper]）。
 * 因此这不是「指纹通过后用一个本地固定口令解锁」那种假实现。
 */
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
    lockViewModel: LockViewModel = hiltViewModel(),
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showChangePassword by remember { mutableStateOf(false) }

    val biometricUsable = remember { BiometricHelper.canAuthenticate(context) }
    val activity = context.findActivity() as? FragmentActivity

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    /** 开启生物识别：先要到一个「待授权」的加密 Cipher，再交给系统弹窗。 */
    fun startEnableBiometric() {
        val host = activity
        if (host == null) {
            viewModel.notify("当前环境不支持生物识别弹窗")
            return
        }
        scope.launch {
            val cipher = lockViewModel.prepareEncryptCipher()
            if (cipher == null) {
                viewModel.notify("无法创建硬件密钥，请重试")
                return@launch
            }
            BiometricHelper.authenticateForEncrypt(
                activity = host,
                cipher = cipher,
                title = context.getString(R.string.unlock_biometric_prompt_title),
                subtitle = context.getString(R.string.unlock_biometric_prompt_subtitle),
                negativeText = context.getString(R.string.action_cancel),
                onSuccess = { authorized ->
                    scope.launch {
                        val ok = lockViewModel.enableBiometric(authorized)
                        viewModel.notify(
                            context.getString(
                                if (ok) R.string.settings_biometric_enabled else R.string.settings_biometric_unavailable,
                            ),
                        )
                    }
                },
                onFailure = { viewModel.notify(it) },
            )
        }
    }

    DetailScaffold(
        title = stringResource(R.string.mine_security),
        onBack = onBack,
        snackbarHostState = snackbar,
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
                SectionCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_biometric),
                        subtitle = if (biometricUsable) {
                            stringResource(R.string.settings_biometric_desc)
                        } else {
                            stringResource(R.string.settings_biometric_unavailable)
                        },
                        checked = settings.biometricEnabled,
                        enabled = biometricUsable,
                        onCheckedChange = { wanted ->
                            if (wanted) {
                                startEnableBiometric()
                            } else {
                                scope.launch {
                                    lockViewModel.disableBiometric()
                                    viewModel.notify(context.getString(R.string.settings_biometric_disabled))
                                }
                            }
                        },
                    )
                }

                SectionCard(title = stringResource(R.string.settings_change_password)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_change_password_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showChangePassword = true }) {
                            Text(stringResource(R.string.settings_change_password))
                        }
                    }
                }

                SectionCard(title = stringResource(R.string.about_security_title)) {
                    Text(
                        text = stringResource(R.string.about_security_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            onConfirm = { current, next ->
                showChangePassword = false
                viewModel.changePassword(current, next) { }
            },
        )
    }
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (current: CharArray, next: CharArray) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val mismatch = next.isNotEmpty() && confirm.isNotEmpty() && next != confirm
    val tooShort = next.isNotEmpty() && next.length < MIN_PASSWORD_LENGTH
    val canSubmit = current.isNotEmpty() && next.length >= MIN_PASSWORD_LENGTH && next == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_change_password)) },
        text = {
            Column {
                SecretTextField(
                    value = current,
                    onValueChange = { current = it },
                    label = stringResource(R.string.settings_old_password),
                )
                Spacer(Modifier.height(10.dp))
                SecretTextField(
                    value = next,
                    onValueChange = { next = it },
                    label = stringResource(R.string.settings_new_password),
                    supportingText = if (tooShort) {
                        stringResource(R.string.setup_password_too_short)
                    } else {
                        stringResource(R.string.setup_password_hint)
                    },
                    isError = tooShort,
                )
                Spacer(Modifier.height(10.dp))
                SecretTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.setup_password_confirm),
                    isError = mismatch,
                    supportingText = if (mismatch) stringResource(R.string.setup_password_mismatch) else null,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.setup_password_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = LotusTheme.semantic.warning,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(current.toCharArray(), next.toCharArray()) },
                enabled = canSubmit,
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 与 [com.lotuswallet.app.ui.root.RootViewModel.MIN_PASSWORD_LENGTH] 保持一致。 */
private const val MIN_PASSWORD_LENGTH = 8
