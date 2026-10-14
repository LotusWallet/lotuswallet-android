package com.lotuswallet.app.data

import com.lotuswallet.app.util.Formatters
import com.lotuswallet.chain.bitcoin.EsploraApi
import com.lotuswallet.chain.bitcoin.EsploraTx
import com.lotuswallet.chain.evm.EvmRpc
import com.lotuswallet.chain.tron.TronAddress
import com.lotuswallet.chain.tron.TronRpc
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChiaChainConfig
import com.lotuswallet.core.network.chain.Ed25519ChainConfig
import com.lotuswallet.core.network.chain.Ed25519Variant
import com.lotuswallet.core.network.chain.EvmChainConfig
import com.lotuswallet.core.network.chain.FilecoinChainConfig
import com.lotuswallet.core.network.chain.TronChainConfig
import com.lotuswallet.core.network.chain.UtxoChainConfig
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 链上只读查询的**统一入口**。
 *
 * ## 为什么把三条链族的差异收敛在这里
 * 上层（ViewModel / UI）只调用 `balance(chain, address)`；至于这条链是走
 * JSON-RPC、Esplora REST 还是 TronGrid，由 [ChainConfig] 的具体子类决定。
 * 新增一条 EVM 链完全不需要改这个文件。
 *
 * ## 失败语义
 * 余额查询失败**不抛异常**，而是返回 [Balance.unavailable]。原因：
 * 一个用户可能有 8 条链的账户，某一个公共节点抽风不应该让整个首页变成错误页。
 */
