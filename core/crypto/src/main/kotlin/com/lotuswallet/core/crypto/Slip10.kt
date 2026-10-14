package com.lotuswallet.core.crypto

/**
 * SLIP-0010 分层确定性派生（ed25519 曲线）。
 *
 * ## 为什么不能复用 BIP-32
 * BIP-32 的公钥推导假设曲线是 secp256k1（需要点加法）。ed25519 上做不了公开推导，
 * 因此 SLIP-0010 规定：**只允许硬化派生**，且主密钥的 HMAC key 是字符串
 * `"ed25519 seed"` 而不是 `"Bitcoin seed"`。
 *
 * Solana / ICP / Monero 的助记词恢复都走这一套（Path 里所有层必须是硬化）。
 * 一旦这里写错，用户导出的地址在其它钱包里就对不上 —— 所以路径解析必须严格。
 */
object Slip10 {

    private val CURVE_SEED = "ed25519 seed".toByteArray(Charsets.US_ASCII)
    private const val HARDENED_OFFSET = 0x80000000.toInt()

    /** 路径片段：十进制数字，可选一个硬化标记（`'` / `h` / `H`）。 */
    private val SEGMENT = Regex("^([0-9]+)['hH]?$")

    /** 一个派生节点：32 字节密钥 + 32 字节链码。 */
    data class Node(val key: ByteArray, val chainCode: ByteArray)

    /** 主密钥。种子来自 [Bip39.mnemonicToSeed]（64 字节）。 */
    fun masterKey(seed: ByteArray): Node {
        require(seed.size in 16..64) { "种子长度应在 16..64 字节，实际 ${seed.size}" }
        val i = Hash.hmacSha512(CURVE_SEED, seed)
        return Node(i.copyOfRange(0, 32), i.copyOfRange(32, 64))
    }

    /** 派生子节点。ed25519 只支持硬化派生，[index] 传未硬化值即可。 */
    fun deriveChild(parent: Node, index: Int): Node {
        require(index in 0..0x7FFFFFFF) { "派生索引越界: $index" }
        val hardened = index or HARDENED_OFFSET
        val data = byteArrayOf(0x00) + parent.key + ser32(hardened)
        val i = Hash.hmacSha512(parent.chainCode, data)
        return Node(i.copyOfRange(0, 32), i.copyOfRange(32, 64))
    }

    /** 由种子沿路径派生。路径中的 `'` / `h` 会被接受（本来就是全硬化）。 */
    fun derive(seed: ByteArray, path: String): Node {
        var node = masterKey(seed)
        for (index in parsePath(path)) node = deriveChild(node, index)
        return node
    }

    fun derive(mnemonic: String, passphrase: String, path: String): Node =
        derive(Bip39.mnemonicToSeed(mnemonic, passphrase), path)

    /**
     * 解析路径。接受 `m/44'/501'/0'/0'`、`44h/501h/...`、`44/501/...` 等形式，
     * 返回未硬化的索引列表。
     *
     * 解析刻意**严格**：`44''`、`44` 混写、`//` 空段都直接报错，而不是「尽量理解」。
     * 派生路径是与硬件钱包对齐的接口，静默纠正拼写错误会产出「另一个钱包里不存在的地址」。
     */
    fun parsePath(path: String): List<Int> {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "m" || trimmed == "M") return emptyList()
        val body = trimmed.removePrefix("m/").removePrefix("M/")
        if (body.isBlank()) return emptyList()
        return body.split("/").map { segment -> parseSegment(segment) }
    }

    /** 一个路径片段：十进制数字 + 可选的单个硬化标记。 */
    private fun parseSegment(segment: String): Int {
        val match = SEGMENT.matchEntire(segment.trim())
            ?: throw IllegalArgumentException("非法的派生路径片段: \"$segment\"")
        val value = match.groupValues[1].toLongOrNull()
            ?: throw IllegalArgumentException("非法的派生路径片段: \"$segment\"")
        require(value in 0..0x7FFFFFFFL) { "派生索引越界: $segment" }
        return value.toInt()
    }

    private fun ser32(i: Int): ByteArray = byteArrayOf(
        (i ushr 24).toByte(),
        (i ushr 16).toByte(),
        (i ushr 8).toByte(),
        i.toByte(),
    )
}
