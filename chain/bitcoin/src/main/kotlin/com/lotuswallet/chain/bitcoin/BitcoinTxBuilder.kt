package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.network.chain.UtxoAddressType

/**
 * 一个可花费的 UTXO。
 */
data class Utxo(
    val txId: String,
    val vout: Long,
    /** 金额（聪）。 */
    val value: Long,
    val scriptPubKey: ByteArray,
    val addressType: UtxoAddressType,
    /** 确认数；0 表示仍在内存池。 */
    val confirmations: Int = 1,
) {
    override fun equals(other: Any?): Boolean =
        other is Utxo && txId == other.txId && vout == other.vout && value == other.value

    override fun hashCode(): Int = (txId.hashCode() * 31 + vout.toInt()) * 31 + value.hashCode()
}

/** 构建结果。 */
sealed interface BitcoinBuildResult {

    data class Success(
        val transaction: BitcoinTransaction,
        val fee: Long,
        val change: Long,
        val selected: List<Utxo>,
        val feeRateUsed: Long,
    ) : BitcoinBuildResult

    data class Failure(val reason: String) : BitcoinBuildResult
}

/**
 * 交易构建与选币。
 *
 * ## 选币策略
 * 采用「大额优先（largest-first）」：
 * 1. 按金额降序排序，逐个纳入输入；
 * 2. 每次纳入后重新估算手续费（含找零输出）；
 * 3. 若找零低于粉尘阈值，则把找零并入手续费（避免产生不可花费的输出）；
 * 4. 全部 UTXO 用尽仍不足则返回失败并说明缺口。
 *
 * 这个策略对普通用户场景足够，且产生的输入数量少、交易体积小。
 * 更复杂的策略（隐私优先、冻结币、最小找零）留待后续按需扩展。
 */
object BitcoinTxBuilder {

    fun build(
        utxos: List<Utxo>,
        recipientScript: ByteArray,
        amount: Long,
        changeScript: ByteArray,
        changeType: UtxoAddressType,
        feeRate: Long,
        dustThreshold: Long,
    ): BitcoinBuildResult {
        require(feeRate > 0) { "费率必须为正数" }
        if (amount <= 0) return BitcoinBuildResult.Failure("转账金额必须大于 0")
        if (amount < dustThreshold) return BitcoinBuildResult.Failure("金额低于粉尘阈值 $dustThreshold，无法转账")

        val usable = utxos.filter { it.value > 0 }.sortedByDescending { it.value }
        if (usable.isEmpty()) return BitcoinBuildResult.Failure("没有可用余额")

        val selected = mutableListOf<Utxo>()
        var total = 0L

        for (utxo in usable) {
            selected += utxo
            total += utxo.value

            // 情形一：带找零输出
            val feeWithChange = BitcoinFee.fee(
                selected.map { it.addressType },
                listOf(recipientScript, changeScript),
                feeRate,
            )
            if (total >= amount + feeWithChange) {
                val change = total - amount - feeWithChange
                if (change >= dustThreshold) {
                    return buildTx(selected, recipientScript, amount, changeScript, change, feeWithChange, feeRate)
                }
                // 找零太小：并入手续费，不再产生找零输出
                val feeNoChange = BitcoinFee.fee(
                    selected.map { it.addressType },
                    listOf(recipientScript),
                    feeRate,
                )
                if (total >= amount + feeNoChange) {
                    return buildTx(
                        selected,
                        recipientScript,
                        amount,
                        changeScript,
                        change = 0,
                        fee = total - amount,
                        feeRateUsed = feeRate,
                    )
                }
            }
        }

        val shortfall = amount + BitcoinFee.fee(
            usable.map { it.addressType },
            listOf(recipientScript, changeScript),
            feeRate,
        ) - total
        return BitcoinBuildResult.Failure("余额不足，还差 $shortfall 聪（含手续费）")
    }

    /** 只估算费用，不真正构建（用于转账页面展示）。 */
    fun estimateFee(
        utxos: List<Utxo>,
        recipientScript: ByteArray,
        amount: Long,
        changeScript: ByteArray,
        feeRate: Long,
        dustThreshold: Long,
    ): Long? {
        val result = build(utxos, recipientScript, amount, changeScript, UtxoAddressType.NATIVE_SEGWIT, feeRate, dustThreshold)
        return (result as? BitcoinBuildResult.Success)?.fee
    }

    private fun buildTx(
        selected: List<Utxo>,
        recipientScript: ByteArray,
        amount: Long,
        changeScript: ByteArray,
        change: Long,
        fee: Long,
        feeRateUsed: Long,
    ): BitcoinBuildResult {
        val inputs = selected.map {
            TxInput(
                outPoint = TxOutPoint(it.txId, it.vout),
                value = it.value,
                scriptPubKey = it.scriptPubKey,
            )
        }
        val outputs = buildList {
            add(TxOutput(amount, recipientScript))
            if (change > 0) add(TxOutput(change, changeScript))
        }
        val tx = BitcoinTransaction(
            version = 2,
            lockTime = 0,
            inputs = inputs,
            outputs = outputs,
            segwit = true,
        )
        return BitcoinBuildResult.Success(tx, fee, change, selected, feeRateUsed)
    }
}
