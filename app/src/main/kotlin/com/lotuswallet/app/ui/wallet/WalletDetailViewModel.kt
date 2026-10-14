package com.lotuswallet.app.ui.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.data.WalletRepository
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.database.entity.AccountEntity
import com.lotuswallet.core.database.entity.WalletEntity
import com.lotuswallet.core.database.entity.WalletSecret
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.datastore.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 钱包（一份密钥材料）级别的操作。 */
@HiltViewModel
class WalletDetailViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val chainRepository: ChainRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walletId: String = savedStateHandle.get<String>("walletId").orEmpty()

    data class Row(val account: AccountEntity, val chain: ChainConfig?)

    data class UiState(
        val wallet: WalletEntity? = null,
        val rows: List<Row> = emptyList(),
        val availableChains: List<ChainConfig> = emptyList(),
        val secret: WalletSecret? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        viewModelScope.launch {
            val showTestnets = settingsRepository.current().showTestnets
            _state.value = _state.value.copy(
                availableChains = chainRepository.current()
                    .filter { it.enabled && (showTestnets || !it.testnet) },
            )
            reload()
        }
    }

    private suspend fun reload() {
        val wallet = walletRepository.wallet(walletId) ?: return
        val accounts = walletRepository.accountsOf(walletId).first()
        _state.value = _state.value.copy(
            wallet = wallet,
            rows = accounts.map { Row(it, chainRepository.findById(it.chainId)) },
        )
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun rename(name: String) {
        viewModelScope.launch {
            runCatching {
                walletRepository.rename(walletId, name.trim().ifBlank { "我的钱包" })
                reload()
            }.onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun addAccount(chainId: String) {
        viewModelScope.launch {
            val chain = chainRepository.findById(chainId) ?: return@launch
            runCatching {
                walletRepository.addAccount(walletId, chain)
                reload()
            }.onSuccess { _message.value = "已新增地址" }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun revealSecret() {
        viewModelScope.launch {
            val wallet = _state.value.wallet ?: return@launch
            if (wallet.type == WalletType.WATCH_ONLY) return@launch
            runCatching { walletRepository.secretOf(wallet) }
                .onSuccess { _state.value = _state.value.copy(secret = it) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun hideSecret() {
        _state.value = _state.value.copy(secret = null)
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { walletRepository.deleteWallet(walletId) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }
}
