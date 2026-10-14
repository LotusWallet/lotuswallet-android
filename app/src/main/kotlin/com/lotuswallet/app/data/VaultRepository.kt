package com.lotuswallet.app.data

import com.lotuswallet.app.security.SessionKeyStore
import com.lotuswallet.core.common.DefaultDispatcher
import com.lotuswallet.core.crypto.Aead
import com.lotuswallet.core.database.dao.VaultDao
import com.lotuswallet.core.database.entity.VaultFieldEntity
import com.lotuswallet.core.database.entity.VaultFieldType
import com.lotuswallet.core.database.entity.VaultItemEntity
import com.lotuswallet.core.database.entity.VaultItemType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 加密信息（保险库）仓库 —— 本产品必须保留的两大模块之一。
 *
 * ## 加解密边界
 * - `title` / `subtitle` 明文存储：列表、排序、标题级搜索都要用，且本地库本身已被
 *   Android 文件级加密保护；
 * - **所有字段值**都加密，且 AAD 绑定 `itemId`：把 A 条目的密文挪到 B 条目下会立刻失败。
 *
 * ## 搜索
 * 标题走 SQL（快）；值级搜索需要解密，因此由本仓库在内存里完成。
 * 条目量级是「个人使用」级别（数百条），代价可以接受；如果将来要支持上千条，
 * 应当改为对关键字做 HMAC 索引，而不是把明文落到库里。
 */
@Singleton
class VaultRepository @Inject constructor(
    private val vaultDao: VaultDao,
    private val session: SessionKeyStore,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) {

    /** 编辑草稿：允许 id 为空（新建）。 */
    data class DraftField(
        val localId: String = newId(),
        val label: String = "",
        val value: String = "",
        val sensitive: Boolean = true,
        val type: VaultFieldType = VaultFieldType.TEXT,
    )

    /** 解密后的字段，供详情页展示。 */
    data class PlainField(
        val id: String,
        val label: String,
        val value: String,
        val sensitive: Boolean,
        val type: VaultFieldType,
        val orderIndex: Int,
    )

    fun items(): Flow<List<VaultItemEntity>> = vaultDao.observeItems()

    fun itemsByType(type: VaultItemType): Flow<List<VaultItemEntity>> =
        vaultDao.observeItemsByType(type)

    suspend fun item(id: String): VaultItemEntity? = vaultDao.findItem(id)

    suspend fun fieldsOf(itemId: String): List<VaultFieldEntity> = vaultDao.fieldsOf(itemId)

    /** 解密某条目的全部字段。仅在已解锁时可用。 */
    suspend fun plainFields(itemId: String): List<PlainField> = withContext(cpu) {
        val dek = session.requireDek()
        vaultDao.fieldsOf(itemId).map { entity ->
            PlainField(
                id = entity.id,
                label = entity.label,
                value = Aead.decryptString(dek, entity.valueCipher, aad(itemId)),
                sensitive = entity.sensitive,
                type = entity.fieldType,
                orderIndex = entity.orderIndex,
            )
        }
    }

    /**
     * 保存条目（新建或更新）。
     *
     * 字段采用「先清后写」策略：字段数量少，简单可靠，
     * 避免逐字段 diff 时漏掉删除的字段而留下孤儿密文。
     */
    suspend fun save(
        itemId: String?,
        type: VaultItemType,
        title: String,
        subtitle: String?,
        favorite: Boolean,
        fields: List<DraftField>,
    ): String = withContext(cpu) {
        val dek = session.requireDek()
        val now = System.currentTimeMillis()
        val id = itemId ?: newId()
        val existing = itemId?.let { vaultDao.findItem(it) }

        vaultDao.upsertItem(
            VaultItemEntity(
                id = id,
                type = type,
                title = title.trim().ifBlank { "未命名" },
                subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() },
                favorite = favorite || existing?.favorite == true,
                orderIndex = existing?.orderIndex ?: 0,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )

        vaultDao.deleteFields(id)
        val entities = fields
            .filter { it.label.isNotBlank() || it.value.isNotBlank() }
            .mapIndexed { index, draft ->
                VaultFieldEntity(
                    id = draft.localId,
                    itemId = id,
                    label = draft.label.trim().ifBlank { "字段 ${index + 1}" },
                    valueCipher = Aead.encryptString(dek, draft.value, aad(id)),
                    sensitive = draft.sensitive,
                    fieldType = draft.type,
                    orderIndex = index,
                )
            }
        if (entities.isNotEmpty()) vaultDao.upsertFields(entities)
        id
    }

    suspend fun deleteItem(id: String) = vaultDao.deleteItem(id)

    suspend fun setFavorite(id: String, favorite: Boolean) {
        val item = vaultDao.findItem(id) ?: return
        vaultDao.upsertItem(item.copy(favorite = favorite, updatedAt = System.currentTimeMillis()))
    }

    suspend fun countItems(): Int = vaultDao.countItems()

    suspend fun countsByType(): Map<VaultItemType, Int> =
        VaultItemType.entries.associateWith { vaultDao.countByType(it) }

    /**
     * 搜索：标题命中直接返回；否则解密字段值再匹配。
     * 空查询返回全部。
     */
    suspend fun search(query: String): List<VaultItemEntity> = withContext(cpu) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext vaultDao.allItems()

        val byTitle = vaultDao.searchItems(q).associateBy { it.id }.toMutableMap()
        val all = vaultDao.allItems()
        val dek = session.requireDek()
        for (item in all) {
            if (byTitle.containsKey(item.id)) continue
            val hit = vaultDao.fieldsOf(item.id).any { field ->
                val value = runCatching {
                    Aead.decryptString(dek, field.valueCipher, aad(item.id))
                }.getOrDefault("")
                value.contains(q, ignoreCase = true) || field.label.contains(q, ignoreCase = true)
            }
            if (hit) byTitle[item.id] = item
        }
        byTitle.values.sortedWith(
            compareByDescending<VaultItemEntity> { it.favorite }.thenByDescending { it.updatedAt },
        )
    }

    /** 字段值的 AAD 绑定到条目 id，跨条目搬运密文会解密失败。 */
    private fun aad(itemId: String): ByteArray = itemId.toByteArray(Charsets.UTF_8)
}
