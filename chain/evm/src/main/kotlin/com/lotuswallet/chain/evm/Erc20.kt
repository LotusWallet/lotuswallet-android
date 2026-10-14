package com.lotuswallet.chain.evm

import com.lotuswallet.core.common.Bytes
import java.math.BigInteger

/**
 * ERC-20 / ERC-721 的 ABI 编码子集。
 *
 * 只实现钱包真正需要的方法，避免引入完整的 ABI 编解码库：
 * 余额查询、转账、元数据读取（symbol / decimals / name），
 * 以及 `eth_call` 返回值的解码。
 */
object Erc20 {

    /** `balanceOf(address)` */
    private val SELECTOR_BALANCE_OF = byteArrayOf(0x70, 0xa0.toByte(), 0x82.toByte(), 0x31)

    /** `transfer(address,uint256)` */
    private val SELECTOR_TRANSFER = byteArrayOf(0xa9.toByte(), 0x05, 0x9c.toByte(), 0xbb.toByte())

    /** `decimals()` */
    private val SELECTOR_DECIMALS = byteArrayOf(0x31, 0x3c.toByte(), 0xe5.toByte(), 0x67)

    /** `symbol()` */
    private val SELECTOR_SYMBOL = byteArrayOf(0x95.toByte(), 0xd8.toByte(), 0x9b.toByte(), 0x41)

    /** `name()` */
    private val SELECTOR_NAME = byteArrayOf(0x06, 0xfd.toByte(), 0xde.toByte(), 0x03)

    /** `transferFrom(address,address,uint256)`（仅用于展示授权额度，不主动调用） */
    private val SELECTOR_ALLOWANCE = byteArrayOf(0xdd.toByte(), 0x62.toByte(), 0xed.toByte(), 0x3e)

    fun balanceOfData(owner: String): ByteArray = SELECTOR_BALANCE_OF + encodeAddress(owner)

    fun transferData(to: String, amount: BigInteger): ByteArray =
        SELECTOR_TRANSFER + encodeAddress(to) + encodeUint(amount)

    fun decimalsData(): ByteArray = SELECTOR_DECIMALS

    fun symbolData(): ByteArray = SELECTOR_SYMBOL

    fun nameData(): ByteArray = SELECTOR_NAME

    fun allowanceData(owner: String, spender: String): ByteArray =
        SELECTOR_ALLOWANCE + encodeAddress(owner) + encodeAddress(spender)

    /** 地址 → 32 字节左填充。 */
    fun encodeAddress(address: String): ByteArray =
        Bytes.leftPad(Bytes.fromHex(EvmAddress.normalize(address)), 32)

    /** uint256 → 32 字节左填充。 */
    fun encodeUint(value: BigInteger): ByteArray = Bytes.leftPad(value.toMinimalBytes(), 32)

    /** 解码 uint256 返回值。 */
    fun decodeUint(returnData: ByteArray): BigInteger {
        if (returnData.isEmpty()) return BigInteger.ZERO
        return BigInteger(1, returnData.copyOfRange(0, minOf(32, returnData.size)))
    }

    /** 解码 bool 返回值。 */
    fun decodeBool(returnData: ByteArray): Boolean = decodeUint(returnData) != BigInteger.ZERO

    /**
     * 解码 ABI 动态字符串返回值（`symbol()` / `name()` 的实现各不相同，
     * 有的返回 `string`，有的返回 `bytes32`，这里两种都兼容）。
     */
    fun decodeString(returnData: ByteArray): String? {
        if (returnData.isEmpty()) return null
        // 情形一：bytes32（前 32 字节含字符串，后面为 0）
        if (returnData.size == 32) {
            return returnData.takeWhile { it.toInt() != 0 }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
                .ifBlank { null }
        }
        // 情形二：动态 string —— 头 32 字节是偏移量
        if (returnData.size >= 64) {
            val offset = BigInteger(1, returnData.copyOfRange(0, 32)).toInt()
            if (offset in 32 until returnData.size) {
                val length = BigInteger(1, returnData.copyOfRange(offset, offset + 32)).toInt()
                val start = offset + 32
                if (length >= 0 && start + length <= returnData.size) {
                    return String(returnData.copyOfRange(start, start + length), Charsets.UTF_8)
                        .trim()
                        .ifBlank { null }
                }
            }
        }
        // 兜底：当作 UTF-8 直接读
        return String(returnData, Charsets.UTF_8).trim().trim('\u0000').ifBlank { null }
    }
}
