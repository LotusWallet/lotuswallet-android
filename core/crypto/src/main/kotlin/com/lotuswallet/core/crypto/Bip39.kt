package com.lotuswallet.core.crypto

import java.text.Normalizer

/**
 * BIP-39 助记词。
 *
 * 与旧版实现的差异：
 * - 严格校验校验和（旧版缺失，导致错误助记词被接受）；
 * - 按规范做 NFKD 归一化（非 ASCII 口令必须处理，否则跨钱包恢复不一致）；
 * - 词表内置在资源中，可离线工作。
 */
object Bip39 {

    private const val WORD_COUNT = 2048
    private const val PBKDF2_ROUNDS = 2048
    private const val SEED_LENGTH_BYTES = 64

    val words: List<String> by lazy { loadWordList() }

    private val wordIndex: Map<String, Int> by lazy {
        words.withIndex().associate { (i, w) -> w to i }
    }

    private fun loadWordList(): List<String> {
        val stream = Bip39::class.java.getResourceAsStream("/bip39/english.txt")
            ?: error("缺少 BIP-39 词表资源 /bip39/english.txt")
        val list = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        check(list.size == WORD_COUNT) { "BIP-39 词表应为 $WORD_COUNT 个单词，实际 ${list.size}" }
        return list
    }

    /**
     * 生成新助记词。
     *
     * @param strengthBits 熵长度，必须是 128 / 160 / 192 / 224 / 256 之一。
     *                     128 → 12 词，256 → 24 词。
     */
    fun generate(strengthBits: Int = 128): String {
        require(strengthBits in intArrayOf(128, 160, 192, 224, 256).toList()) {
            "熵长度必须为 128/160/192/224/256 之一，实际 $strengthBits"
        }
        return entropyToMnemonic(SecureRandom.bytes(strengthBits / 8))
    }

    /** 熵 → 助记词。 */
    fun entropyToMnemonic(entropy: ByteArray): String {
        require(entropy.size in 16..32 && entropy.size % 4 == 0) {
            "熵长度必须为 16..32 且是 4 的倍数，实际 ${entropy.size}"
        }
        val entropyBits = entropy.size * 8
        val checksumBits = entropyBits / 32
        val checksum = Hash.sha256(entropy)

        val totalBits = entropyBits + checksumBits
        val indices = IntArray(totalBits / 11)

        for (i in indices.indices) {
            var value = 0
            for (b in 0 until 11) {
                val bitPos = i * 11 + b
                val bit = if (bitPos < entropyBits) {
                    (entropy[bitPos / 8].toInt() ushr (7 - bitPos % 8)) and 1
                } else {
                    val csPos = bitPos - entropyBits
                    (checksum[csPos / 8].toInt() ushr (7 - csPos % 8)) and 1
                }
                value = (value shl 1) or bit
            }
            indices[i] = value
        }
        return indices.joinToString(" ") { words[it] }
    }

    /** 助记词 → 熵（同时校验校验和）。 */
    fun mnemonicToEntropy(mnemonic: String): ByteArray {
        val parts = normalize(mnemonic).split(" ").filter { it.isNotEmpty() }
        require(parts.size in intArrayOf(12, 15, 18, 21, 24)) { "助记词数量非法: ${parts.size}" }

        val totalBits = parts.size * 11
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits

        val bits = BooleanArray(totalBits)
        parts.forEachIndexed { i, word ->
            val idx = wordIndex[word] ?: throw IllegalArgumentException("助记词包含非词表单词: \"$word\"")
            for (b in 0 until 11) {
                bits[i * 11 + b] = (idx ushr (10 - b)) and 1 == 1
            }
        }

        val entropy = ByteArray(entropyBits / 8)
        for (i in 0 until entropyBits) {
            if (bits[i]) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
        }

        val expectedChecksum = Hash.sha256(entropy)
        for (i in 0 until checksumBits) {
            val expected = (expectedChecksum[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1
            if (bits[entropyBits + i] != expected) {
                throw IllegalArgumentException("助记词校验和不匹配（可能是抄写错误）")
            }
        }
        return entropy
    }

    fun validate(mnemonic: String): Boolean = try {
        mnemonicToEntropy(mnemonic)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 助记词 → 种子（BIP-32 的输入）。
     *
     * @param passphrase 可选附加口令（BIP-39 的 "25th word"），通常为空。
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = normalize(mnemonic.trim().split(Regex("\\s+")).joinToString(" "))
        val salt = "mnemonic" + normalize(passphrase)
        return Hash.pbkdf2HmacSha512(
            password = normalizedMnemonic.toCharArray(),
            salt = salt.toByteArray(Charsets.UTF_8),
            iterations = PBKDF2_ROUNDS,
            keyLengthBytes = SEED_LENGTH_BYTES,
        )
    }

    /** 供助记词输入框做逐词联想。 */
    fun suggest(prefix: String, limit: Int = 8): List<String> {
        if (prefix.isBlank()) return emptyList()
        val p = prefix.trim().lowercase()
        return words.asSequence().filter { it.startsWith(p) }.take(limit).toList()
    }

    private fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
}
