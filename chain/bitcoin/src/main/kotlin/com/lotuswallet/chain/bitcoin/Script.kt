package com.lotuswallet.chain.bitcoin

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash
import java.io.ByteArrayOutputStream

/**
 * 比特币脚本构建（只做钱包需要的部分）。
 *
 * 实现的是「输出脚本」（scriptPubKey）与「解锁脚本」（scriptSig / witness），
 * 不实现脚本解释器 —— 钱包不需要执行脚本，只需要构造合法的标准脚本类型。
 */
object Script {

    // 操作码。声明为 Byte 而不是 Int —— 否则每次拼接脚本都要写一遍 .toByte()，
    // 而且漏写时编译器不会报错（byteArrayOf 会退化为 Int 数组）。
    val OP_0: Byte = 0x00
    val OP_1: Byte = 0x51
    val OP_PUSHDATA1: Byte = 0x4c.toByte()
    val OP_PUSHDATA2: Byte = 0x4d.toByte()
    val OP_DUP: Byte = 0x76
    val OP_EQUAL: Byte = 0x87.toByte()
    val OP_EQUALVERIFY: Byte = 0x88.toByte()
    val OP_HASH160: Byte = 0xa9.toByte()
    val OP_CHECKSIG: Byte = 0xac.toByte()
    val OP_RETURN: Byte = 0x6a

    /** P2PKH：`OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG` */
    fun p2pkhScriptPubKey(pubKeyHash160: ByteArray): ByteArray {
        require(pubKeyHash160.size == 20) { "hash160 必须是 20 字节" }
        return byteArrayOf(OP_DUP, OP_HASH160, 0x14) + pubKeyHash160 +
            byteArrayOf(OP_EQUALVERIFY, OP_CHECKSIG)
    }

    /** P2WPKH：`OP_0 <20>` */
    fun p2wpkhScriptPubKey(pubKeyHash160: ByteArray): ByteArray {
        require(pubKeyHash160.size == 20) { "hash160 必须是 20 字节" }
        return byteArrayOf(OP_0, 0x14) + pubKeyHash160
    }

    /** P2SH：`OP_HASH160 <20> OP_EQUAL` */
    fun p2shScriptPubKey(scriptHash160: ByteArray): ByteArray {
        require(scriptHash160.size == 20) { "script hash 必须是 20 字节" }
        return byteArrayOf(OP_HASH160, 0x14) + scriptHash160 + byteArrayOf(OP_EQUAL)
    }

    /** P2TR：`OP_1 <32>`（witness v1） */
    fun p2trScriptPubKey(xOnlyOutputKey: ByteArray): ByteArray {
        require(xOnlyOutputKey.size == 32) { "Taproot 输出键必须是 32 字节 x-only 公钥" }
        return byteArrayOf(OP_1, 0x20) + xOnlyOutputKey
    }

    /** P2SH-P2WPKH 的 redeemScript 就是 P2WPKH 的 scriptPubKey。 */
    fun p2shP2wpkhRedeemScript(pubKeyHash160: ByteArray): ByteArray = p2wpkhScriptPubKey(pubKeyHash160)

    /** `scriptSig = <sig> <pubkey>`（P2PKH）。 */
    fun p2pkhScriptSig(signatureWithHashType: ByteArray, pubKey: ByteArray): ByteArray =
        pushData(signatureWithHashType) + pushData(pubKey)

    /** `scriptSig = <redeemScript>`（P2SH）。 */
    fun p2shScriptSig(redeemScript: ByteArray): ByteArray = pushData(redeemScript)

