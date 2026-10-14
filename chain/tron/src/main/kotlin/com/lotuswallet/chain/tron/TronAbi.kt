package com.lotuswallet.chain.tron

import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Hash
import java.math.BigInteger

/**
 * TRC-20 合约的 ABI 编解码。
 *
 * 与 ERC-20 完全一致（TRON 兼容 EVM 的 ABI 约定），唯一区别是**地址编码**：
 * TRON 的地址在 ABI 里只取 20 字节主体，左填充到 32 字节，
 * **不带 `0x41` 前缀** —— 这点写错会导致转账发给一个完全不存在的地址。
 */
object TronAbi {

    private val SELECTOR_BALANCE_OF = byteArrayOf(0x70, 0xa0.toByte(), 0x82.toByte(), 0x31)
    private val SELECTOR_TRANSFER = byteArrayOf(0xa9.toByte(), 0x05, 0x9c.toByte(), 0xbb.toByte())
    private val SELECTOR_DECIMALS = byteArrayOf(0x31, 0x3c.toByte(), 0xe5.toByte(), 0x67)
    private val SELECTOR_SYMBOL = byteArrayOf(0x95.toByte(), 0xd8.toByte(), 0x9b.toByte(), 0x41)
    private val SELECTOR_NAME = byteArrayOf(0x06, 0xfd.toByte(), 0xde.toByte(), 0x03)

    const val FUNCTION_TRANSFER = "transfer(address,uint256)"
    const val FUNCTION_BALANCE_OF = "balanceOf(address)"

    /** 地址 → 32 字节 ABI 参数（20 字节主体左填充）。 */
    fun encodeAddress(address: String): ByteArray =
        Bytes.leftPad(Bytes.fromHex(TronAddress.toBodyHex(address)), 32)

    fun encodeUint(value: BigInteger): ByteArray {
        if (value.signum() == 0) return ByteArray(32)
        val raw = value.toByteArray()
        val trimmed = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        return Bytes.leftPad(trimmed, 32)
    }

    /** `transfer(address,uint256)` 的**参数部分**（不含 4 字节选择器）。 */
    fun transferParameter(to: String, amount: BigInteger): ByteArray =
        encodeAddress(to) + encodeUint(amount)

    fun balanceOfParameter(owner: String): ByteArray = encodeAddress(owner)

    fun transferData(to: String, amount: BigInteger): ByteArray =
        SELECTOR_TRANSFER + transferParameter(to, amount)

    fun balanceOfData(owner: String): ByteArray = SELECTOR_BALANCE_OF + balanceOfParameter(owner)

    fun decimalsData(): ByteArray = SELECTOR_DECIMALS

    fun symbolData(): ByteArray = SELECTOR_SYMBOL

    fun nameData(): ByteArray = SELECTOR_NAME

    // ------------------------------------------------------------ 解码

    fun decodeUint(returnData: ByteArray): BigInteger {
        if (returnData.isEmpty()) return BigInteger.ZERO
        return BigInteger(1, returnData.copyOfRange(0, minOf(32, returnData.size)))
    }

    fun decodeUintHex(hex: String): BigInteger = decodeUint(Bytes.fromHex(hex))

    /** 解码 ABI 返回的地址参数（32 字节）为 TRON Base58 地址。 */
    fun decodeAddress(parameter: ByteArray): String? {
        if (parameter.size < 32) return null
        val body = parameter.copyOfRange(12, 32)
        if (body.all { it.toInt() == 0 }) return null
        return TronAddress.fromHex(Bytes.toHex(body))
    }

    fun decodeAddressHex(hex: String): String? = decodeAddress(Bytes.fromHex(hex))

    fun decodeString(returnData: ByteArray): String? {
        if (returnData.isEmpty()) return null
        if (returnData.size == 32) {
            return returnData.takeWhile { it.toInt() != 0 }.toByteArray()
                .toString(Charsets.UTF_8).trim().ifBlank { null }
        }
        if (returnData.size >= 64) {
            val offset = BigInteger(1, returnData.copyOfRange(0, 32)).toInt()
            if (offset in 32 until returnData.size) {
                val length = BigInteger(1, returnData.copyOfRange(offset, offset + 32)).toInt()
                val start = offset + 32
                if (length >= 0 && start + length <= returnData.size) {
                    return String(returnData.copyOfRange(start, start + length), Charsets.UTF_8)
                        .trim().ifBlank { null }
                }
            }
        }
        return String(returnData, Charsets.UTF_8).trim().trim('\u0000').ifBlank { null }
    }

    /**
     * 解析一笔 TRC-20 转账的调用数据。
     *
     * **签名前必须调用它**：这样确认页展示的收款人与金额直接来自即将签名的字节，
     * 而不是来自 UI 层传下来的变量。任何不一致都应当中止签名。
     */
    fun decodeTransfer(callData: ByteArray): Pair<String, BigInteger>? {
        if (callData.size < 4 + 64) return null
        val selector = callData.copyOfRange(0, 4)
        if (!selector.contentEquals(SELECTOR_TRANSFER)) return null
        val params = callData.copyOfRange(4, callData.size)
        val to = decodeAddress(params) ?: return null
        val amount = decodeUint(params.copyOfRange(32, 64))
        return to to amount
    }

    /** 计算选择器（供测试与校验）。 */
    fun selector(signature: String): ByteArray = Hash.keccak256(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)
}
