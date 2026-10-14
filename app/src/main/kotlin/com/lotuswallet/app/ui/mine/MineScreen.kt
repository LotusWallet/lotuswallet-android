package com.lotuswallet.app.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.OtpRepository
import com.lotuswallet.app.data.VaultRepository
import com.lotuswallet.app.data.WalletRepository
import com.lotuswallet.core.datastore.AppSettings
import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusTopBar
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.component.SectionHeader
import com.lotuswallet.core.designsystem.component.SettingRow
import com.lotuswallet.core.network.chain.ChainRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 「我的」页。
 *
 * 只做两件事：给出一眼可见的「我持有什么」的概况，以及通往四类设置的门。
 * 资产总额刻意不显示 —— 当前版本未接入行情源，编造一个折合法币的数字
 * 比不显示更糟。
 */
@HiltViewModel
class MineViewModel @Inject constructor(
    walletRepository: WalletRepository,
    vaultRepository: VaultRepository,
    otpRepository: OtpRepository,
    private val settings: SettingsRepository,
    private val chains: ChainRepository,
) : ViewModel() {

    data class UiState(
        val walletCount: Int = 0,
        val accountCount: Int = 0,
        val vaultCount: Int = 0,
        val otpCount: Int = 0,
        val chainCount: Int = 0,
        val appSettings: AppSettings = AppSettings(),
    )

    val state: StateFlow<UiState> = combine(
        walletRepository.wallets(),
        walletRepository.accounts(),
        vaultRepository.items(),
        otpRepository.entries(),
        settings.settings,
    ) { wallets, accounts, vault, otp, appSettings ->
        UiState(
            walletCount = wallets.size,
            accountCount = accounts.size,
            vaultCount = vault.size,
            otpCount = otp.size,
            chainCount = chains.enabled.size,
            appSettings = appSettings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
}

@Composable
fun MineScreen(
    onOpenSettings: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAddressBook: () -> Unit,
    onOpenChains: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: MineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { LotusTopBar(title = stringResource(R.string.mine_title)) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        ReadableContent(maxWidth = 720.dp) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ---------------------------------------------------- 概况
                LotusCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatCell(stringResource(R.string.wallet_accounts_count, state.walletCount), stringResource(R.string.tab_wallet))
                        StatCell(state.accountCount.toString(), stringResource(R.string.wallet_address))
                        StatCell(state.vaultCount.toString(), stringResource(R.string.tab_vault))
                        StatCell(state.otpCount.toString(), stringResource(R.string.tab_otp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ---------------------------------------------------- 外观
                SectionHeader(title = stringResource(R.string.mine_appearance))
                LotusCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.settings_theme),
                        subtitle = themeLabel(state.appSettings),
                        icon = Icons.Outlined.Palette,
                        onClick = onOpenSettings,
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_hide_balances),
                        subtitle = stringResource(R.string.settings_hide_balances_desc),
                        icon = Icons.Outlined.Shield,
                        onClick = onOpenSettings,
                    )
                }

                // ---------------------------------------------------- 安全
                SectionHeader(title = stringResource(R.string.mine_security))
                LotusCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.settings_biometric),
                        subtitle = if (state.appSettings.biometricEnabled) {
                            stringResource(R.string.settings_biometric_enabled)
                        } else {
                            stringResource(R.string.settings_biometric_desc)
                        },
                        icon = Icons.Outlined.VerifiedUser,
                        onClick = onOpenSecurity,
                    )
                    SettingRow(
                        title = stringResource(R.string.settings_change_password),
                        subtitle = stringResource(R.string.settings_change_password_desc),
                        icon = Icons.Outlined.Security,
                        onClick = onOpenSecurity,
                    )
                }

                // ---------------------------------------------------- 数据
                SectionHeader(title = stringResource(R.string.mine_data))
                LotusCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.mine_backup),
                        subtitle = stringResource(R.string.backup_export_encrypted_desc),
                        icon = Icons.Outlined.Backup,
                        onClick = onOpenBackup,
                    )
                    SettingRow(
                        title = stringResource(R.string.mine_address_book),
                        icon = Icons.Outlined.Contacts,
                        onClick = onOpenAddressBook,
                    )
                    SettingRow(
                        title = stringResource(R.string.chain_management),
                        subtitle = stringResource(R.string.chain_enabled_count, state.chainCount),
                        icon = Icons.Outlined.Hub,
                        onClick = onOpenChains,
                    )
                }

                // ---------------------------------------------------- 关于
                SectionHeader(title = stringResource(R.string.mine_about))
                LotusCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.about_title),
                        subtitle = stringResource(R.string.about_slogan),
                        icon = Icons.Outlined.Info,
                        onClick = onOpenAbout,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun themeLabel(settings: AppSettings): String = stringResource(
    when (settings.themeMode) {
        com.lotuswallet.core.datastore.ThemeMode.SYSTEM -> R.string.settings_theme_system
        com.lotuswallet.core.datastore.ThemeMode.LIGHT -> R.string.settings_theme_light
        com.lotuswallet.core.datastore.ThemeMode.DARK -> R.string.settings_theme_dark
    },
)
