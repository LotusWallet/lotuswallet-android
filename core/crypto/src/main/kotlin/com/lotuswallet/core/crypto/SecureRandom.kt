package com.lotuswallet.core.crypto

import java.math.BigInteger

/**
 * 系统级安全随机数。全项目唯一入口 —— 禁止在业务代码里直接 `new SecureRandom()` 或 `Random()`。
 */
object SecureRandom {

    private val rng = java.security.SecureRandom()

    fun bytes(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }

    fun nextInt(bound: Int): Int = rng.nextInt(bound)

    fun nextLong(): Long = rng.nextLong()

    /**
     * 均匀随机的大整数，范围 `[1, n-1]`（n 为传入上界）。
     * 采用拒绝采样，避免模偏差。
     */
    fun bigInteger(bound: BigInteger): BigInteger {
        require(bound > BigInteger.ONE) { "上界必须大于 1" }
        val bitLength = bound.bitLength()
        var candidate: BigInteger
        do {
            candidate = BigInteger(bitLength, rng)
        } while (candidate.signum() == 0 || candidate >= bound)
        return candidate
    }
}