    /** 最小 push 编码（BIP-62 要求使用最小 push）。 */
    fun pushData(data: ByteArray): ByteArray = when {
        data.size < 0x4c -> byteArrayOf(data.size.toByte()) + data
        data.size <= 0xff -> byteArrayOf(OP_PUSHDATA1, data.size.toByte()) + data
        data.size <= 0xffff -> byteArrayOf(
            OP_PUSHDATA2,
            (data.size and 0xff).toByte(),
            ((data.size ushr 8) and 0xff).toByte(),
        ) + data
        else -> {
            val out = ByteArrayOutputStream()
            out.write(0x4e)
            out.write(data.size and 0xff)
            out.write((data.size ushr 8) and 0xff)
            out.write((data.size ushr 16) and 0xff)
            out.write((data.size ushr 24) and 0xff)
            out.write(data)
            out.toByteArray()
        }
    }

    /**
     * BIP-143 的 scriptCode：P2WPKH 用等价的 P2PKH 脚本，即 `76a914{20 字节公钥哈希}88ac`。
     *
     * **不含长度前缀**。BIP-143 规定 scriptCode 在签名原像里按「CTxOut 内的脚本」方式序列化
     * （即 CompactSize 长度 + 脚本），这一步由 [BitcoinTransaction.segwitV0Sighash] 统一完成；
     * 这里如果再带一次前缀，原像就会变成 `1a 19 76a914…88ac`，产生的签名在链上会被判为无效。
     * P2WSH 的 witnessScript 同样按「裸脚本」传入，语义保持一致。
     */
    fun p2wpkhScriptCode(pubKeyHash160: ByteArray): ByteArray = p2pkhScriptPubKey(pubKeyHash160)

    /** 判断某个 scriptPubKey 是否是 P2WPKH。 */
    fun isP2wpkh(scriptPubKey: ByteArray): Boolean =
        scriptPubKey.size == 22 && scriptPubKey[0] == OP_0 && scriptPubKey[1] == 0x14.toByte()

    /** 判断是否是 P2TR。 */
    fun isP2tr(scriptPubKey: ByteArray): Boolean =
        scriptPubKey.size == 34 && scriptPubKey[0] == OP_1 && scriptPubKey[1] == 0x20.toByte()

    /** 计算脚本哈希（P2SH 用）。 */
    fun scriptHash160(script: ByteArray): ByteArray = Hash.hash160(script)

    /** 计算公钥哈希（P2PKH / P2WPKH 用）。 */
    fun pubKeyHash160(pubKeyCompressed: ByteArray): ByteArray = Hash.hash160(pubKeyCompressed)

    fun hex(script: ByteArray): String = Bytes.toHex(script)
}

/** 交易序列化的底层写入器（小端序）。 */
internal object TxWriter {

    fun varInt(value: Long): ByteArray = when {
        value < 0xFD -> byteArrayOf(value.toByte())
        value <= 0xFFFF -> byteArrayOf(0xFD.toByte(), (value and 0xff).toByte(), ((value ushr 8) and 0xff).toByte())
        value <= 0xFFFFFFFFL -> byteArrayOf(
            0xFE.toByte(),
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte(),
        )
        else -> ByteArray(9).also { out ->
            out[0] = 0xFF.toByte()
            for (i in 0 until 8) out[i + 1] = ((value ushr (8 * i)) and 0xff).toByte()
        }
    }

    /** 4 字节小端（u32）。 */
    fun u32(value: Long): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )

    /** 8 字节小端（u64，金额）。 */
    fun u64(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xff).toByte() }

    fun i32(value: Int): ByteArray = u32(value.toLong() and 0xFFFFFFFFL)

    /**
     * 交易哈希的线格式是**小端**，而区块浏览器展示的 txid 是**大端**。
     * 这个反转是最容易出错的地方之一，这里统一处理。
     */
    fun txIdToWireOrder(txIdHex: String): ByteArray {
        val bytes = Bytes.fromHex(txIdHex.trim())
        require(bytes.size == 32) { "txid 必须是 32 字节，实际 ${bytes.size}" }
        return bytes.reversedArray()
    }

    /** 线格式 txid → 展示用大端十六进制。 */
    fun wireOrderToTxId(wire: ByteArray): String = Bytes.toHex(wire.reversedArray())
}
