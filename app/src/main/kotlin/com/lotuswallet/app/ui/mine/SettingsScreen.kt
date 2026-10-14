package com.lotuswallet.app.ui.mine

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.SwitchRow
import com.lotuswallet.core.datastore.AppSettings
import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.datastore.ThemeMode
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.component.SegmentedControl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 偏好设置。
 *
 * 这里所有选项都**立即生效并持久化**，没有「保存」按钮 ——
 * 设置项是低频、独立、可逆的动作，攒着一起提交只会带来「我改了没生效？」的困惑。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setTheme(mode: ThemeMode) = update { settings.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = update { settings.setDynamicColor(enabled) }

    fun setHideBalances(hide: Boolean) = update { settings.setHideBalances(hide) }

    fun setScreenshotProtection(enabled: Boolean) = update { settings.setScreenshotProtection(enabled) }

    fun setAutoLock(minutes: Int) = update { settings.setAutoLockMinutes(minutes) }

    fun setShowTestnets(show: Boolean) = update { settings.setShowTestnets(show) }

    fun setFiat(code: String) = update { settings.setFiatCurrency(code) }

    /**
     * 设置应用语言。null 表示跟随系统。
     *
     * 写入 DataStore 后，[com.lotuswallet.app.LotusWalletApp] 的监听会把它交给
     * `AppCompatDelegate.setApplicationLocales`，前台 Activity 会被自动重建，
     * 因此这里不需要任何额外的回调或重启提示。
     */
    fun setLanguage(tag: String?) = update { settings.setLanguage(tag) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }

    SnackbarEffect(snackbar, message) { message = null }

    DetailScaffold(
        title = stringResource(R.string.settings_title),
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
                // ------------------------------------------------ 外观
                SectionCard(title = stringResource(R.string.mine_appearance)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = stringResource(R.string.settings_theme_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.themeMode == mode,
                                    onClick = { viewModel.setTheme(mode) },
                                    label = { Text(themeName(mode)) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_desc),
                        checked = state.dynamicColor,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }

                // ------------------------------------------------ 隐私
                SectionCard(title = stringResource(R.string.about_privacy_title)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_hide_balances),
                        subtitle = stringResource(R.string.settings_hide_balances_desc),
                        checked = state.hideBalances,
                        onCheckedChange = viewModel::setHideBalances,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_screenshot),
                        subtitle = stringResource(R.string.settings_screenshot_desc),
                        checked = state.screenshotProtection,
                        onCheckedChange = viewModel::setScreenshotProtection,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_show_testnets),
                        subtitle = stringResource(R.string.settings_show_testnets_desc),
                        checked = state.showTestnets,
                        onCheckedChange = viewModel::setShowTestnets,
                    )
                }

                // ------------------------------------------------ 自动锁定
                SectionCard(title = stringResource(R.string.settings_autolock)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = stringResource(R.string.settings_autolock_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AUTO_LOCK_OPTIONS.forEach { minutes ->
                                FilterChip(
                                    selected = state.autoLockMinutes == minutes,
                                    onClick = { viewModel.setAutoLock(minutes) },
                                    label = {
                                        Text(
                                            if (minutes == 0) {
                                                stringResource(R.string.settings_autolock_immediately)
                                            } else {
                                                "$minutes 分钟"
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // ------------------------------------------------ 通用
                // 语言选择：跟随系统 / 简体中文 / English。
                // 用分段控件，因为这是**互斥的三选一**，且选项很短，平铺刚好最直观。
                SectionCard(title = stringResource(R.string.settings_language)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_language_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        SegmentedControl(
                            options = listOf(
                                stringResource(R.string.settings_language_system),
                                stringResource(R.string.settings_language_zh),
                                stringResource(R.string.settings_language_en),
                            ),
                            selected = when (state.language) {
                                null -> 0
                                "zh-CN" -> 1
                                "en" -> 2
                                else -> 0
                            },
                            onSelect = { index ->
                                viewModel.setLanguage(
                                    when (index) {
                                        1 -> "zh-CN"
                                        2 -> "en"
                                        else -> null
                                    },
                                )
                            },
                        )
                    }
                }

                SectionCard(title = stringResource(R.string.settings_fiat)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = stringResource(R.string.settings_fiat_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FIAT_OPTIONS.forEach { code ->
                                FilterChip(
                                    selected = state.fiatCurrency == code,
                                    onClick = { viewModel.setFiat(code) },
                                    label = { Text(code) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private val AUTO_LOCK_OPTIONS = listOf(0, 1, 5, 15, 30)
private val FIAT_OPTIONS = listOf("CNY", "USD", "EUR", "JPY")

@Composable
private fun themeName(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    },
)
