package com.lotuswallet.app.data

import com.lotuswallet.app.security.SessionKeyStore
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.DefaultDispatcher
import com.lotuswallet.core.common.IoDispatcher
import com.lotuswallet.core.database.dao.AccountDao
import com.lotuswallet.core.database.dao.AddressBookDao
import com.lotuswallet.core.database.dao.OtpDao
import com.lotuswallet.core.database.dao.TokenDao
import com.lotuswallet.core.database.dao.VaultDao
import com.lotuswallet.core.database.dao.WalletDao
import com.lotuswallet.core.database.entity.AccountEntity
import com.lotuswallet.core.database.entity.AddressBookEntity
import com.lotuswallet.core.database.entity.OtpEntryEntity
import com.lotuswallet.core.database.entity.OtpType
import com.lotuswallet.core.database.entity.TokenEntity
import com.lotuswallet.core.database.entity.VaultFieldEntity
import com.lotuswallet.core.database.entity.VaultItemEntity
import com.lotuswallet.core.database.entity.WalletEntity
import com.lotuswallet.core.crypto.Aead
import com.lotuswallet.core.crypto.OtpEngine
import com.lotuswallet.core.datastore.SettingsRepository
import com.lotuswallet.core.datastore.VaultEnvelopeRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份与迁移。
 *
 * ## 两条导出通道，语义完全不同
 *
 * | 通道 | 内容 | 风险 | 用途 |
 * |---|---|---|---|
 * | **加密快照** | 密钥信封 + 全部**密文**行 | 低：离开主密码就是一堆随机字节 | 换机还原、日常备份 |
 * | **明文导出** | 助记词、私钥、OTP 密钥、保险库字段值 | **极高** | 从旧版迁移、导出到其他认证器 |
 *
 * 明文导出必须由用户显式触发，并且 UI 上要点两次确认；本类只负责生成内容，
 * 「写到哪个文件」交给系统文件选择器（SAF），避免应用自己申请存储权限。
 *
 * ## 为什么快照不加密
 * 快照里没有任何明文 —— 所有敏感字段在库里就已经是密文。
 * 再套一层加密只会让「忘记主密码 = 数据永久丢失」多一次机会，
 * 而安全性并没有实质提升（攻击者拿到快照后真正需要的是主密码）。
 */
