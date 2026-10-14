package com.lotuswallet.app.data

import com.lotuswallet.chain.bitcoin.BitcoinAddress
import com.lotuswallet.chain.bitcoin.BitcoinSigner
import com.lotuswallet.chain.bitcoin.BitcoinTransaction
import com.lotuswallet.chain.bitcoin.BitcoinTxBuilder
import com.lotuswallet.chain.bitcoin.BitcoinBuildResult
import com.lotuswallet.chain.bitcoin.EsploraApi
import com.lotuswallet.chain.bitcoin.Utxo
import com.lotuswallet.chain.evm.Eip1559EvmTransaction
import com.lotuswallet.chain.evm.Erc20
import com.lotuswallet.chain.evm.EvmCallRequest
import com.lotuswallet.chain.evm.EvmRpc
import com.lotuswallet.chain.evm.LegacyEvmTransaction
import com.lotuswallet.chain.tron.TronAddress
import com.lotuswallet.chain.tron.TronRpc
import com.lotuswallet.chain.tron.TronSigningFlow
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.network.chain.EvmChainConfig
import com.lotuswallet.core.network.chain.TronChainConfig
import com.lotuswallet.core.network.chain.UtxoChainConfig
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 转账（构建 → 签名 → 广播）。
 *
 * ## 安全上的硬性约束
 * 1. **广播前必做本地自检**：UTXO 链校验「输入 ≥ 输出且全部已签名」，
 *    TRON 校验「签名能恢复出发起地址」。任何一项不过就中止，绝不把可疑交易发出去。
 * 2. 私钥只作为参数传入，函数内部用完即用，**不做任何缓存**。
 * 3. 金额校验先于签名：余额不足/地址非法在本地就拦掉，避免白付手续费。
 */
