package com.lotuswallet.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 钱包（密钥容器）。
 *
 * 与旧版 [`WalletEntity`] 的关键区别：把「钱包（一份密钥材料）」与
 * 「账户（由该密钥派生出的某个链上的地址）」拆成两张表。
 * 旧版把助记词、地址、链信息混在一张表里，导致同一助记词在多链下反复存多份，
 * 且新增链时必须改表结构。
 */
@Entity(tableName = "wallets")
@Serializable
data class WalletEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: WalletType,
    /**
     * 密钥材料密文（AES-256-GCM，AAD = id）。
     * 明文结构由 [WalletSecret] 描述，序列化后整体加密 —— 助记词、私钥、xpub 都不落明文。
     */
    val secretCipher: String,
    /** 用户是否已确认备份过助记词。未备份时 UI 会持续提醒。 */
    val backedUp: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class WalletType {
    /** 助记词钱包（HD，支持多链派生）。 */
    MNEMONIC,

    /** 单私钥导入。 */
    PRIVATE_KEY,

    /** 观察钱包（只有地址/xpub，不能签名）。 */
    WATCH_ONLY,
}

/**
 * 钱包密钥材料的**明文**结构，序列化后加密存储。
 *
 * 注意：这个类永远不会被持久化到磁盘，只在内存里短暂存在。
 */
@Serializable
data class WalletSecret(
    /** 助记词（MNEMONIC 类型）。 */
    val mnemonic: String? = null,
    /** BIP-39 附加口令（"第 25 个词"），通常为空。 */
    val passphrase: String? = null,
    /** 单私钥（PRIVATE_KEY 类型），十六进制。 */
    val privateKeyHex: String? = null,
    /** 观察钱包的 xpub。 */
    val xpub: String? = null,
)

/**
 * 账户：某个钱包在某条链上的一个地址。
 */
@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = WalletEntity::class,
            parentColumns = ["id"],
            childColumns = ["walletId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("walletId"),
        Index(value = ["walletId", "chainId", "address"], unique = true),
        Index("chainId"),
    ],
)
@Serializable
data class AccountEntity(
    @PrimaryKey val id: String,
    val walletId: String,
    /** 关联 [com.lotuswallet.core.network.chain.ChainConfig.id]。 */
    val chainId: String,
    val address: String,
    /** BIP-44 派生路径，如 `m/44'/60'/0'/0/0`。 */
    val derivationPath: String,
    /** 压缩公钥（十六进制），便于观察钱包与地址校验。 */
    val publicKeyHex: String? = null,
    val label: String? = null,
    val orderIndex: Int = 0,
    val createdAt: Long,
)

/** 加密信息（保险库）条目的分类。对应旧版的安全信息分类，必须保留。 */
enum class VaultItemType {
    /** 私钥 / 助记词。 */
    SECRET_KEY,

    /** 身份证件（身份证、护照、驾照…）。 */
    IDENTITY,

    /** 银行卡 / 支付信息。 */
    BANK_CARD,

    /** 网站登录信息。 */
    WEBSITE_LOGIN,

    /** 自定义（用户自建模版）。 */
    CUSTOM,
}

/**
 * 保险库条目。
 *
 * 明文只保留 [type]/[title]/[subtitle] —— 用于列表展示、分类与搜索；
 * 所有真正敏感的值在 [VaultFieldEntity.valueCipher] 里加密存放。
 * 这个折中是刻意的：本地库已被 Android 文件级加密保护，而加密每个字段又要牺牲列表性能。
 */
@Entity(tableName = "vault_items", indices = [Index("type"), Index("title")])
@Serializable
data class VaultItemEntity(
    @PrimaryKey val id: String,
    val type: VaultItemType,
    val title: String,
    val subtitle: String? = null,
    val favorite: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 保险库字段类型，决定 UI 用什么控件渲染。 */
enum class VaultFieldType {
    TEXT,
    PASSWORD,
    URL,
    EMAIL,
    PHONE,
    NUMBER,
    DATE,
    MULTILINE,
    TOTP_SECRET,
}

/**
 * 保险库条目的字段。值的密文用 DEK 加密，AAD 绑定到所属条目 id，
 * 因此把某个字段的密文剪切到另一个条目下会立即校验失败。
 */
@Entity(
    tableName = "vault_fields",
    foreignKeys = [
        ForeignKey(
            entity = VaultItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("itemId")],
)
@Serializable
data class VaultFieldEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val label: String,
    /** 字段值密文。 */
    val valueCipher: String,
    val sensitive: Boolean = true,
    val fieldType: VaultFieldType = VaultFieldType.TEXT,
    val orderIndex: Int = 0,
)

/** OTP 类型。 */
enum class OtpType { TOTP, HOTP }

/**
 * 一次性口令条目（对应旧版 `TwostepVerificationEntity`，必须保留）。
 *
 * 密钥同样加密存储；[issuer]/[accountName] 保留明文便于列表展示与排序。
 */
@Entity(tableName = "otp_entries", indices = [Index("issuer"), Index("accountName")])
@Serializable
data class OtpEntryEntity(
    @PrimaryKey val id: String,
    val issuer: String?,
    val accountName: String,
    /** Base32 密钥的密文。 */
    val secretCipher: String,
    val type: OtpType = OtpType.TOTP,
    /** SHA1 / SHA256 / SHA512。 */
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val periodSeconds: Int = 30,
    val counter: Long = 0,
    val iconUrl: String? = null,
    val favorite: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 用户关注/自建的代币。 */
@Entity(
    tableName = "tokens",
    indices = [Index(value = ["chainId", "contractAddress"], unique = true)],
)
@Serializable
data class TokenEntity(
    @PrimaryKey val id: String,
    val chainId: String,
    val contractAddress: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    /** ERC20 / ERC721 / ERC1155 / TRC20 … */
    val standard: String,
    val iconUrl: String? = null,
    val orderIndex: Int = 0,
)

/** 地址簿。 */
@Entity(tableName = "address_book")
@Serializable
data class AddressBookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val chainId: String? = null,
    val memo: String? = null,
    val createdAt: Long,
)
