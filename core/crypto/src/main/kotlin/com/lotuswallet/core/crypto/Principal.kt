package com.lotuswallet.core.crypto

/**
 * Internet Computer（ICP）的 principal 编码。
 *
 * ## 它其实不是「地址」
 * ICP 没有账户地址的概念，只有 **principal**（主体标识）。由公钥派生出来的是
 * 「自认证 principal」：
 * ```
 * raw   = sha224(公钥的 DER 编码)
 * 文本  = base32(crc32be(raw) || raw)   小写、无填充
 * ```
 * 于是同一个公钥在任何工具里都能得到同一个 `xxxxx-xxxxx-...` 串。
 *
 * ## 为什么必须带 DER 前缀
 * 规范里的入参是**公钥的 DER/SPKI 编码**而不是 32 字节裸公钥。
 * ed25519 的 SPKI 前缀是固定的 `302a300506032b6570032100`，
 * 少这三个字节会让 sha224 的输入整体错位，算出来的 principal 与官方工具不一致。
 */
object Principal {

    /** ed25519 公钥（裸 32 字节）的 DER SubjectPublicKeyInfo 前缀。 */
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /** 由 32 字节 ed25519 公钥得到自认证 principal 的文本形式。 */
    fun selfAuthenticating(ed25519PublicKey: ByteArray): String {
        require(ed25519PublicKey.size == 32) {
            "ed25519 公钥必须是 32 字节，实际 ${ed25519PublicKey.size}"
        }
        return fromRaw(principalBytes(ED25519_SPKI_PREFIX + ed25519PublicKey))
    }

    /** 裸 principal 字节（28 字节 sha224）。 */
    fun principalBytes(derPublicKey: ByteArray): ByteArray = Hash.sha224(derPublicKey)

    /**
     * 裸 principal → 文本形式：4 字节 CRC32（大端）作为校验和前缀，
     * 整体做 base32（小写、无 `=` 填充），再**每 5 个字符插入一个连字符**。
     *
     * 分组不是装饰：ICP 工具链比较 principal 用的是这个带连字符的规范文本，
     * 少一个连字符就会被判为「不是同一个 principal」。
     * 顺带验证一下已知常量：空 principal 编码出来是 `aaaaa-aa`（管理容器）。
     */
    fun fromRaw(raw: ByteArray): String {
        val crc = Hash.crc32(raw)
        val withChecksum = byteArrayOf(
            (crc ushr 24).toByte(),
            (crc ushr 16).toByte(),
            (crc ushr 8).toByte(),
            crc.toByte(),
        ) + raw
        return Base32.encode(withChecksum, pad = false).lowercase()
            .chunked(5)
            .joinToString("-")
    }

    /** 文本形式 → 裸 principal；校验和不对时返回 null。 */
    fun toRaw(text: String): ByteArray? {
        val compact = text.trim().replace("-", "")
        val decoded = runCatching { Base32.decode(compact.uppercase()) }.getOrNull() ?: return null
        if (decoded.size < 5) return null
        val expected = ((decoded[0].toInt() and 0xFF) shl 24) or
            ((decoded[1].toInt() and 0xFF) shl 16) or
            ((decoded[2].toInt() and 0xFF) shl 8) or
            (decoded[3].toInt() and 0xFF)
        val raw = decoded.copyOfRange(4, decoded.size)
        return if (Hash.crc32(raw) == expected) raw else null
    }

    /** 简单的格式校验（含校验和）。 */
    fun isValid(text: String): Boolean = toRaw(text) != null
}
