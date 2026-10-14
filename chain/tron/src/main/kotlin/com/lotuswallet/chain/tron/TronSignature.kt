package com.lotuswallet.chain.tron

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash
import com.lotuswallet.core.crypto.Secp256k1
import java.math.BigInteger

/**
 * TRON 交易签名。
 *
 * ## 关键点：签的是 `txID`，不是整笔交易
 * TRON 的交易哈希 `txID = SHA256(raw_data)`。签名就是对这个 32 字节哈希做 ECDSA，
 * 产出 `r(32) || s(32) || v(1)` 共 65 字节，其中 `v` 是**恢复标识**（0 或 1）。
 *
 * 这与 EVM 不同（EVM 的 `v` 是 `recId + 27` 或 `recId + 35 + chainId*2`），
 * 也是旧版钱包里最容易搞错的一处。
 *
 * ## 为什么用 low-S
 * TRON 主流的签名实现（TronWeb / java-tron 客户端）都使用 **canonical（low-S）** 签名，
 * 节点会拒绝 s 处于高半区的签名。本项目复用 [Secp256k1.sign]，它默认做 low-S 规范化，
 * 并且会基于规范化后的 `s` 重新求出正确的 `recId`。
 */
object TronSignature {

    const val SIGNATURE_LENGTH = 65

    /**
     * 对交易哈希签名。
     *
     * @param txId 32 字节的交易哈希（`SHA256(raw_data)`）
     * @return `r || s || v`，65 字节
     */
    fun sign(txId: ByteArray, privateKey: ByteArray): ByteArray {
        require(txId.size == 32) { "TRON 交易哈希必须是 32 字节，实际 ${txId.size}" }
        val signature = Secp256k1.sign(privateKey, txId)
        return signature.toCompact() + byteArrayOf(signature.recId.toByte())
    }

    fun signHex(txId: ByteArray, privateKey: ByteArray): String = Bytes.toHex(sign(txId, privateKey))

    /**
     * 由签名恢复出签名者地址，并与期望地址比对。
     *
     * 这是广播前最后的自检：签名一旦错误，链上会静默拒绝（或更糟，产生异常交易）。
     */
    fun verify(txId: ByteArray, signature: ByteArray, expectedAddress: String): Boolean {
        require(signature.size == SIGNATURE_LENGTH) { "TRON 签名必须是 65 字节" }
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        val v = signature[64].toInt() and 0xFF
        val point = try {
            Secp256k1.recoverPublicPoint(txId, r, s, v)
        } catch (_: Exception) {
            return false
        }
        val recovered = "41" + Bytes.toHex(
            Hash.keccak256(point.getEncoded(false).copyOfRange(1, 65)).copyOfRange(12, 32),
        )
        return recovered.equals(TronAddress.toHex41(expectedAddress), ignoreCase = true)
    }

    /** 从 65 字节签名中提取恢复标识。 */
    fun recoveryId(signature: ByteArray): Int {
        require(signature.size == SIGNATURE_LENGTH)
        return signature[64].toInt() and 0xFF
    }
}
