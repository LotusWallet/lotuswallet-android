package com.lotuswallet.chain.alt

import com.lotuswallet.core.crypto.Bech32

/**
 * Chia 地址（`xch1…`）。
 *
 * ## 它是什么
 * Chia 没有「公钥 = 地址」。地址是 **puzzle hash** 的 bech32m 编码：
 * ```
 * xch1 + bech32m(32 字节 puzzle hash)
 * ```
 * puzzle hash 由「标准 puzzle 程序（内含 48 字节 BLS 公钥）」做 CLVM 树哈希得到。
 *
 * ## 本版本的支持范围
 * - ✅ **编码/校验**：puzzle hash ↔ `xch1…` 的互转，以及导入观察地址时的完整性校验。
 * - ❌ **由助记词派生**：需要内置 BLS12-381 与标准 puzzle 的**字节级常量**。
 *   常量一旦写错，算出来的地址看上去完全合法、却指向一个没人能解锁的谜题，
 *   属于「静默错误资产」，因此在没有参照实现可以逐字节比对之前**不生成**。
 *
 * 于是 Chia 在本钱包里以「观察地址」方式可用：用户可以导入自己已有的 `xch1` 地址
 * 纳入统一账户管理；等派生实现经过校验后再打开。
 */
object ChiaAddress {

    /** 主网前缀。 */
    const val MAINNET_HRP = "xch"

    /** 测试网前缀。 */
    const val TESTNET_HRP = "txch"

    /** puzzle hash → 地址。 */
    fun puzzleHashToAddress(puzzleHash: ByteArray, hrp: String = MAINNET_HRP): String {
        require(puzzleHash.size == 32) { "Chia 的 puzzle hash 必须是 32 字节，实际 ${puzzleHash.size}" }
        val data5 = Bech32.convertBits(puzzleHash, 8, 5, true)
        return Bech32.encode(hrp, data5, Bech32.Variant.BECH32M)
    }

    /** 地址 → puzzle hash；格式不对时返回 null。 */
    fun addressToPuzzleHash(address: String, hrp: String = MAINNET_HRP): ByteArray? {
        val decoded = runCatching { Bech32.decode(address.trim()) }.getOrNull() ?: return null
        if (decoded.hrp != hrp) return null
        if (decoded.variant != Bech32.Variant.BECH32M) return null
        val bytes = runCatching { Bech32.convertBits(decoded.data, 5, 8, false) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == 32 }
    }

    fun isValid(address: String, hrp: String = MAINNET_HRP): Boolean =
        addressToPuzzleHash(address, hrp) != null
}
