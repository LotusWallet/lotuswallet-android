package com.lotuswallet.app.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lotuswallet.app.R
import com.lotuswallet.app.security.SecureScreen
import com.lotuswallet.app.ui.common.LanguageSwitcher
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SecretTextField
import com.lotuswallet.app.ui.common.SwitchRow
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.core.crypto.PasswordGenerator
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.component.StepIndicator
import com.lotuswallet.core.designsystem.theme.LotusTheme
import kotlinx.coroutines.launch

/**
 * 首次使用的完整流程：介绍 → 设置主密码。
 *
 * 介绍页允许「跳过」直接进入设置密码 —— 老用户换机时不需要再读一遍。
 * 密码设置这一步**不可跳过**，因为它是全部加密的前提。
 */
@Composable
fun OnboardingFlow(
    busy: Boolean,
    introCompleted: Boolean,
    onCreateVault: (password: CharArray, enableBiometricAfter: Boolean) -> Unit,
    onIntroFinished: () -> Unit,
) {
    // 进入本流程就意味着数据尚未加密，因此全程禁止截屏
    SecureScreen(enabled = true)

    var showSetup by rememberSaveable { mutableStateOf(introCompleted) }

    // 「跳过」与「开始使用」是两条路径、同一个结果：都进入设置主密码。
    // 早期版本里「开始使用」只写了偏好（onIntroFinished）却没切换页面，
    // 于是最后一页那个按钮看起来「点了没反应」——因为 showSetup 用的是
    // rememberSaveable 的初值，偏好变化不会把它重新推导一遍。
    val goToSetup: () -> Unit = { showSetup = true }

    if (showSetup) {
        SetupPasswordScreen(
            busy = busy,
            onCreate = onCreateVault,
        )
    } else {
        IntroPager(
            onFinish = {
                onIntroFinished()
                goToSetup()
            },
            onSkipToSetup = goToSetup,
        )
    }
}

private data class IntroFeature(
    val icon: ImageVector,
    val titleRes: Int,
    val descRes: Int,
)

private val IntroFeatures = listOf(
    IntroFeature(Icons.Outlined.Lock, R.string.onboarding_feature_custody, R.string.onboarding_feature_custody_desc),
    IntroFeature(Icons.Outlined.Shield, R.string.onboarding_feature_vault, R.string.onboarding_feature_vault_desc),
    IntroFeature(Icons.Outlined.Password, R.string.onboarding_feature_otp, R.string.onboarding_feature_otp_desc),
    IntroFeature(
        Icons.Outlined.AccountBalanceWallet,
        R.string.onboarding_feature_multichain,
        R.string.onboarding_feature_multichain_desc,
    ),
)

@Composable
private fun IntroPager(
    onFinish: () -> Unit,
    onSkipToSetup: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { IntroFeatures.size })
    val scope = rememberCoroutineScope()
    val pages = IntroFeatures

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 语言切换必须在引导页就可触达：用户若看不懂当前语言，
                // 不能要求他先看懂界面才能找到语言设置
                LanguageSwitcher()
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSkipToSetup) { Text(stringResource(R.string.action_skip)) }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                val feature = pages[index]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = feature.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = if (index == 0) stringResource(R.string.onboarding_title)
                        else stringResource(feature.titleRes),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (index == 0) stringResource(R.string.onboarding_subtitle)
                        else stringResource(feature.descRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            StepIndicator(
                total = pages.size,
                current = pagerState.currentPage,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))

            val isLast = pagerState.currentPage == pages.lastIndex
            PrimaryButton(
                text = if (isLast) stringResource(R.string.onboarding_start) else stringResource(R.string.action_next),
                onClick = {
                    if (isLast) onFinish() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
            )
        }
    }
}

@Composable
private fun SetupPasswordScreen(
    busy: Boolean,
    onCreate: (CharArray, Boolean) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var enableBiometric by rememberSaveable { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }

    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && password != confirm
    val canSubmit = password.length >= MIN_PASSWORD_LENGTH && password == confirm

    val errorText = when {
        touched && tooShort -> stringResource(R.string.setup_password_too_short)
        touched && mismatch -> stringResource(R.string.setup_password_mismatch)
        else -> null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.setup_password_create),
                    enabled = canSubmit && !busy,
                    onClick = {
                        touched = true
                        if (canSubmit) onCreate(password.toCharArray(), enableBiometric)
                    },
                )
            }
        },
    ) { padding ->
        ReadableContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .widthIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    LanguageSwitcher()
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.setup_password_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.setup_password_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))

                SecretTextField(
                    value = password,
                    onValueChange = { password = it; touched = true },
                    label = stringResource(R.string.setup_password_label),
                    supportingText = if (password.isEmpty()) stringResource(R.string.setup_password_hint) else errorText,
                    isError = tooShort && touched,
                    initiallyVisible = false,
                )
                Spacer(Modifier.height(12.dp))
                SecretTextField(
                    value = confirm,
                    onValueChange = { confirm = it; touched = true },
                    label = stringResource(R.string.setup_password_confirm),
                    isError = mismatch && touched,
                    supportingText = if (mismatch && touched) stringResource(R.string.setup_password_mismatch) else null,
                )

                if (password.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    PasswordStrengthBar(password)
                }

                Spacer(Modifier.height(16.dp))
                SwitchRow(
                    title = stringResource(R.string.setup_password_biometric_after),
                    checked = enableBiometric,
                    onCheckedChange = { enableBiometric = it },
                    modifier = Modifier.padding(horizontal = 0.dp),
                )

                Spacer(Modifier.height(16.dp))
                WarningCard(text = stringResource(R.string.setup_password_warning))

                if (busy) {
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.setup_password_working),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 密码强度条。四档，颜色从警示到安全。 */
@Composable
private fun PasswordStrengthBar(password: String) {
    val bits = PasswordGenerator.entropyBits(password)
    val (level, labelRes, color) = when {
        bits < 40 -> Triple(1, R.string.setup_password_strength_weak, LotusTheme.semantic.danger)
        bits < 60 -> Triple(2, R.string.setup_password_strength_fair, LotusTheme.semantic.warning)
        bits < 80 -> Triple(3, R.string.setup_password_strength_good, MaterialTheme.colorScheme.primary)
        else -> Triple(4, R.string.setup_password_strength_strong, LotusTheme.semantic.verified)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setup_password_strength),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelSmall, color = color)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            color = if (index < level) color else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

private const val MIN_PASSWORD_LENGTH = 8
