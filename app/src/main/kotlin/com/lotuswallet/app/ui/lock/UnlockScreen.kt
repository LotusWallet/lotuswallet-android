package com.lotuswallet.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.lotuswallet.app.R
import com.lotuswallet.app.security.BiometricHelper
import com.lotuswallet.app.security.SecureScreen
import com.lotuswallet.app.security.findActivity
import com.lotuswallet.app.ui.common.LanguageSwitcher
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SecretTextField
import com.lotuswallet.core.designsystem.component.ReadableContent
import kotlinx.coroutines.launch

/**
 * 解锁页。
 *
 * 两条通道：
 * - 主密码：任何情况下都可用，是唯一的「真相来源」；
 * - 生物识别：快捷方式，前提是硬件密钥仍有效。失效时（例如用户重新录入指纹）
 *   必须回到主密码，并且要给出**明确的原因**，否则用户会以为是 bug。
 *
 * 进入本页即视为「保险库已锁定」，所以全程禁止截屏。
 */
@Composable
fun UnlockScreen(
    busy: Boolean,
    biometricAvailable: Boolean,
    onUnlock: (CharArray) -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
) {
    SecureScreen(enabled = true)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by rememberSaveable { mutableStateOf("") }
    var localMessage by remember { mutableStateOf<String?>(null) }

    val activity = context.findActivity() as? FragmentActivity
    val canUseBiometric = biometricAvailable && activity != null && BiometricHelper.canAuthenticate(context)

    fun startBiometric() {
        val host = activity ?: return
        scope.launch {
            val cipher = viewModel.prepareDecryptCipher()
            if (cipher == null) {
                localMessage = context.getString(R.string.unlock_biometric_expired)
                return@launch
            }
            BiometricHelper.authenticateForDecrypt(
                activity = host,
                cipher = cipher,
                title = context.getString(R.string.unlock_biometric_prompt_title),
                subtitle = context.getString(R.string.unlock_biometric_prompt_subtitle),
                negativeText = context.getString(R.string.unlock_password_label),
                onSuccess = { authorized ->
                    scope.launch {
                        val ok = viewModel.completeBiometricUnlock(authorized)
                        if (!ok) {
                            localMessage = context.getString(R.string.unlock_biometric_expired)
                        }
                    }
                },
                onFailure = { reason -> localMessage = reason },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        ReadableContent(maxWidth = 420.dp) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.widthIn(max = 420.dp),
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(54.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.unlock_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(28.dp))

                    SecretTextField(
                        value = password,
                        onValueChange = { password = it; localMessage = null },
                        label = stringResource(R.string.unlock_password_label),
                        isError = localMessage != null,
                        supportingText = localMessage,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    )

                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(
                        text = stringResource(R.string.unlock_title),
                        enabled = password.isNotEmpty() && !busy,
                        onClick = { onUnlock(password.toCharArray()) },
                    )

                    if (canUseBiometric) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { startBiometric() },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = !busy,
                        ) {
                            Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.unlock_biometric))
                        }
                    }

                    if (busy) {
                        Spacer(Modifier.height(20.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }

        // 语言切换悬浮在锁屏右上角：看不懂当前语言的用户必须能在这里自救
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        ) {
            LanguageSwitcher()
        }
    }
}
