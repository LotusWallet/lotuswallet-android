package com.lotuswallet.app.ui.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.data.AccountDeriver
import com.lotuswallet.app.data.ChainService
import com.lotuswallet.app.data.TransferService
import com.lotuswallet.app.data.WalletRepository
import com.lotuswallet.app.util.Formatters
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.database.dao.TokenDao
import com.lotuswallet.core.database.entity.AccountEntity
import com.lotuswallet.core.database.entity.TokenEntity
import com.lotuswallet.core.database.entity.WalletEntity
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.network.chain.ChiaChainConfig
import com.lotuswallet.core.network.chain.Ed25519ChainConfig
import com.lotuswallet.core.network.chain.EvmChainConfig
import com.lotuswallet.core.network.chain.FilecoinChainConfig
import com.lotuswallet.core.network.chain.TronChainConfig
import com.lotuswallet.core.network.chain.UtxoChainConfig
import com.lotuswallet.core.network.chain.supportsSending
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger
import javax.inject.Inject

/**
 * 转账流程。
 *
 * ## 报价 — 确认 — 广播 三段式
 * 用户填完金额后**先报价**（估手续费 + 校验余额），确认页展示的是一条完整的
 * 「从哪 → 到哪 → 多少 → 手续费多少」；只有用户点了确认才真正签名。
 * 签名与广播之间不再有网络往返，避免手续费在确认期间变化导致用户付了预期外的钱。
 *
 * 费率固定为「报价时拿到的那一个」，确认页展示的就是最终值。
 */