@Singleton
class BackupRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val session: SessionKeyStore,
    private val walletDao: WalletDao,
    private val accountDao: AccountDao,
    private val vaultDao: VaultDao,
    private val otpDao: OtpDao,
    private val tokenDao: TokenDao,
    private val addressBookDao: AddressBookDao,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) {

    /** 备份文件格式版本。还原时按版本做兼容。 */
    private companion object {
        const val FORMAT_VERSION = 1
    }

    @Serializable
    data class Snapshot(
        val formatVersion: Int = FORMAT_VERSION,
        val createdAt: Long = System.currentTimeMillis(),
        /** 密钥信封（Argon2id 参数 + 被包裹的 DEK）。没有它，下面的密文一行也解不开。 */
        val envelope: EnvelopeDto,
        val wallets: List<WalletEntity> = emptyList(),
        val accounts: List<AccountEntity> = emptyList(),
        val vaultItems: List<VaultItemEntity> = emptyList(),
        val vaultFields: List<VaultFieldEntity> = emptyList(),
        val otpEntries: List<OtpEntryEntity> = emptyList(),
        val tokens: List<TokenEntity> = emptyList(),
        val addressBook: List<AddressBookEntity> = emptyList(),
    )

    @Serializable
    data class EnvelopeDto(
        val kdfParams: String,
        val wrappedDek: String,
        val version: Int,
    )

    /** 生成加密快照（JSON 文本）。 */
    suspend fun snapshot(): String = withContext(io) {
        val envelope = settings.vaultEnvelope()
            ?: throw AppException(AppError.InvalidInput("保险库尚未初始化，无法备份"))

        val snapshot = Snapshot(
            envelope = EnvelopeDto(envelope.kdfParams, envelope.wrappedDek, envelope.version),
            wallets = walletDao.all(),
            accounts = accountDao.all(),
            vaultItems = vaultDao.allItems(),
            vaultFields = vaultDao.allItems().flatMap { vaultDao.fieldsOf(it.id) },
            otpEntries = otpDao.all(),
            tokens = tokenDao.all(),
            addressBook = addressBookDao.all(),
        )
        json.encodeToString(Snapshot.serializer(), snapshot)
    }

    /**
     * 从快照还原。
     *
     * 必须提供**原主密码**：不仅是为了验证快照可用，更因为还原后要立即进入可用状态，
     * 否则用户会在「已还原但打不开」的状态里困惑。
     *
     * @return 还原的数据条数摘要
     */
    suspend fun restore(raw: String, password: CharArray): RestoreSummary = withContext(io) {
        val snapshot = try {
            json.decodeFromString(Snapshot.serializer(), raw)
        } catch (t: Throwable) {
            throw AppException(AppError.InvalidInput("备份文件格式无法识别"))
        }
        if (snapshot.formatVersion > FORMAT_VERSION) {
            throw AppException(AppError.InvalidInput("备份来自更新的版本（v${snapshot.formatVersion}），请先升级应用"))
        }

        val record = VaultEnvelopeRecord(
            kdfParams = snapshot.envelope.kdfParams,
            wrappedDek = snapshot.envelope.wrappedDek,
            version = snapshot.envelope.version,
        )
        // 先验证密码能解开信封，再落任何数据 —— 否则会留下一个解不开的库
        val dek = withContext(cpu) {
            runCatching {
                com.lotuswallet.core.crypto.VaultCrypto.unlock(
                    password,
                    com.lotuswallet.core.crypto.VaultCrypto.Envelope(
                        record.kdfParams, record.wrappedDek, record.version,
                    ),
                )
            }.getOrElse { throw AppException(AppError.WrongPassword) }
        }

        settings.saveVaultEnvelope(record)

        accountDao.deleteAll()
        walletDao.deleteAll()
        vaultDao.deleteAllFields()
        vaultDao.deleteAllItems()
        otpDao.deleteAll()
        tokenDao.deleteAll()
        addressBookDao.deleteAll()

        walletDao.upsertAll(snapshot.wallets)
        accountDao.upsertAll(snapshot.accounts)
        vaultDao.upsertItems(snapshot.vaultItems)
        if (snapshot.vaultFields.isNotEmpty()) vaultDao.upsertFields(snapshot.vaultFields)
        otpDao.upsertAll(snapshot.otpEntries)
        if (snapshot.tokens.isNotEmpty()) tokenDao.upsertAll(snapshot.tokens)
        addressBookDao.upsertAll(snapshot.addressBook)

        session.put(dek)
        dek.fill(0)

        RestoreSummary(
            wallets = snapshot.wallets.size,
            accounts = snapshot.accounts.size,
            vaultItems = snapshot.vaultItems.size,
            otpEntries = snapshot.otpEntries.size,
        )
    }

    data class RestoreSummary(
        val wallets: Int,
        val accounts: Int,
        val vaultItems: Int,
        val otpEntries: Int,
    )

    // ------------------------------------------------------------ 明文导出

    /** 全部 OTP 条目导出为标准 `otpauth://` URI（可被任何认证器导入）。 */
    suspend fun exportOtpAuthUris(): List<String> = withContext(cpu) {
        val dek = session.requireDek()
        otpDao.all().map { entry ->
            val secret = Aead.decryptString(dek, entry.secretCipher, entry.id.toByteArray(Charsets.UTF_8))
            OtpEngine.buildUri(
                label = entry.accountName,
                secret = secret,
                issuer = entry.issuer,
                algorithm = OtpEngine.Algorithm.fromOtpAuth(entry.algorithm),
                digits = entry.digits,
                periodSeconds = entry.periodSeconds,
                counter = if (entry.type == OtpType.HOTP) entry.counter else null,
            )
        }
    }

    /**
     * 保险库明文导出（Markdown 风格纯文本，便于打印或存入离线介质）。
     * 每一条都带类型与标题，字段按 `标签: 值` 排列。
     */
    suspend fun exportVaultPlaintext(): String = withContext(cpu) {
        val dek = session.requireDek()
        val items = vaultDao.allItems()
        if (items.isEmpty()) return@withContext "# LotusWallet 加密信息导出\n\n（暂无内容）\n"

        buildString {
            appendLine("# LotusWallet 加密信息导出")
            appendLine()
            appendLine("导出时间：${com.lotuswallet.app.util.Formatters.formatDateTime(System.currentTimeMillis())}")
            appendLine("条目数：${items.size}")
            appendLine()
            appendLine("> ⚠️ 本文件为**明文**，包含密码与证件信息，请妥善保管或在使用后销毁。")
            appendLine()
            for (item in items) {
                appendLine("## ${item.title}")
                appendLine("- 类型：${typeLabel(item.type)}")
                item.subtitle?.takeIf { it.isNotBlank() }?.let { appendLine("- 备注：$it") }
                for (field in vaultDao.fieldsOf(item.id)) {
                    val value = runCatching {
                        Aead.decryptString(dek, field.valueCipher, item.id.toByteArray(Charsets.UTF_8))
                    }.getOrDefault("(解密失败)")
                    appendLine("- ${field.label}：$value")
                }
                appendLine()
            }
        }
    }

    /** 助记词 / 私钥明文导出。**只有用户点两次确认后才会到这里。** */
    suspend fun exportWalletSecrets(): String = withContext(cpu) {
        val walletRepository = WalletSecretsReader(walletDao, session, json)
        val wallets = walletDao.all()
        if (wallets.isEmpty()) return@withContext "（暂无钱包）\n"

        buildString {
            appendLine("# LotusWallet 钱包密钥导出")
            appendLine()
            appendLine("> ⚠️ **极高风险**：获得本文件等同于获得你的全部资产。")
            appendLine("> 请勿截图、勿存网盘、勿通过聊天工具发送。建议抄写后立即销毁文件。")
            appendLine()
            for (wallet in wallets) {
                appendLine("## ${wallet.name}（${wallet.id}）")
                appendLine("- 类型：${walletTypeLabel(wallet)}")
                val secret = walletRepository.read(wallet)
                secret.mnemonic?.let { appendLine("- 助记词：$it") }
                secret.passphrase?.takeIf { it.isNotBlank() }?.let { appendLine("- BIP-39 附加口令：$it") }
                secret.privateKeyHex?.let { appendLine("- 私钥：$it") }
                if (secret.mnemonic == null && secret.privateKeyHex == null) {
                    appendLine("- （观察钱包，无私钥）")
                }
                appendLine("- 已备份标记：${if (wallet.backedUp) "是" else "否"}")
                appendLine()
            }
        }
    }

    private fun typeLabel(type: com.lotuswallet.core.database.entity.VaultItemType): String = when (type) {
        com.lotuswallet.core.database.entity.VaultItemType.SECRET_KEY -> "私钥 / 助记词"
        com.lotuswallet.core.database.entity.VaultItemType.IDENTITY -> "身份证件"
        com.lotuswallet.core.database.entity.VaultItemType.BANK_CARD -> "银行卡"
        com.lotuswallet.core.database.entity.VaultItemType.WEBSITE_LOGIN -> "网站登录"
        com.lotuswallet.core.database.entity.VaultItemType.CUSTOM -> "自定义"
    }

    private fun walletTypeLabel(wallet: WalletEntity): String = when (wallet.type) {
        com.lotuswallet.core.database.entity.WalletType.MNEMONIC -> "助记词钱包"
        com.lotuswallet.core.database.entity.WalletType.PRIVATE_KEY -> "单私钥钱包"
        com.lotuswallet.core.database.entity.WalletType.WATCH_ONLY -> "观察钱包"
    }

    /** 只读解密助手（避免把 WalletRepository 整个注入进来产生循环依赖）。 */
    private class WalletSecretsReader(
        private val walletDao: WalletDao,
        private val session: SessionKeyStore,
        private val json: Json,
    ) {
        fun read(wallet: WalletEntity): com.lotuswallet.core.database.entity.WalletSecret =
            json.decodeFromString(
                com.lotuswallet.core.database.entity.WalletSecret.serializer(),
                Aead.decryptString(
                    session.requireDek(),
                    wallet.secretCipher,
                    wallet.id.toByteArray(Charsets.UTF_8),
                ),
            )
    }
}
