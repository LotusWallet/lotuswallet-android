package com.lotuswallet.app.ui.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotuswallet.app.navigation.MainShell
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.lock.OnboardingFlow
import com.lotuswallet.app.ui.lock.UnlockScreen
import com.lotuswallet.app.ui.theme.AppTheme
import com.lotuswallet.core.designsystem.component.LoadingBox

/**
 * 应用根组合项。
 *
 * 职责范围刻意收窄成三件事：
 * 1. 把偏好设置接到主题上；
 * 2. 按 [RootViewModel.stage] 决定显示引导 / 解锁 / 主界面；
 * 3. 处理自动锁定。
 *
 * 具体的业务页面全部在 [MainShell] 里，这样「锁屏」这件事与应用内容解耦 ——
 * 主界面可以在用户看不见的时候被整体拆掉重建，密钥状态却始终正确。
 */
@Composable
fun AppRoot(viewModel: RootViewModel = hiltViewModel()) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val biometricAvailable by viewModel.biometricAvailable.collectAsStateWithLifecycle()

    // 自动锁定：应用退到后台开始计时，回到前台时结算
    AutoLockEffect(autoLockMinutes = settings.autoLockMinutes) { elapsed ->
        viewModel.autoLockIfNeeded(elapsed, settings.autoLockMinutes)
    }

    AppTheme(settings = settings) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (stage) {
                    RootViewModel.Stage.Loading -> LoadingBox(
                        modifier = Modifier.fillMaxSize(),
                        label = null,
                    )

                    RootViewModel.Stage.Onboarding -> OnboardingFlow(
                        busy = busy,
                        introCompleted = settings.onboardingCompleted,
                        onCreateVault = viewModel::createVault,
                        onIntroFinished = viewModel::completeIntro,
                    )

                    RootViewModel.Stage.Locked -> UnlockScreen(
                        busy = busy,
                        biometricAvailable = biometricAvailable,
                        onUnlock = viewModel::unlock,
                    )

                    RootViewModel.Stage.Ready -> MainShell(onLock = viewModel::lock)
                }
            }
        }

        error?.let { message ->
            ConfirmDialog(
                title = "操作未完成",
                message = message,
                confirmText = "知道了",
                onConfirm = viewModel::clearError,
                onDismiss = viewModel::clearError,
                singleAction = true,
            )
        }
    }
}

/**
 * 自动锁定。
 *
 * 只在**退到后台**时计时，不做前台空闲计时 —— 前台空闲时用户可能正在
 * 抄写助记词或翻看地址，突然锁屏是纯粹的伤害。回到前台时如果超过阈值则锁定，
 * 阈值设为 0 表示「一离开就锁」。
 */
@Composable
private fun AutoLockEffect(
    autoLockMinutes: Int,
    onIdleElapsed: (Long) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback by rememberUpdatedState(onIdleElapsed)
    val currentMinutes by rememberUpdatedState(autoLockMinutes)

    DisposableEffect(lifecycleOwner) {
        var backgroundedAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    if (backgroundedAt > 0L) {
                        val elapsed = System.currentTimeMillis() - backgroundedAt
                        backgroundedAt = 0L
                        currentCallback(elapsed)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // currentMinutes 被读取一次，保证设置变更能触发重组
    @Suppress("UNUSED_EXPRESSION")
    currentMinutes
}