@HiltViewModel
class SendViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val chainRepository: ChainRepository,
    private val chainService: ChainService,
    private val transferService: TransferService,
    private val tokenDao: TokenDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val accountId: String = savedStateHandle.get<String>("accountId").orEmpty()
    private val tokenId: String? = savedStateHandle.get<String>("tokenId")?.takeIf { it.isNotBlank() }

    data class UiState(
        val loading: Boolean = true,
        val account: AccountEntity? = null,
        val wallet: WalletEntity? = null,
        val chain: ChainConfig? = null,
        val balance: ChainService.Balance? = null,
        val token: TokenEntity? = null,
        val to: String = "",
        val amountText: String = "",
        val feeRateText: String = "",
        val quote: TransferService.Quote? = null,
        val quoting: Boolean = false,
        val sending: Boolean = false,
    ) {
        /** 实际使用的精度：代币转账用代币精度，否则用链原生精度。 */
        val decimals: Int get() = token?.decimals ?: chain?.decimals ?: 18

        val symbol: String get() = token?.symbol ?: chain?.symbol.orEmpty()

        val canSign: Boolean get() = wallet?.type != WalletType.WATCH_ONLY
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _broadcast = MutableStateFlow<TransferService.Broadcast?>(null)
    val broadcast: StateFlow<TransferService.Broadcast?> = _broadcast.asStateFlow()

    private var quoteJob: Job? = null

    init {
        viewModelScope.launch {
            val account = walletRepository.account(accountId)
            if (account == null) {
                _state.value = UiState(loading = false)
                _message.value = "地址不存在"
                return@launch
            }
            val chain = chainRepository.findById(account.chainId)
            val wallet = walletRepository.wallet(account.walletId)
            // 代币转账时（ERC-20）需要代币的精度与符号
            val token = tokenId?.let { runCatching { tokenDao.findById(it) }.getOrNull() }

            _state.value = _state.value.copy(
                account = account,
                wallet = wallet,
                chain = chain,
                token = token,
                loading = false,
            )
            if (chain != null) {
                val balance = chainService.balance(chain, account.address)
                _state.value = _state.value.copy(balance = balance)
            }
            if (chain is UtxoChainConfig) {
                _state.value = _state.value.copy(
                    feeRateText = chain.suggestedFeePerVByte?.toString().orEmpty(),
                )
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeBroadcast() {
        _broadcast.value = null
    }

    fun setTo(value: String) {
        _state.value = _state.value.copy(to = value.trim(), quote = null)
    }

    fun setAmount(value: String) {
        _state.value = _state.value.copy(amountText = value, quote = null)
    }

    fun setFeeRate(value: String) {
        _state.value = _state.value.copy(feeRateText = value.filter { c -> c.isDigit() }.take(8), quote = null)
    }

    /** 填满可用余额。手续费由用户在确认页核对，报价失败会明确提示。 */
    fun fillMax() {
        val state = _state.value
        val balance = state.balance?.takeIf { !it.unavailable } ?: return
        _state.value = state.copy(
            amountText = Formatters.formatUnits(balance.raw, state.decimals),
            quote = null,
        )
    }

    /** 输入变动后的一小段防抖，避免每敲一个字符就打一次 RPC。 */
    fun requestQuote(debounceMillis: Long = 600) {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            delay(debounceMillis)
            quoteNow()
        }
    }

    fun quoteNow() {
        viewModelScope.launch {
            val s = _state.value
            val chain = s.chain ?: return@launch
            val account = s.account ?: return@launch

            val amount = Formatters.parseUnits(s.amountText, s.decimals)
            if (amount == null || amount <= BigInteger.ZERO) {
                _state.value = s.copy(quote = null)
                return@launch
            }

            _state.value = s.copy(quoting = true)
            val quote = when (chain) {
                is EvmChainConfig -> transferService.quoteEvm(chain, account.address, s.to, amount, s.token?.contractAddress)
                is UtxoChainConfig -> {
                    val change = changeAddressOrNull()
                    if (change == null) {
                        TransferService.Quote(
                            BigInteger.ZERO, chain.decimals, chain.symbol,
                            error = "无法生成找零地址",
                        )
                    } else {
                        transferService.quoteUtxo(
                            chain, account.address, change, s.to, amount,
                            s.feeRateText.toLongOrNull(),
                        )
                    }
                }
                is TronChainConfig -> transferService.quoteTron(chain, account.address, s.to, amount, s.token?.contractAddress)
                is Ed25519ChainConfig, is FilecoinChainConfig, is ChiaChainConfig -> TransferService.Quote(
                    BigInteger.ZERO, chain.decimals, chain.symbol,
                    error = "${chain.name} 暂不支持在 App 内转账（当前仅做账户管理）",
                )
            }
            _state.value = _state.value.copy(quote = quote, quoting = false)
        }
    }

    /** 本地校验：地址格式、自转、余额是否覆盖「金额 + 手续费」。 */
    fun validate(): String? {
        val s = _state.value
        val chain = s.chain ?: return "链配置缺失"
        val account = s.account ?: return "账户缺失"
        if (s.to.isBlank()) return "请填写收款地址"
        if (!chainService.isValidAddress(chain, s.to)) return "收款地址格式不正确"
        if (chainService.isValidAddress(chain, account.address) &&
            normalize(chain, s.to) == normalize(chain, account.address)
        ) {
            return "不能转给自己的地址"
        }
        val amount = Formatters.parseUnits(s.amountText, s.decimals)
            ?: return "金额格式不正确"
        if (amount <= BigInteger.ZERO) return "金额必须大于 0"

        val quote = s.quote
        if (quote != null && quote.ok && s.token == null) {
            val balance = s.balance?.raw ?: BigInteger.ZERO
            if (balance < amount + quote.fee) return "余额不足（含手续费）"
        }
        if (quote != null && !quote.ok) return quote.error
        return null
    }

    private fun normalize(chain: ChainConfig, address: String): String = when (chain) {
        is EvmChainConfig -> com.lotuswallet.chain.evm.EvmAddress.normalize(address)
        is TronChainConfig -> com.lotuswallet.chain.tron.TronAddress.toHex41(address)
        else -> address.trim()
    }

    /** 签名并广播。 */
    fun send() {
        viewModelScope.launch {
            val s = _state.value
            val chain = s.chain ?: return@launch
            val account = s.account ?: return@launch
            val wallet = s.wallet ?: return@launch

            val error = validate()
            if (error != null) {
                _message.value = error
                return@launch
            }
            if (!s.canSign) {
                _message.value = "观察钱包无法签名"
                return@launch
            }
            // 新接入的链族目前只做账户管理，没有签名/广播实现 —— 明确挡住，
            // 而不是让用户填完一屏表单之后才在最后一步失败。
            if (!chain.supportsSending) {
                _message.value = "${chain.name} 暂不支持在 App 内转账（当前仅做账户管理）"
                return@launch
            }

            val amount = Formatters.parseUnits(s.amountText, s.decimals) ?: return@launch
            _state.value = s.copy(sending = true)
            try {
                val secret = walletRepository.secretOf(wallet)
                val privateKey = AccountDeriver.privateKey(
                    walletType = wallet.type,
                    secret = secret,
                    chain = chain,
                    index = account.orderIndex,
                )
                val result = when (chain) {
                    is EvmChainConfig -> transferService.sendEvm(
                        chain, privateKey, account.address, s.to, amount, s.token?.contractAddress,
                    )
                    is UtxoChainConfig -> {
                        val change = changeAddressOrNull() ?: error("无法生成找零地址")
                        transferService.sendUtxo(
                            chain, privateKey, account.address, change, s.to, amount,
                            s.feeRateText.toLongOrNull(),
                        )
                    }
                    is TronChainConfig -> transferService.sendTron(
                        chain, privateKey, account.address, s.to, amount, s.token?.contractAddress,
                    )

                    is Ed25519ChainConfig, is FilecoinChainConfig, is ChiaChainConfig -> error(
                        "${chain.name} 暂不支持在 App 内转账",
                    )
                }
                privateKey.fill(0)
                _broadcast.value = result
            } catch (t: Throwable) {
                _message.value = t.toUserMessage()
            } finally {
                _state.value = _state.value.copy(sending = false)
            }
        }
    }

    private suspend fun changeAddressOrNull(): String? {
        val s = _state.value
        val wallet = s.wallet ?: return null
        val chain = s.chain as? UtxoChainConfig ?: return null
        return runCatching {
            AccountDeriver.changeAddress(walletRepository.secretOf(wallet), chain, index = s.account?.orderIndex ?: 0)
        }.getOrNull()
    }
}
