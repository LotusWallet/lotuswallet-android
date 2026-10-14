package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1
import com.lotuswallet.core.network.chain.UtxoAddressType
import java.io.ByteArrayOutputStream

/** 交易输入引用的 UTXO。 */
data class TxOutPoint(val txId: String, val vout: Long)

/**
 * 交易输入。
 *
 * [value] 与 [scriptPubKey] 是「被花费的那个 UTXO」的信息 ——
 * BIP-143 签名必须用到金额（防手续费篡改攻击），所以这里必须带全。
 */
class TxInput(
    val outPoint: TxOutPoint,
    val value: Long,
    val scriptPubKey: ByteArray,
    val sequence: Long = 0xFFFFFFFFL,
) {
    /** P2PKH / P2SH 的解锁脚本。 */
    var scriptSig: ByteArray = ByteArray(0)

    /** SegWit / Taproot 的见证数据。 */
    var witness: List<ByteArray> = emptyList()

    val isSigned: Boolean get() = scriptSig.isNotEmpty() || witness.isNotEmpty()
}

/** 交易输出。 */
data class TxOutput(val value: Long, val scriptPubKey: ByteArray)

/**
 * 比特币交易：序列化、各类签名摘要（sighash）。
 *
 * 三种签名摘要都实现了：
 * - **Legacy**：`SIGHASH_ALL`，对整笔交易（含脚本）做双 SHA256；
 * - **BIP-143（SegWit v0）**：引入 `hashPrevouts / hashSequence / hashOutputs`，
 *   并**把金额纳入签名**，修掉了 legacy 手续费被中间人抬高的漏洞；
 * - **BIP-341（Taproot）**：带标签哈希的 `TapSighash`，只覆盖承诺过的部分以减小体积。
 */