@Singleton
class ChainService @Inject constructor(
    private val evmRpc: EvmRpc,
    private val esplora: EsploraApi,
    private val tronRpc: TronRpc,
) {

    /** 余额查询结果。 */
    data class Balance(
        val raw: BigInteger,
        val decimals: Int,
        val symbol: String,
        /** 额外的链特异性信息（例如 TRON 的质押量）。 */
        val note: String? = null,
        val unavailable: Boolean = false,
    ) {
        val formatted: String
            get() = if (unavailable) "—" else Formatters.formatUnits(raw, decimals)

        companion object {
            fun of(raw: BigInteger, decimals: Int, symbol: String, note: String? = null) =
                Balance(raw, decimals, symbol, note)

            fun unavailable(decimals: Int, symbol: String, note: String? = null) =
                Balance(BigInteger.ZERO, decimals, symbol, note, unavailable = true)
        }
    }

    /** 交易历史的一条。 */
    data class HistoryItem(
        val txId: String,
        /** 相对该地址的净变化（可为负）。最小单位。 */
        val delta: BigInteger,
        val decimals: Int,
        val symbol: String,
        val timestamp: Long,
        val confirmations: Int,
        val failed: Boolean = false,
    ) {
        val direction: Int get() = delta.signum()
    }

    // ---------------------------------------------------------------- 余额

    suspend fun balance(chain: ChainConfig, address: String): Balance = when (chain) {
        is EvmChainConfig -> evmBalance(chain, address)
        is UtxoChainConfig -> utxoBalance(chain, address)
        is TronChainConfig -> tronBalance(chain, address)
        // Solana / ICP / Monero / Filecoin / Chia：本版本只做账户管理，
        // 尚未接入各自的节点接口。余额显示为「—」而不是 0 ——
        // 把「查不到」画成「没有钱」是误导用户。
        is Ed25519ChainConfig, is FilecoinChainConfig, is ChiaChainConfig ->
            Balance.unavailable(chain.decimals, chain.symbol, "暂未接入节点查询")
    }

    private suspend fun evmBalance(chain: EvmChainConfig, address: String): Balance = try {
        Balance.of(evmRpc.balance(chain.rpcUrls, address), chain.decimals, chain.symbol)
    } catch (_: Throwable) {
        Balance.unavailable(chain.decimals, chain.symbol)
    }

    private suspend fun utxoBalance(chain: UtxoChainConfig, address: String): Balance = try {
        val stats = esplora.address(chain.rpcUrls, address)
        val note = if (stats.pendingBalance != 0L) {
            "待确认 ${Formatters.formatUnits(BigInteger.valueOf(stats.pendingBalance), chain.decimals)}"
        } else {
            null
        }
        Balance.of(BigInteger.valueOf(stats.totalBalance), chain.decimals, chain.symbol, note)
    } catch (_: Throwable) {
        Balance.unavailable(chain.decimals, chain.symbol)
    }

    private suspend fun tronBalance(chain: TronChainConfig, address: String): Balance = try {
        val account = tronRpc.account(chain.rpcUrls, TronAddress.toHex41(address))
        if (account == null) {
            // TRON 上「账户未激活」是合法状态，余额视为 0，而不是错误
            Balance.of(BigInteger.ZERO, chain.decimals, chain.symbol, "账户未激活")
        } else {
            val frozen = account.totalFrozenSun
            Balance.of(
                BigInteger.valueOf(account.balance),
                chain.decimals,
                chain.symbol,
                if (frozen > 0) "质押冻结 ${Formatters.formatUnits(BigInteger.valueOf(frozen), chain.decimals)}" else null,
            )
        }
    } catch (_: Throwable) {
        Balance.unavailable(chain.decimals, chain.symbol)
    }

    /** 批量查询（首页用）。返回的顺序与 [accounts] 一致。 */
    suspend fun balances(entries: List<Pair<ChainConfig, String>>): List<Balance> =
        entries.map { (chain, address) -> balance(chain, address) }

    // ---------------------------------------------------------------- 交易历史

    /**
     * 交易历史。
     *
     * 只有 UTXO 链能无凭据地拿到完整历史（Esplora 的 `/txs` 接口）。
     * EVM 与 TRON 的历史需要索引器（Etherscan / TronGrid 的账户交易接口都要求 API Key），
     * 本产品刻意不引入任何需要密钥的第三方服务，因此这两类链返回空列表，
     * UI 会改为提供「在区块浏览器中查看」的入口。
     */
    suspend fun history(chain: ChainConfig, address: String): List<HistoryItem> = when (chain) {
        is UtxoChainConfig -> utxoHistory(chain, address)
        else -> emptyList()
    }

    /** 该链是否支持 App 内直接查看历史。 */
    fun supportsHistory(chain: ChainConfig): Boolean = chain is UtxoChainConfig

    private suspend fun utxoHistory(chain: UtxoChainConfig, address: String): List<HistoryItem> {
        val tip = runCatching { esplora.tipHeight(chain.rpcUrls) }.getOrDefault(0L)
        val txs: List<EsploraTx> = runCatching { esplora.transactions(chain.rpcUrls, address) }
            .getOrDefault(emptyList())
        return txs.map { tx ->
            HistoryItem(
                txId = tx.txid,
                delta = BigInteger.valueOf(tx.netFor(address)),
                decimals = chain.decimals,
                symbol = chain.symbol,
                timestamp = tx.status.blockTime?.times(1000L) ?: 0L,
                confirmations = tx.confirmations(tip),
                failed = false,
            )
        }
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 校验 EVM 节点返回的 chainId 与配置一致。
     *
     * 连错链是真实存在的风险：把 BSC 的 RPC 配成 ETH 的地址，用户会以为资产没了。
     * 因此在「链管理」里提供一次显式校验。
     */
    suspend fun verifyEvmChainId(chain: EvmChainConfig): Long? =
        runCatching { evmRpc.chainId(chain.rpcUrls) }.getOrNull()

    /** 校验地址格式是否与该链匹配。 */
    fun isValidAddress(chain: ChainConfig, address: String): Boolean = when (chain) {
        is EvmChainConfig -> com.lotuswallet.chain.evm.EvmAddress.isValid(address)
        is UtxoChainConfig -> com.lotuswallet.chain.bitcoin.BitcoinAddress.isProbablyValid(address, chain)
        is TronChainConfig -> TronAddress.isValid(address)
        is Ed25519ChainConfig -> when (chain.variant) {
            Ed25519Variant.SOLANA -> com.lotuswallet.chain.alt.SolanaAddress.isValid(address)
            Ed25519Variant.ICP -> com.lotuswallet.chain.alt.IcpAddress.isValid(address)
            Ed25519Variant.MONERO -> com.lotuswallet.chain.alt.MoneroAddress.isValid(
                address,
                chain.addressPrefix ?: com.lotuswallet.chain.alt.MoneroAddress.MAINNET_PREFIX,
            )
        }

        is FilecoinChainConfig -> com.lotuswallet.chain.alt.FilecoinAddress.isValid(address, chain.network)
        is ChiaChainConfig -> com.lotuswallet.chain.alt.ChiaAddress.isValid(address, chain.hrp)
    }

    /** 地址不合法时抛出可直接展示的错误。 */
    fun requireValidAddress(chain: ChainConfig, address: String) {
        if (!isValidAddress(chain, address)) {
            throw AppException(AppError.InvalidInput("地址格式与 ${chain.name} 不匹配，请检查"))
        }
    }
}
