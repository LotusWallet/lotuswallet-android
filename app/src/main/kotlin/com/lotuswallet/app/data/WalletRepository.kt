package com.lotuswallet.app.data

import com.lotuswallet.app.security.SessionKeyStore
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.DefaultDispatcher
import com.lotuswallet.core.crypto.Aead
import com.lotuswallet.core.crypto.Bip39
import com.lotuswallet.core.database.dao.AccountDao
import com.lotuswallet.core.database.dao.WalletDao
import com.lotuswallet.core.database.entity.AccountEntity
import com.lotuswallet.core.database.entity.WalletEntity
import com.lotuswallet.core.database.entity.WalletSecret
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.network.chain.ChainConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 钱包与账户仓库。
 *
 * ## 密钥的落盘形式
 * `WalletSecret`（助记词 / 私钥）先序列化成 JSON，再用 DEK 走 AES-256-GCM 加密，
 * AAD 绑定钱包 id —— 于是「把 A 钱包的密文剪切到 B 钱包」会立刻解密失败，
 * 而不是悄悄变成另一个钱包的密钥。
 *
 * 明文只存在于 [SessionKeyStore] 已解锁期间的内存里。
 */
@Singleton
class WalletRepository @Inject constructor(
    private val walletDao: WalletDao,
    private val accountDao: AccountDao,
    private val session: SessionKeyStore,
    private val json: Json,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) {

    fun wallets(): Flow<List<WalletEntity>> = walletDao.observeAll()

    fun accounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    fun accountsOf(walletId: String): Flow<List<AccountEntity>> = accountDao.observeByWallet(walletId)

    suspend fun wallet(id: String): WalletEntity? = walletDao.findById(id)

    suspend fun account(id: String): AccountEntity? = accountDao.findById(id)

    suspend fun count(): Int = walletDao.count()

    // ------------------------------------------------------------ 创建 / 导入

    /** 生成一个新助记词（默认 128 bit = 12 个词）。 */
    fun generateMnemonic(strengthBits: Int = 128): String = Bip39.generate(strengthBits)

    fun isValidMnemonic(mnemonic: String): Boolean = Bip39.validate(mnemonic)

    /**
     * 由助记词创建钱包，并为 [chains] 中的每条链派生第 0 个地址。
     *
     * @return 新建钱包的 id
     */
    suspend fun createMnemonicWallet(
        name: String,
        mnemonic: String,
        passphrase: String? = null,
        chains: List<ChainConfig>,
    ): String {
        val normalized = mnemonic.trim().lowercase().replace(Regex("\\s+"), " ")
        if (!Bip39.validate(normalized)) {
            throw AppException(AppError.InvalidInput("助记词校验和或词表不匹配"))
        }
        return create(
            name = name,
            type = WalletType.MNEMONIC,
            secret = WalletSecret(mnemonic = normalized, passphrase = passphrase?.takeIf { it.isNotEmpty() }),
            chains = chains,
        )
    }

    /** 导入单个私钥。只支持单地址（index = 0）。 */
    suspend fun importPrivateKey(name: String, privateKeyHex: String, chains: List<ChainConfig>): String {
        val clean = privateKeyHex.trim().removePrefix("0x").removePrefix("0X")
        if (clean.length != 64 || clean.any { it !in "0123456789abcdefABCDEF" }) {
            throw AppException(AppError.InvalidInput("私钥应为 64 位十六进制"))
        }
        return create(
            name = name,
            type = WalletType.PRIVATE_KEY,
            secret = WalletSecret(privateKeyHex = clean.lowercase()),
            chains = chains,
        )
    }

    /** 导入观察地址，不可签名。 */
    suspend fun importWatchOnly(name: String, chain: ChainConfig, address: String): String {
        val trimmed = address.trim()
        val walletId = newId()
        val now = System.currentTimeMillis()
        val dek = session.requireDek()

        withContext(cpu) {
            walletDao.upsert(
                WalletEntity(
                    id = walletId,
                    name = name.ifBlank { "观察钱包" },
                    type = WalletType.WATCH_ONLY,
                    secretCipher = encryptSecret(dek, walletId, WalletSecret()),
                    backedUp = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            accountDao.upsert(
                AccountEntity(
                    id = newId(),
                    walletId = walletId,
                    chainId = chain.id,
                    address = trimmed,
                    derivationPath = "仅观察（无密钥）",
                    publicKeyHex = null,
                    createdAt = now,
                ),
            )
        }
        return walletId
    }

    private suspend fun create(
        name: String,
        type: WalletType,
        secret: WalletSecret,
        chains: List<ChainConfig>,
    ): String {
        val walletId = newId()
        val now = System.currentTimeMillis()
        val dek = session.requireDek()

        withContext(cpu) {
            walletDao.upsert(
                WalletEntity(
                    id = walletId,
                    name = name.ifBlank { "我的钱包" },
                    type = type,
                    secretCipher = encryptSecret(dek, walletId, secret),
                    backedUp = type != WalletType.MNEMONIC,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            // 每条链派生一个默认地址。EVM 家族共用同一路径，
            // 但仍按链各存一行 —— 这样「某条链不想显示」可以直接删掉对应账户。
            chains.forEachIndexed { index, chain ->
                val derived = AccountDeriver.derive(type, secret, chain, 0)
                accountDao.upsert(
                    AccountEntity(
                        id = newId(),
                        walletId = walletId,
                        chainId = chain.id,
                        address = derived.address,
                        derivationPath = derived.derivationPath,
                        publicKeyHex = derived.publicKeyHex,
                        orderIndex = index,
                        createdAt = now,
                    ),
                )
            }
        }
        return walletId
    }

    // ------------------------------------------------------------ 账户

    /**
     * 为钱包在某条链上追加一个新地址。
     *
     * 索引取「该钱包在该链上已有的账户数」，因此不会与已有地址重复；
     * 观察钱包没有密钥，直接拒绝。
     */
    suspend fun addAccount(walletId: String, chain: ChainConfig): AccountEntity {
        val wallet = walletDao.findById(walletId)
            ?: throw AppException(AppError.InvalidInput("钱包不存在"))
        if (wallet.type == WalletType.WATCH_ONLY) {
            throw AppException(AppError.InvalidInput("观察钱包不能新增地址"))
        }

        val used = accountDao.byChain(chain.id).count { it.walletId == walletId }
        if (wallet.type == WalletType.PRIVATE_KEY && used > 0) {
            throw AppException(AppError.InvalidInput("单私钥钱包只能有一个地址"))
        }
        val index = if (wallet.type == WalletType.PRIVATE_KEY) 0 else used

        val derived = AccountDeriver.derive(wallet.type, secretOf(wallet), chain, index)
        val account = AccountEntity(
            id = newId(),
            walletId = walletId,
            chainId = chain.id,
            address = derived.address,
            derivationPath = derived.derivationPath,
            publicKeyHex = derived.publicKeyHex,
            orderIndex = used,
            createdAt = System.currentTimeMillis(),
        )
        accountDao.upsert(account)
        return account
    }

    suspend fun deleteWallet(walletId: String) {
        // 外键 CASCADE 会一并清掉该钱包的账户
        walletDao.deleteById(walletId)
    }

    suspend fun deleteAccount(accountId: String) = accountDao.deleteById(accountId)

    suspend fun markBackedUp(walletId: String) =
        walletDao.setBackedUp(walletId, true, System.currentTimeMillis())

    suspend fun rename(walletId: String, name: String) {
        val wallet = walletDao.findById(walletId) ?: return
        walletDao.update(wallet.copy(name = name, updatedAt = System.currentTimeMillis()))
    }

    // ------------------------------------------------------------ 密钥读写

    /** 解出钱包的密钥材料。仅在已解锁时可用。 */
    suspend fun secretOf(wallet: WalletEntity): WalletSecret = withContext(cpu) {
        decryptSecret(session.requireDek(), wallet.id, wallet.secretCipher)
    }

    suspend fun secretOf(walletId: String): WalletSecret {
        val wallet = walletDao.findById(walletId)
            ?: throw AppException(AppError.InvalidInput("钱包不存在"))
        return secretOf(wallet)
    }

    // ------------------------------------------------------------ 内部

    private fun encryptSecret(dek: ByteArray, walletId: String, secret: WalletSecret): String =
        Aead.encryptString(
            key = dek,
            plaintext = json.encodeToString(WalletSecret.serializer(), secret),
            associatedData = walletId.toByteArray(Charsets.UTF_8),
        )

    private fun decryptSecret(dek: ByteArray, walletId: String, cipherHex: String): WalletSecret =
        json.decodeFromString(
            WalletSecret.serializer(),
            Aead.decryptString(
                key = dek,
                payloadHex = cipherHex,
                associatedData = walletId.toByteArray(Charsets.UTF_8),
            ),
        )
}
