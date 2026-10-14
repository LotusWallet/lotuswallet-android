package com.lotuswallet.core.crypto

import com.lotuswallet.core.common.Bytes
import java.math.BigInteger

/**
 * BIP-32 分层确定性密钥推导，附带 BIP-44 路径工具。
 *
 * BTC / ETH 及所有 EVM 链 / TRON 都基于同一 secp256k1 曲线，
 * 因此共享同一份 BIP-32 实现，仅在「路径 + 地址编码」上做区分。
 */
object Bip32 {

    private val MASTER_SECRET = "Bitcoin seed".toByteArray(Charsets.US_ASCII)
    private const val HARDENED_OFFSET = 0x80000000.toInt()

    /** 路径片段：十进制数字，可选一个硬化标记（`'` / `h` / `H`）。 */
    private val SEGMENT = Regex("^([0-9]+)(['hH]?)$")

    /** 主网 xprv / xpub 版本号（Base58Check 前缀）。 */
    private val XPRV_VERSION = byteArrayOf(0x04, 0x88.toByte(), 0xAD.toByte(), 0xE4.toByte())
    private val XPUB_VERSION = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)

    /** 扩展私钥。 */
    data class ExtendedPrivateKey(
        val privateKey: ByteArray,
        val chainCode: ByteArray,
        val depth: Int,
        val parentFingerprint: Int,
        val childNumber: Int,
    ) {

        val publicKey: ByteArray get() = Secp256k1.publicKeyCompressed(privateKey)

        /** 公钥指纹：hash160(pubkey) 的前 4 字节。 */
        val fingerprint: Int
            get() = BigInteger(1, Hash.hash160(publicKey).copyOfRange(0, 4)).toInt()

        val isHardened: Boolean get() = childNumber and HARDENED_OFFSET != 0

        /** 按索引派生子密钥。索引 >= 0x80000000 表示硬化派生。 */
        fun derive(index: Int): ExtendedPrivateKey {
            val data = if (index and HARDENED_OFFSET != 0) {
                byteArrayOf(0x00) + privateKey + ser32(index)
            } else {
                publicKey + ser32(index)
            }
            val i = Hash.hmacSha512(chainCode, data)
            val il = i.copyOfRange(0, 32)
            val ir = i.copyOfRange(32, 64)
            val child = parse256(il).add(BigInteger(1, privateKey)).mod(Secp256k1.N)
            require(child.signum() != 0) { "派生出无效私钥（概率极低），请更换助记词" }
            return ExtendedPrivateKey(
                privateKey = Bytes.leftPad(child.toByteArray().trimSign(), 32),
                chainCode = ir,
                depth = depth + 1,
                parentFingerprint = fingerprint,
                childNumber = index,
            )
        }

        /** 派生路径，例如 `m/44'/0'/0'/0/0`。 */
        fun derive(path: String): ExtendedPrivateKey {
            var key = this
            for (index in Bip32.parsePath(path)) key = key.derive(index)
            return key
        }

        fun serialize(): String = Base58.encodeChecked(
            XPRV_VERSION + byteArrayOf(
                depth.toByte(),
                (parentFingerprint ushr 24).toByte(),
                (parentFingerprint ushr 16).toByte(),
                (parentFingerprint ushr 8).toByte(),
                parentFingerprint.toByte(),
            ) + ser32(childNumber) + chainCode + byteArrayOf(0x00) + privateKey
        )

        /** 导出对应的扩展公钥（xpub），可用于只读观察钱包。 */
        fun serializePublic(): String = Base58.encodeChecked(
            XPUB_VERSION + byteArrayOf(
                depth.toByte(),
                (parentFingerprint ushr 24).toByte(),
                (parentFingerprint ushr 16).toByte(),
                (parentFingerprint ushr 8).toByte(),
                parentFingerprint.toByte(),
            ) + ser32(childNumber) + chainCode + publicKey
        )

        override fun equals(other: Any?): Boolean =
            other is ExtendedPrivateKey && privateKey.contentEquals(other.privateKey) &&
                chainCode.contentEquals(other.chainCode)

        override fun hashCode(): Int = privateKey.contentHashCode() * 31 + chainCode.contentHashCode()
    }

    /** 由种子构造主密钥。种子应来自 [Bip39.mnemonicToSeed]（64 字节）。 */
    fun fromSeed(seed: ByteArray): ExtendedPrivateKey {
        require(seed.size in 16..64) { "种子长度应在 16..64 字节，实际 ${seed.size}" }
        val i = Hash.hmacSha512(MASTER_SECRET, seed)
        return ExtendedPrivateKey(
            privateKey = i.copyOfRange(0, 32),
            chainCode = i.copyOfRange(32, 64),
            depth = 0,
            parentFingerprint = 0,
            childNumber = 0,
        )
    }

    /** 由助记词直接得到主密钥。 */
    fun fromMnemonic(mnemonic: String, passphrase: String = ""): ExtendedPrivateKey =
        fromSeed(Bip39.mnemonicToSeed(mnemonic, passphrase))

    /**
     * 解析路径，返回各级索引（已含硬化位）。
     * 接受 `m/44'/60'/0'/0/0`、`44h/60h/...`、`44/60/...` 等形式。
     *
     * 与 [Slip10.parsePath] 一样刻意严格：多写一个引号（`44''`）会报错而不是被忽略。
     * 派生路径是与硬件钱包对齐的接口，静默容错会派生出一批「别的钱包里不存在的地址」。
     */
    fun parsePath(path: String): List<Int> {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "m" || trimmed == "M") return emptyList()
        val body = trimmed.removePrefix("m/").removePrefix("M/")
        if (body.isBlank()) return emptyList()
        return body.split("/").map { segment ->
            val match = SEGMENT.matchEntire(segment.trim())
                ?: throw IllegalArgumentException("非法的派生路径片段: \"$segment\"")
            val value = match.groupValues[1].toLongOrNull()
                ?: throw IllegalArgumentException("非法的派生路径片段: \"$segment\"")
            require(value in 0..0x7FFFFFFFL) { "派生索引越界: $segment" }
            val hardened = match.groupValues[2].isNotEmpty()
            if (hardened) value.toInt() or HARDENED_OFFSET else value.toInt()
        }
    }

    /** 生成 BIP-44 路径字符串（不含 `m/` 前缀由调用方决定）。 */
    fun bip44Path(coinType: Int, account: Int = 0, change: Int = 0, index: Int = 0, purpose: Int = 44): String =
        "$purpose'/$coinType'/$account'/$change/$index"

    private fun ser32(i: Int): ByteArray = byteArrayOf(
        (i ushr 24).toByte(),
        (i ushr 16).toByte(),
        (i ushr 8).toByte(),
        i.toByte(),
    )

    private fun parse256(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    private fun ByteArray.trimSign(): ByteArray =
        if (size > 1 && this[0].toInt() == 0) copyOfRange(1, size) else this
}

/**
 * SLIP-0044 注册的币种编号。新增链时只需在此登记并复用同一套 BIP-32 推导。
 */
object CoinType {
    const val BITCOIN = 0
    const val LITECOIN = 2
    const val DOGECOIN = 3
    const val ETHEREUM = 60
    const val BSC = 60
    const val POLYGON = 60
    const val TRON = 195
}