class BitcoinTransaction(
    val version: Int = 2,
    val lockTime: Long = 0,
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    /** 是否按 SegWit 格式序列化（含 marker/flag 与 witness）。 */
    val segwit: Boolean = true,
) {

    /** 序列化。[includeWitness] 为 false 时得到 legacy 格式（用于计算 txid）。 */
    fun serialize(includeWitness: Boolean = segwit): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(TxWriter.i32(version))

        if (includeWitness) {
            out.write(0x00)
            out.write(0x01)
        }

        out.write(TxWriter.varInt(inputs.size.toLong()))
        for (input in inputs) {
            out.write(TxWriter.txIdToWireOrder(input.outPoint.txId))
            out.write(TxWriter.u32(input.outPoint.vout))
            out.write(TxWriter.varInt(input.scriptSig.size.toLong()))
            out.write(input.scriptSig)
            out.write(TxWriter.u32(input.sequence))
        }

        out.write(TxWriter.varInt(outputs.size.toLong()))
        for (output in outputs) {
            out.write(TxWriter.u64(output.value))
            out.write(TxWriter.varInt(output.scriptPubKey.size.toLong()))
            out.write(output.scriptPubKey)
        }

        if (includeWitness) {
            for (input in inputs) {
                out.write(TxWriter.varInt(input.witness.size.toLong()))
                for (item in input.witness) {
                    out.write(TxWriter.varInt(item.size.toLong()))
                    out.write(item)
                }
            }
        }

        out.write(TxWriter.u32(lockTime))
        return out.toByteArray()
    }

    /** 交易 ID（展示用的 32 字节大端十六进制）。 */
    fun txId(): String = TxWriter.wireOrderToTxId(Hash.doubleSha256(serialize(includeWitness = false)))

    /** 含见证的数据哈希（wtxid），与 [txId] 不同 —— 这是防「见证数据被篡改」的机制。 */
    fun wtxId(): String = TxWriter.wireOrderToTxId(Hash.doubleSha256(serialize(includeWitness = true)))

    fun totalOut(): Long = outputs.sumOf { it.value }

    fun totalIn(): Long = inputs.sumOf { it.value }

    val isFullySigned: Boolean get() = inputs.all { it.isSigned }

    /**
     * Legacy 签名摘要（只支持 `SIGHASH_ALL`，这是钱包唯一需要的模式）。
     */
    fun legacySighash(inputIndex: Int, scriptCode: ByteArray, hashType: Int = SIGHASH_ALL): ByteArray {
        require(inputIndex in inputs.indices) { "输入下标越界: $inputIndex" }
        require(hashType == SIGHASH_ALL) { "当前仅支持 SIGHASH_ALL" }
        val out = ByteArrayOutputStream()
        out.write(TxWriter.i32(version))
        out.write(TxWriter.varInt(inputs.size.toLong()))
        for ((i, input) in inputs.withIndex()) {
            out.write(TxWriter.txIdToWireOrder(input.outPoint.txId))
            out.write(TxWriter.u32(input.outPoint.vout))
            val script = if (i == inputIndex) scriptCode else ByteArray(0)
            out.write(TxWriter.varInt(script.size.toLong()))
            out.write(script)
            out.write(TxWriter.u32(input.sequence))
        }
        out.write(TxWriter.varInt(outputs.size.toLong()))
        for (output in outputs) {
            out.write(TxWriter.u64(output.value))
            out.write(TxWriter.varInt(output.scriptPubKey.size.toLong()))
            out.write(output.scriptPubKey)
        }
        out.write(TxWriter.u32(lockTime))
        out.write(TxWriter.u32(hashType.toLong()))
        return Hash.doubleSha256(out.toByteArray())
    }

    /**
     * BIP-143 签名摘要（SegWit v0）。
     *
     * [scriptCode] 对 P2WPKH 而言是带长度前缀的 P2PKH 脚本，
     * 由 [Script.p2wpkhScriptCode] 生成。
     */
    fun segwitV0Sighash(
        inputIndex: Int,
        scriptCode: ByteArray,
        amount: Long,
        hashType: Int = SIGHASH_ALL,
    ): ByteArray {
        require(inputIndex in inputs.indices) { "输入下标越界: $inputIndex" }
        require(hashType == SIGHASH_ALL) { "当前仅支持 SIGHASH_ALL" }

        val hashPrevouts = Hash.doubleSha256(
            concatInputs { TxWriter.txIdToWireOrder(it.outPoint.txId) + TxWriter.u32(it.outPoint.vout) },
        )
        val hashSequence = Hash.doubleSha256(concatInputs { TxWriter.u32(it.sequence) })
        val hashOutputs = Hash.doubleSha256(
            concatOutputs { TxWriter.u64(it.value) + TxWriter.varInt(it.scriptPubKey.size.toLong()) + it.scriptPubKey },
        )

        val input = inputs[inputIndex]
        val preimage = TxWriter.i32(version) +
            hashPrevouts +
            hashSequence +
            TxWriter.txIdToWireOrder(input.outPoint.txId) +
            TxWriter.u32(input.outPoint.vout) +
            TxWriter.varInt(scriptCode.size.toLong()) +
            scriptCode +
            TxWriter.u64(amount) +
            TxWriter.u32(input.sequence) +
            hashOutputs +
            TxWriter.u32(lockTime) +
            TxWriter.u32(hashType.toLong())

        return Hash.doubleSha256(preimage)
    }

    /**
     * BIP-341 签名摘要（Taproot 密钥路径，`SIGHASH_DEFAULT`）。
     *
     * 注意与 BIP-143 的两个差异：
     * 1. 这里用的是**单次 SHA256**（BIP-341 自带标签哈希已提供域分离）；
     * 2. `spend_type = 0` 表示「无扩展标志、无 annex」的密钥路径花费。
     */
    fun taprootSighash(inputIndex: Int, hashType: Int = SIGHASH_DEFAULT): ByteArray {
        require(inputIndex in inputs.indices) { "输入下标越界: $inputIndex" }
        require(hashType == SIGHASH_DEFAULT) { "当前仅支持 SIGHASH_DEFAULT" }

        val shaPrevouts = Hash.sha256(
            concatInputs { TxWriter.txIdToWireOrder(it.outPoint.txId) + TxWriter.u32(it.outPoint.vout) },
        )
        val shaAmounts = Hash.sha256(concatInputs { TxWriter.u64(it.value) })
        val shaScriptPubKeys = Hash.sha256(
            concatInputs { TxWriter.varInt(it.scriptPubKey.size.toLong()) + it.scriptPubKey },
        )
        val shaSequences = Hash.sha256(concatInputs { TxWriter.u32(it.sequence) })
        val shaOutputs = Hash.sha256(
            concatOutputs { TxWriter.u64(it.value) + TxWriter.varInt(it.scriptPubKey.size.toLong()) + it.scriptPubKey },
        )

        val sigMsg = byteArrayOf(hashType.toByte()) +
            TxWriter.i32(version) +
            TxWriter.u32(lockTime) +
            shaPrevouts +
            shaAmounts +
            shaScriptPubKeys +
            shaSequences +
            shaOutputs +
            byteArrayOf(0x00)

        // epoch 字节 0x00 + SigMsg
        return Schnorr.taggedHash("TapSighash", byteArrayOf(0x00) + sigMsg)
    }

    /** 最终广播用的原始交易十六进制。 */
    fun rawHex(): String {
        check(isFullySigned) { "交易尚未完成签名，不能广播" }
        return Bytes.toHex(serialize())
    }

    private inline fun concatInputs(block: (TxInput) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (input in inputs) out.write(block(input))
        return out.toByteArray()
    }

    private inline fun concatOutputs(block: (TxOutput) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (output in outputs) out.write(block(output))
        return out.toByteArray()
    }

    companion object {
        const val SIGHASH_ALL = 1
        const val SIGHASH_DEFAULT = 0
    }
}

/**
 * 输入签名器。按地址类型选择正确的摘要算法与解锁脚本/见证结构。
 */
