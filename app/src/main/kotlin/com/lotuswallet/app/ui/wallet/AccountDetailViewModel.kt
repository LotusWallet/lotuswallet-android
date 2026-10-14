package com.lotuswallet.app.ui.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.data.AccountDeriver
import com.lotuswallet.app.data.ChainService
import com.lotuswallet.app.data.WalletRepository
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.chain.evm.Erc20
import com.lotuswallet.chain.evm.EvmCallRequest
import com.lotuswallet.chain.evm.EvmRpc
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.database.entity.AccountEntity
import com.lotuswallet.core.database.entity.TokenEntity
import com.lotuswallet.core.database.entity.WalletEntity
import com.lotuswallet.core.database.entity.WalletSecret
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.database.dao.TokenDao
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.network.chain.EvmChainConfig
import com.lotuswallet.core.network.chain.UtxoChainConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigInteger
import javax.inject.Inject

/**
 * 某个地址的详情页。
 *
 * 这里同时承载「资产」与「交易记录」两块内容，因为用户点进一个地址后
 * 想看的就是这两件事。交易历史对 EVM / TRON 不可用（需要索引器），
 * 此时界面改为提供区块浏览器入口，而不是显示一个误导性的空列表。
 */
@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val chainRepository: ChainRepository,
    private val chainService: ChainService,
    private val evmRpc: EvmRpc,
    private val tokenDao: TokenDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val accountId: String = savedStateHandle.get<String>("accountId").orEmpty()

    data class TokenRow(val token: TokenEntity, val balance: BigInteger?)

    data class UiState(
        val loading: Boolean = true,
        val account: AccountEntity? = null,
        val wallet: WalletEntity? = null,
        val chain: ChainConfig? = null,
        val balance: ChainService.Balance? = null,
        val history: List<ChainService.HistoryItem> = emptyList(),
        val historySupported: Boolean = true,
        val tokens: List<TokenRow> = emptyList(),
        val canSign: Boolean = false,
        val secret: WalletSecret? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        load()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun load() {
        viewModelScope.launch {
            val account = walletRepository.account(accountId)
            if (account == null) {
                _state.value = UiState(loading = false)
                return@launch
            }
            val chain = chainRepository.findById(account.chainId)
            val wallet = walletRepository.wallet(account.walletId)
            _state.value = _state.value.copy(
                account = account,
                chain = chain,
                wallet = wallet,
                historySupported = chain?.let { chainService.supportsHistory(it) } ?: false,
                canSign = wallet?.type != WalletType.WATCH_ONLY,
                loading = false,
            )
            refresh()
        }
    }

    /** 刷新余额、历史与代币。任何一项失败都只影响它自己。 */
    fun refresh() {
        viewModelScope.launch {
            val account = _state.value.account ?: return@launch
            val chain = _state.value.chain
            if (chain == null) {
                _message.value = "该地址所属的链已不在配置中"
                return@launch
            }

            val balance = chainService.balance(chain, account.address)
            val history = runCatching { chainService.history(chain, account.address) }.getOrDefault(emptyList())
            val tokens = loadTokenBalances(chain, account)

            _state.value = _state.value.copy(balance = balance, history = history, tokens = tokens)
        }
    }

    /**
     * 代币余额。
     *
     * 只有 EVM 会真正去链上读 ERC-20 余额（一次 `eth_call`）。
     * TRON 的 TRC-20 需要走 TronGrid 的常量调用，UTXO 链没有代币概念，
     * 这两类暂时只列出已添加的代币而不显示余额 —— 显示一个编造的 0 比显示「—」更糟。
     */
    private suspend fun loadTokenBalances(chain: ChainConfig, account: AccountEntity): List<TokenRow> {
        val tokens = tokenDao.observeByChain(chain.id).first()
        if (tokens.isEmpty()) return emptyList()
        if (chain !is EvmChainConfig) return tokens.map { TokenRow(it, null) }

        return tokens.map { token ->
            val value = runCatching {
                evmRpc.call(
                    chain.rpcUrls,
                    EvmCallRequest(
                        to = token.contractAddress,
                        data = "0x" + Bytes.toHex(Erc20.balanceOfData(account.address)),
                    ),
                ).let(Erc20::decodeUint)
            }.getOrNull()
            TokenRow(token, value)
        }
    }

    /** 导出该钱包的密钥材料（明文）。 */
    fun revealSecret() {
        viewModelScope.launch {
            val wallet = _state.value.wallet ?: return@launch
            runCatching { walletRepository.secretOf(wallet) }
                .onSuccess { _state.value = _state.value.copy(secret = it) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun hideSecret() {
        _state.value = _state.value.copy(secret = null)
    }

    /** 在 [chain] 上为该钱包追加一个地址。 */
    fun addAccount(chainId: String) {
        viewModelScope.launch {
            val wallet = _state.value.wallet ?: return@launch
            val chain = chainRepository.findById(chainId) ?: return@launch
            runCatching { walletRepository.addAccount(wallet.id, chain) }
                .onSuccess { _message.value = "已新增地址" }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            val account = _state.value.account ?: return@launch
            runCatching { walletRepository.deleteAccount(account.id) }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun addToken(symbol: String, name: String, contract: String, decimals: Int) {
        viewModelScope.launch {
            val chain = _state.value.chain ?: return@launch
            runCatching {
                tokenDao.upsert(
                    TokenEntity(
                        id = com.lotuswallet.app.data.newId(),
                        chainId = chain.id,
                        contractAddress = contract,
                        symbol = symbol,
                        name = name,
                        decimals = decimals,
                        standard = "ERC20",
                    ),
                )
            }.onSuccess { _message.value = "代币已添加"; refresh() }
                .onFailure { _message.value = it.toUserMessage() }
        }
    }

    fun removeToken(tokenId: String) {
        viewModelScope.launch {
            runCatching { tokenDao.deleteById(tokenId) }.onSuccess { refresh() }
        }
    }

    /** 该地址对应的找零地址（UTXO 链转账时需要）。 */
    suspend fun changeAddressOrNull(): String? {
        val state = _state.value
        val wallet = state.wallet ?: return null
        val chain = state.chain as? UtxoChainConfig ?: return null
        return runCatching {
            AccountDeriver.changeAddress(walletRepository.secretOf(wallet), chain)
        }.getOrNull()
    }
}