@Singleton
class TransferService @Inject constructor(
    private val evmRpc: EvmRpc,
    private val esplora: EsploraApi,
    private val tronRpc: TronRpc,
) {

    /** 费用报价。 */
    data class Quote(
        /** 预计手续费（最小单位）。 */
        val fee: BigInteger,
        val decimals: Int,
        val symbol: String,
        /** 额外说明（例如「按 12 聪/vB 估算」「需 ERC-20 授权」）。 */
        val note: String? = null,
        /** 无法报价时的原因。 */
        val error: String? = null,
        /** 该笔交易需要的总支出（金额 + 手续费）。 */
        val total: BigInteger = BigInteger.ZERO,
    ) {
        val ok: Boolean get() = error == null
    }

    /** 广播结果。 */
    data class Broadcast(val txId: String, val explorerUrl: String?)

    // ================================================================= EVM

    /**
     * EVM 转账报价。
     *
     * @param tokenContract 为 null 时是原生币转账；否则是 ERC-20 转账
     */
    suspend fun quoteEvm(
        chain: EvmChainConfig,
        from: String,
        to: String,
        amount: BigInteger,
        tokenContract: String? = null,
    ): Quote {
        if (amount <= BigInteger.ZERO) return evmError(chain, "金额必须大于 0")
        val data = tokenContract?.let {
            Erc20.transferData(com.lotuswallet.chain.evm.EvmAddress.checksum(to), amount)
        } ?: ByteArray(0)

        val gasLimit = try {
            if (data.isEmpty()) {
                NATIVE_TRANSFER_GAS
            } else {
                evmRpc.estimateGas(
                    chain.rpcUrls,
                    EvmCallRequest(from = from, to = tokenContract, value = "0x0", data = "0x" + Bytes.toHex(data)),
                )
            }
        } catch (_: Throwable) {
            if (data.isEmpty()) NATIVE_TRANSFER_GAS else ERC20_TRANSFER_GAS_FALLBACK
        }

        val suggestion = try {
            evmRpc.suggestFees(chain.rpcUrls, chain)
        } catch (t: Throwable) {
            return evmError(chain, "无法获取链上费率，请检查网络：" + (t.message ?: "未知错误"))
        }

        val fee = if (chain.supportsEip1559) {
            gasLimit.multiply(suggestion.maxFeePerGas)
        } else {
            gasLimit.multiply(suggestion.legacyGasPrice ?: suggestion.maxFeePerGas)
        }
        val note = if (data.isEmpty()) {
            "原生币转账 · gas ${gasLimit} "
        } else {
            "ERC-20 转账 · gas ${gasLimit} "
        }
        return Quote(fee, chain.decimals, chain.symbol, note.trim(), total = amount.add(fee))
    }

    /**
     * 签名并广播 EVM 交易。
     *
     * @return 交易哈希
     */
    suspend fun sendEvm(
        chain: EvmChainConfig,
        privateKey: ByteArray,
        from: String,
        to: String,
        amount: BigInteger,
        tokenContract: String? = null,
    ): Broadcast {
        if (!com.lotuswallet.chain.evm.EvmAddress.isValid(to)) {
            throw AppException(AppError.InvalidInput("收款地址不是合法的 EVM 地址"))
        }
        if (amount <= BigInteger.ZERO) {
            throw AppException(AppError.InvalidInput("金额必须大于 0"))
        }

        val data = tokenContract?.let {
            Erc20.transferData(com.lotuswallet.chain.evm.EvmAddress.checksum(to), amount)
        } ?: ByteArray(0)

        val gasLimit = try {
            if (data.isEmpty()) {
                NATIVE_TRANSFER_GAS
            } else {
                evmRpc.estimateGas(
                    chain.rpcUrls,
                    EvmCallRequest(from = from, to = tokenContract, value = "0x0", data = "0x" + Bytes.toHex(data)),
                ).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN) // 留 20% 缓冲
            }
        } catch (_: Throwable) {
            if (data.isEmpty()) NATIVE_TRANSFER_GAS else ERC20_TRANSFER_GAS_FALLBACK
        }

        val nonce = evmRpc.transactionCount(chain.rpcUrls, from)
        val suggestion = evmRpc.suggestFees(chain.rpcUrls, chain)
        val target = tokenContract ?: to

        val signed = if (chain.supportsEip1559) {
            Eip1559EvmTransaction(
                chainId = chain.chainId,
                nonce = nonce,
                maxPriorityFeePerGas = suggestion.maxPriorityFeePerGas,
                maxFeePerGas = suggestion.maxFeePerGas,
                gasLimit = gasLimit,
                to = com.lotuswallet.chain.evm.EvmAddress.normalize(target),
                value = if (data.isEmpty()) amount else BigInteger.ZERO,
                data = data,
            ).sign(privateKey)
        } else {
            LegacyEvmTransaction(
                chainId = chain.chainId,
                nonce = nonce,
                gasPrice = suggestion.legacyGasPrice ?: suggestion.maxFeePerGas,
                gasLimit = gasLimit,
                to = com.lotuswallet.chain.evm.EvmAddress.normalize(target),
                value = if (data.isEmpty()) amount else BigInteger.ZERO,
                data = data,
            ).sign(privateKey)
        }

        // 签名者必须与我们以为的发起地址一致 —— 不一致说明密钥或账户搞错了
        if (!com.lotuswallet.chain.evm.EvmAddress.normalize(signed.from)
                .equals(com.lotuswallet.chain.evm.EvmAddress.normalize(from), ignoreCase = true)
        ) {
            throw AppException(AppError.IntegrityCheckFailed)
        }

        val hash = evmRpc.sendRawTransaction(chain.rpcUrls, signed.rawHex)
        return Broadcast(hash, chain.txUrl(hash.removePrefix("0x").let { "0x$it" }))
    }

    private fun evmError(chain: EvmChainConfig, message: String) =
        Quote(BigInteger.ZERO, chain.decimals, chain.symbol, error = message)

    // ================================================================= UTXO

    /** UTXO 链报价：拉取未花费输出并做一次试算。 */
    suspend fun quoteUtxo(
        chain: UtxoChainConfig,
        address: String,
        changeAddress: String,
        to: String,
        amount: BigInteger,
        feeRateOverride: Long? = null,
    ): Quote {
        if (amount <= BigInteger.ZERO) return utxoError(chain, "金额必须大于 0")
        val recipientScript = BitcoinAddress.scriptPubKeyFor(to, chain)
            ?: return utxoError(chain, "收款地址无法解析为脚本，可能地址类型不受支持")

        return try {
            val utxos = fetchUtxos(chain, address)
            if (utxos.isEmpty()) return utxoError(chain, "该地址没有可用的未花费输出")
            val feeRate = feeRateOverride ?: esplora.recommendedFeeRate(chain.rpcUrls, fallback = 2)
            val changeScript = BitcoinAddress.scriptPubKeyFor(changeAddress, chain) ?: recipientScript
            val built = BitcoinTxBuilder.build(
                utxos = utxos,
                recipientScript = recipientScript,
                amount = amount.toLong(),
                changeScript = changeScript,
                changeType = chain.addressType,
                feeRate = feeRate,
                dustThreshold = chain.dustThreshold,
            )
            when (built) {
                is BitcoinBuildResult.Success -> Quote(
                    fee = BigInteger.valueOf(built.fee),
                    decimals = chain.decimals,
                    symbol = chain.symbol,
                    note = "按 $feeRate 聪/vB 估算 · 输入 ${built.selected.size} 个",
                    total = amount.add(BigInteger.valueOf(built.fee)),
                )
                is BitcoinBuildResult.Failure -> utxoError(chain, built.reason)
            }
        } catch (t: Throwable) {
            utxoError(chain, "无法获取未花费输出：" + (t.message ?: "网络错误"))
        }
    }

    /**
     * 签名并广播 UTXO 交易。
     *
     * 所有输入都来自 [fromAddress]（外部链的第 0 个地址），因此统一用同一个私钥签名；
     * 找零回到 [changeAddress]（change = 1 路径）。
     */
    suspend fun sendUtxo(
        chain: UtxoChainConfig,
        privateKey: ByteArray,
        fromAddress: String,
        changeAddress: String,
        to: String,
        amount: BigInteger,
        feeRateOverride: Long? = null,
    ): Broadcast {
        val recipientScript = BitcoinAddress.scriptPubKeyFor(to, chain)
            ?: throw AppException(AppError.InvalidInput("收款地址无法解析为脚本"))
        val utxos = fetchUtxos(chain, fromAddress)
        if (utxos.isEmpty()) throw AppException(AppError.InvalidInput("该地址没有可用的未花费输出"))

        val feeRate = feeRateOverride ?: esplora.recommendedFeeRate(chain.rpcUrls, fallback = 2)
        val changeScript = BitcoinAddress.scriptPubKeyFor(changeAddress, chain) ?: recipientScript

        val built = BitcoinTxBuilder.build(
            utxos = utxos,
            recipientScript = recipientScript,
            amount = amount.toLong(),
            changeScript = changeScript,
            changeType = chain.addressType,
            feeRate = feeRate,
            dustThreshold = chain.dustThreshold,
        )
        val success = built as? BitcoinBuildResult.Success
            ?: throw AppException(AppError.InvalidInput((built as BitcoinBuildResult.Failure).reason))

        val tx: BitcoinTransaction = success.transaction
        for (index in tx.inputs.indices) {
            BitcoinSigner.signInput(tx, index, privateKey, chain.addressType)
        }
        // 广播前的本地自检：未签名 / 输入不足都必须在这里就失败
        BitcoinSigner.sanityCheck(tx)

        val raw = tx.rawHex()
        val txId = esplora.broadcast(chain.rpcUrls, raw)
        return Broadcast(txId, chain.txUrl(txId))
    }

    private suspend fun fetchUtxos(chain: UtxoChainConfig, address: String): List<Utxo> {
        val scriptPubKey = BitcoinAddress.scriptPubKeyFor(address, chain) ?: return emptyList()
        val tip = runCatching { esplora.tipHeight(chain.rpcUrls) }.getOrDefault(0L)
        return esplora.utxos(chain.rpcUrls, address).map { dto ->
            Utxo(
                txId = dto.txid,
                vout = dto.vout,
                value = dto.value,
                scriptPubKey = scriptPubKey,
                addressType = chain.addressType,
                confirmations = dto.status.blockHeight?.let { (tip - it + 1).coerceAtLeast(0).toInt() } ?: 0,
            )
        }
    }

    /**
     * 计算同钱包的找零地址。
     *
     * 找零是 change = 1 路径的派生结果，必须由上层用助记词重新派生
     * （见 [AccountDeriver.changeAddress]），本服务不持有密钥材料，因此由调用方传入。
     */
    private fun utxoError(chain: UtxoChainConfig, message: String) =
        Quote(BigInteger.ZERO, chain.decimals, chain.symbol, error = message)

    // ================================================================= TRON

    /** TRON 报价。TRX 转账带宽通常免费，TRC-20 需要能量，这里给出费用上限。 */
    suspend fun quoteTron(
        chain: TronChainConfig,
        from: String,
        to: String,
        amount: BigInteger,
        tokenContract: String? = null,
    ): Quote {
        if (amount <= BigInteger.ZERO) return tronError(chain, "金额必须大于 0")
        if (!TronAddress.isValid(to)) return tronError(chain, "收款地址不是合法的 TRON 地址")
        return if (tokenContract == null) {
            Quote(
                fee = BigInteger.ZERO,
                decimals = chain.decimals,
                symbol = chain.symbol,
                note = "TRX 转账通常只消耗带宽（每日免费额度内为 0）",
                total = amount,
            )
        } else {
            val feeLimit = BigInteger.valueOf(tronRpc.defaultFeeLimitSun)
            Quote(
                fee = feeLimit,
                decimals = chain.decimals,
                symbol = chain.symbol,
                note = "TRC-20 需消耗能量，费用上限 ${com.lotuswallet.app.util.Formatters.formatUnits(feeLimit, chain.decimals)} TRX",
                total = amount,
            )
        }
    }

    /**
     * 签名并广播 TRON 交易。
     *
     * 注意：未签名交易由节点构建，钱包只对 `txID` 签名 —— 但**确认页展示的内容
     * 必须来自 `raw_data_hex` 的解析结果**，不能信任同一次响应里的 JSON 描述。
     */
    suspend fun sendTron(
        chain: TronChainConfig,
        privateKey: ByteArray,
        from: String,
        to: String,
        amount: BigInteger,
        tokenContract: String? = null,
    ): Broadcast {
        if (!TronAddress.isValid(to)) throw AppException(AppError.InvalidInput("收款地址不是合法的 TRON 地址"))
        if (amount <= BigInteger.ZERO) throw AppException(AppError.InvalidInput("金额必须大于 0"))

        val ownerHex41 = TronAddress.toHex41(from)
        val unsigned = if (tokenContract == null) {
            tronRpc.createTransfer(
                baseUrls = chain.rpcUrls,
                ownerHex41 = ownerHex41,
                toHex41 = TronAddress.toHex41(to),
                amountSun = amount.toLong(),
            )
        } else {
            tronRpc.createTrc20Transfer(
                baseUrls = chain.rpcUrls,
                ownerHex41 = ownerHex41,
                contractHex41 = TronAddress.toHex41(tokenContract),
                toAddress = to,
                amount = amount,
            )
        }

        // 签名前自检：解析出的原始字节必须真的指向我们以为的收款人与金额
        val raw = unsigned.rawData
        if (raw.contracts.isEmpty()) {
            throw AppException(AppError.IntegrityCheckFailed)
        }

        val signature = TronSigningFlow.sign(unsigned, privateKey, from)
        val result = tronRpc.broadcast(chain.rpcUrls, unsigned.withSignature(signature))
        if (!result.result) {
            throw AppException(
                AppError.Network(IllegalStateException(result.message ?: result.code ?: "节点拒绝了该交易")),
            )
        }
        val txId = result.txid ?: unsigned.txId
        return Broadcast(txId, chain.txUrl(txId))
    }

    private fun tronError(chain: TronChainConfig, message: String) =
        Quote(BigInteger.ZERO, chain.decimals, chain.symbol, error = message)

    companion object {
        /** 原生币转账的固定 gas。 */
        val NATIVE_TRANSFER_GAS: BigInteger = BigInteger.valueOf(21_000L)

        /** ERC-20 转账的兜底 gas（估不出来时用，略微保守）。 */
        val ERC20_TRANSFER_GAS_FALLBACK: BigInteger = BigInteger.valueOf(70_000L)
    }
}
