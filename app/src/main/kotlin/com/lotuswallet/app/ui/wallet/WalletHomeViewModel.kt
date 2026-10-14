package com.lotuswallet.app.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.data.ChainService
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.database.entity.AccountEntity
import com.lotuswallet.core.database.entity.WalletEntity
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.datastore.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页（钱包 Tab）。
 *
 * ## 余额为什么单独放一个 StateFlow
 * 钱包与账户来自 Room 的 Flow（结构变化时自动推送），余额来自网络（需要显式刷新）。
 * 把两者分开可以做到：新增一个地址时列表**立刻**出现，而它的余额随后异步补齐；
 * 也不至于因为某个节点超时而让整个列表闪一下。
 */
@HiltViewModel
class WalletHomeViewModel @Inject constructor(
    private val walletRepository: com.lotuswallet.app.data.WalletRepository,
    private val chainRepository: ChainRepository,
    private val chainService: ChainService,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    data class AccountRow(
        val account: AccountEntity,
        /** 链配置可能已被远端清单移除，此时为 null，界面退化为显示 chainId。 */
        val chain: ChainConfig?,
        val balance: ChainService.Balance?,
    )

    data class WalletGroup(
        val wallet: WalletEntity,
        val rows: List<AccountRow>,
    )

    data class UiState(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val groups: List<WalletGroup> = emptyList(),
        val enabledChains: List<ChainConfig> = emptyList(),
    ) {
        val isEmpty: Boolean get() = !loading && groups.isEmpty()
    }

    private val balances = MutableStateFlow<Map<String, ChainService.Balance>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val loadedOnce = MutableStateFlow(false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<UiState> = combine(
        walletRepository.wallets(),
        walletRepository.accounts(),
        balances,
        refreshing,
        loadedOnce,
    ) { wallets, accounts, balanceMap, isRefreshing, loaded ->
        val showTestnets = settingsRepository.current().showTestnets
        val enabled = chainRepository.current()
            .filter { it.enabled && (showTestnets || !it.testnet) }

        val groups = wallets.map { wallet ->
            WalletGroup(
                wallet = wallet,
                rows = accounts
                    .filter { it.walletId == wallet.id }
                    .map { account ->
                        AccountRow(
                            account = account,
                            chain = chainRepository.findById(account.chainId),
                            balance = balanceMap[account.id],
                        )
                    },
            )
        }
        UiState(
            loading = !loaded,
            refreshing = isRefreshing,
            groups = groups,
            enabledChains = enabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    /** 刷新全部账户余额（并发，单条失败不影响其它）。 */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                // 取一次快照即可：余额查询是命令式的，不需要响应式订阅
                val accounts = walletRepository.accounts().first()
                val results = accounts.map { account ->
                    async {
                        val chain = chainRepository.findById(account.chainId) ?: return@async null
                        account.id to chainService.balance(chain, account.address)
                    }
                }.awaitAll().filterNotNull().toMap()
                balances.value = results
            } catch (t: Throwable) {
                _message.value = t.toUserMessage()
            } finally {
                loadedOnce.value = true
                refreshing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun renameWallet(walletId: String, name: String) {
        viewModelScope.launch {
            runCatching { walletRepository.rename(walletId, name) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun deleteWallet(walletId: String) {
        viewModelScope.launch {
            runCatching { walletRepository.deleteWallet(walletId) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }
}