object BitcoinSigner {

    /**
     * 对第 [index] 个输入签名。会就地写入 `scriptSig` / `witness`。
     *
     * @param privateKey 该输入对应地址的私钥
     * @param type 地址类型，必须与 UTXO 实际归属的地址类型一致
     */
    fun signInput(
        tx: BitcoinTransaction,
        index: Int,
        privateKey: ByteArray,
        type: UtxoAddressType,
        hashType: Int = BitcoinTransaction.SIGHASH_ALL,
    ) {
        val input = tx.inputs[index]
        val pubKey = Secp256k1.publicKeyCompressed(privateKey)
        val hash160 = Script.pubKeyHash160(pubKey)

        when (type) {
            UtxoAddressType.LEGACY -> {
                val scriptCode = Script.p2pkhScriptPubKey(hash160)
                val digest = tx.legacySighash(index, scriptCode, hashType)
                val signature = Secp256k1.sign(privateKey, digest).toDer() + hashType.toByte()
                input.scriptSig = Script.p2pkhScriptSig(signature, pubKey)
                input.witness = emptyList()
            }

            UtxoAddressType.NESTED_SEGWIT -> {
                val scriptCode = Script.p2wpkhScriptCode(hash160)
                val digest = tx.segwitV0Sighash(index, scriptCode, input.value, hashType)
                val signature = Secp256k1.sign(privateKey, digest).toDer() + hashType.toByte()
                input.scriptSig = Script.p2shScriptSig(Script.p2shP2wpkhRedeemScript(hash160))
                input.witness = listOf(signature, pubKey)
            }

            UtxoAddressType.NATIVE_SEGWIT -> {
                val scriptCode = Script.p2wpkhScriptCode(hash160)
                val digest = tx.segwitV0Sighash(index, scriptCode, input.value, hashType)
                val signature = Secp256k1.sign(privateKey, digest).toDer() + hashType.toByte()
                input.scriptSig = ByteArray(0)
                input.witness = listOf(signature, pubKey)
            }

            UtxoAddressType.TAPROOT -> {
                val digest = tx.taprootSighash(index, BitcoinTransaction.SIGHASH_DEFAULT)
                // 必须使用调整后的私钥：d + t (mod n)，且已按 y 奇偶修正
                val tweaked = Taproot.tweakedPrivateKey(privateKey)
                val signature = Schnorr.sign(tweaked, digest)
                input.scriptSig = ByteArray(0)
                input.witness = listOf(signature)
            }
        }
    }

    /** 校验签好的交易结构是否自洽（不上链的本地自检）。 */
    fun sanityCheck(tx: BitcoinTransaction) {
        check(tx.inputs.isNotEmpty()) { "交易至少需要一个输入" }
        check(tx.outputs.isNotEmpty()) { "交易至少需要一个输出" }
        check(tx.isFullySigned) { "存在未签名的输入" }
        check(tx.totalIn() >= tx.totalOut()) { "输入总额小于输出总额" }
    }
}

/**
 * 手续费估算。
 *
 * 采用「按字节计费 + 见证折扣」的常见近似值（单位：vB）。
 * 这些常量取自主流钱包的实测值，误差通常在 ±2 vB 内，足以避免交易被卡住。
 */
object BitcoinFee {

    /** 交易固定开销（version 4 + locktime 4 + 输入/输出计数 2，含 segwit marker/flag 折扣）。 */
    private const val OVERHEAD_VBYTES = 10.5

    fun inputVBytes(type: UtxoAddressType): Double = when (type) {
        UtxoAddressType.LEGACY -> 148.0
        UtxoAddressType.NESTED_SEGWIT -> 91.0
        UtxoAddressType.NATIVE_SEGWIT -> 68.0
        UtxoAddressType.TAPROOT -> 57.5
    }

    /** 由目标地址的 scriptPubKey 长度推算输出体积（字节）。 */
    fun outputVBytes(scriptPubKey: ByteArray): Double = 8.0 + varIntSize(scriptPubKey.size) + scriptPubKey.size

    fun varIntSize(value: Int): Int = when {
        value < 0xFD -> 1
        value <= 0xFFFF -> 3
        else -> 5
    }

    /** 估算虚拟体积（向上取整，宁可多给一点费）。 */
    fun estimateVBytes(inputTypes: List<UtxoAddressType>, outputScripts: List<ByteArray>): Long {
        var vbytes = OVERHEAD_VBYTES
        for (type in inputTypes) vbytes += inputVBytes(type)
        for (script in outputScripts) vbytes += outputVBytes(script)
        return Math.ceil(vbytes).toLong()
    }

    /** 手续费 = 虚拟体积 × 费率（聪/vB）。 */
    fun fee(inputTypes: List<UtxoAddressType>, outputScripts: List<ByteArray>, feeRate: Long): Long =
        estimateVBytes(inputTypes, outputScripts) * feeRate
}
