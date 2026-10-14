package com.lotuswallet.app.data

import com.lotuswallet.app.security.SessionKeyStore
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.DefaultDispatcher
import com.lotuswallet.core.crypto.Aead
import com.lotuswallet.core.crypto.Base32
import com.lotuswallet.core.crypto.OtpEngine
import com.lotuswallet.core.database.dao.OtpDao
import com.lotuswallet.core.database.entity.OtpEntryEntity
import com.lotuswallet.core.database.entity.OtpType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次性口令仓库 —— 本产品必须保留的两大模块之二。
 *
 * ## 与旧版的差别
 * 旧版把 OTP 密钥明文（或用一个可预测的密钥加密）存在本地。
 * 这里密钥用 DEK 走 AES-256-GCM 加密，AAD 绑定条目 id。
 *
 * ## 关于「每秒重算」
 * 验证码每秒都要刷新。为了让 UI 不每秒都去解密数据库，
 * [entriesWithSecrets] 在列表加载时一次性解密，明文密钥只留在 ViewModel 的内存里；
 * 每秒的刷新只是对内存中的密钥做 HMAC 计算。
 */
@Singleton
class OtpRepository @Inject constructor(
    private val otpDao: OtpDao,
    private val session: SessionKeyStore,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) {

    /** 条目 + 已解密的密钥（仅内存）。 */
    data class UnlockedEntry(
        val entity: OtpEntryEntity,
        val secret: String,
    )

    fun entries(): Flow<List<OtpEntryEntity>> = otpDao.observeAll()

    suspend fun allEntries(): List<OtpEntryEntity> = otpDao.all()

    suspend fun entry(id: String): OtpEntryEntity? = otpDao.findById(id)

    suspend fun count(): Int = otpDao.count()

    /** 一次性解密全部密钥。列表加载时调用。 */
    suspend fun entriesWithSecrets(): List<UnlockedEntry> = withContext(cpu) {
        val dek = session.requireDek()
        otpDao.all().map { entity ->
            UnlockedEntry(
                entity = entity,
                secret = Aead.decryptString(dek, entity.secretCipher, aad(entity.id)),
            )
        }
    }

    suspend fun secretOf(id: String): String = withContext(cpu) {
        val entity = otpDao.findById(id)
            ?: throw AppException(AppError.InvalidInput("条目不存在"))
        Aead.decryptString(session.requireDek(), entity.secretCipher, aad(entity.id))
    }

    /**
     * 新建或更新条目。
     *
     * @param secret Base32 密钥；会先规范化（去空格、转大写）再落库，
     *               避免用户从网站复制时带上空格或小写导致后续解码不一致。
     */
    suspend fun save(
        entryId: String?,
        issuer: String?,
        accountName: String,
        secret: String,
        type: OtpType,
        algorithm: String,
        digits: Int,
        periodSeconds: Int,
        counter: Long,
    ): String = withContext(cpu) {
        val normalized = normalizeSecret(secret)
        if (normalized.isEmpty() || Base32.decodeLenient(normalized).isEmpty()) {
            throw AppException(AppError.InvalidInput("OTP 密钥不是合法的 Base32"))
        }
        val now = System.currentTimeMillis()
        val id = entryId ?: newId()
        val existing = entryId?.let { otpDao.findById(it) }

        otpDao.upsert(
            OtpEntryEntity(
                id = id,
                issuer = issuer?.trim()?.takeIf { it.isNotEmpty() },
                accountName = accountName.trim().ifBlank { "未命名账号" },
                secretCipher = Aead.encryptString(session.requireDek(), normalized, aad(id)),
                type = type,
                algorithm = algorithm,
                digits = digits.coerceIn(6, 8),
                periodSeconds = periodSeconds.coerceIn(1, 300),
                counter = counter.coerceAtLeast(0),
                iconUrl = existing?.iconUrl,
                favorite = existing?.favorite ?: false,
                orderIndex = existing?.orderIndex ?: 0,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        id
    }

    suspend fun delete(id: String) = otpDao.deleteById(id)

    suspend fun setFavorite(id: String, favorite: Boolean) =
        otpDao.setFavorite(id, favorite, System.currentTimeMillis())

    /** HOTP 计数器 +1（每次成功使用后必须推进，否则会重复）。 */
    suspend fun incrementCounter(id: String): Long {
        val entity = otpDao.findById(id) ?: throw AppException(AppError.InvalidInput("条目不存在"))
        val next = entity.counter + 1
        otpDao.setCounter(id, next, System.currentTimeMillis())
        return next
    }

    suspend fun setCounter(id: String, counter: Long) =
        otpDao.setCounter(id, counter, System.currentTimeMillis())

    /** 新建条目时用的随机密钥。 */
    fun randomSecret(bits: Int = 160): String = OtpEngine.generateSecret(bits)

    /** 解析 otpauth:// 链接或二维码内容。 */
    fun parseOtpAuth(uri: String): OtpEngine.OtpAuth = OtpEngine.parseUri(uri)

    fun buildOtpAuth(entry: OtpEntryEntity, secret: String): String = OtpEngine.buildUri(
        label = entry.accountName,
        secret = secret,
        issuer = entry.issuer,
        algorithm = OtpEngine.Algorithm.fromOtpAuth(entry.algorithm),
        digits = entry.digits,
        periodSeconds = entry.periodSeconds,
        // counter 为 null 时生成 totp，非 null 时生成 hotp —— 与 OtpEngine.buildUri 的约定一致。
        counter = if (entry.type == OtpType.HOTP) entry.counter else null,
    )

    /** 去掉空格/连字符并转大写 —— 用户从网页复制密钥时经常带上这些字符。 */
    fun normalizeSecret(raw: String): String =
        raw.filter { it.isLetterOrDigit() }.uppercase()

    private fun aad(entryId: String): ByteArray = entryId.toByteArray(Charsets.UTF_8)
}
