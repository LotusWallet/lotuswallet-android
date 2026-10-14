package com.lotuswallet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lotuswallet.core.database.entity.AccountEntity
import com.lotuswallet.core.database.entity.AddressBookEntity
import com.lotuswallet.core.database.entity.OtpEntryEntity
import com.lotuswallet.core.database.entity.TokenEntity
import com.lotuswallet.core.database.entity.VaultFieldEntity
import com.lotuswallet.core.database.entity.VaultItemEntity
import com.lotuswallet.core.database.entity.VaultItemType
import com.lotuswallet.core.database.entity.WalletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(wallet: WalletEntity)

    @Update
    suspend fun update(wallet: WalletEntity)

    @Query("SELECT * FROM wallets ORDER BY orderIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun all(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE id = :id")
    suspend fun findById(id: String): WalletEntity?

    @Query("SELECT * FROM wallets WHERE id = :id")
    fun observeById(id: String): Flow<WalletEntity?>

    @Query("DELETE FROM wallets WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 备份还原用：整表替换。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(wallets: List<WalletEntity>)

    @Query("DELETE FROM wallets")
    suspend fun deleteAll()

    @Query("UPDATE wallets SET backedUp = :backedUp, updatedAt = :timestamp WHERE id = :id")
    suspend fun setBackedUp(id: String, backedUp: Boolean, timestamp: Long)

    @Query("UPDATE wallets SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun setOrder(id: String, orderIndex: Int)

    @Query("SELECT COUNT(*) FROM wallets")
    suspend fun count(): Int
}

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts ORDER BY orderIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE walletId = :walletId ORDER BY orderIndex ASC, createdAt ASC")
    fun observeByWallet(walletId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE chainId = :chainId ORDER BY createdAt ASC")
    fun observeByChain(chainId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE chainId = :chainId")
    suspend fun byChain(chainId: String): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun findById(id: String): AccountEntity?

    @Query(
        "SELECT * FROM accounts WHERE walletId = :walletId AND chainId = :chainId AND address = :address LIMIT 1",
    )
    suspend fun find(walletId: String, chainId: String, address: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun all(): List<AccountEntity>

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM accounts WHERE walletId = :walletId")
    suspend fun deleteByWallet(walletId: String)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}

@Dao
interface VaultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: VaultItemEntity)

    @Query("SELECT * FROM vault_items ORDER BY favorite DESC, orderIndex ASC, updatedAt DESC")
    fun observeItems(): Flow<List<VaultItemEntity>>

    @Query(
        "SELECT * FROM vault_items WHERE type = :type " +
            "ORDER BY favorite DESC, orderIndex ASC, updatedAt DESC",
    )
    fun observeItemsByType(type: VaultItemType): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items ORDER BY favorite DESC, updatedAt DESC")
    suspend fun allItems(): List<VaultItemEntity>

    /** 备份还原用：整表替换。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<VaultItemEntity>)

    @Query("DELETE FROM vault_items")
    suspend fun deleteAllItems()

    @Query("DELETE FROM vault_fields")
    suspend fun deleteAllFields()

    /** 标题级别的快速过滤（值级搜索需要解密，由仓库层在内存中完成）。 */
    @Query(
        "SELECT * FROM vault_items WHERE title LIKE '%' || :query || '%' " +
            "OR IFNULL(subtitle, '') LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC",
    )
    suspend fun searchItems(query: String): List<VaultItemEntity>

    @Query("SELECT * FROM vault_items WHERE id = :id")
    suspend fun findItem(id: String): VaultItemEntity?

    @Query("SELECT * FROM vault_items WHERE id = :id")
    fun observeItem(id: String): Flow<VaultItemEntity?>

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("SELECT COUNT(*) FROM vault_items WHERE type = :type")
    suspend fun countByType(type: VaultItemType): Int

    @Query("SELECT COUNT(*) FROM vault_items")
    suspend fun countItems(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFields(fields: List<VaultFieldEntity>)
    @Query("SELECT * FROM vault_fields WHERE itemId = :itemId ORDER BY orderIndex ASC")
    suspend fun fieldsOf(itemId: String): List<VaultFieldEntity>

    @Query("SELECT * FROM vault_fields WHERE itemId = :itemId ORDER BY orderIndex ASC")
    fun observeFields(itemId: String): Flow<List<VaultFieldEntity>>

    @Query("DELETE FROM vault_fields WHERE itemId = :itemId")
    suspend fun deleteFields(itemId: String)
}

@Dao
interface OtpDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: OtpEntryEntity)

    @Query("SELECT * FROM otp_entries ORDER BY favorite DESC, orderIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<OtpEntryEntity>>

    @Query("SELECT * FROM otp_entries ORDER BY favorite DESC, orderIndex ASC, createdAt ASC")
    suspend fun all(): List<OtpEntryEntity>

    @Query("SELECT * FROM otp_entries WHERE id = :id")
    suspend fun findById(id: String): OtpEntryEntity?

    @Query("DELETE FROM otp_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<OtpEntryEntity>)

    @Query("DELETE FROM otp_entries")
    suspend fun deleteAll()

    @Query("UPDATE otp_entries SET counter = :counter, updatedAt = :timestamp WHERE id = :id")
    suspend fun setCounter(id: String, counter: Long, timestamp: Long)

    @Query("UPDATE otp_entries SET favorite = :favorite, updatedAt = :timestamp WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, timestamp: Long)

    @Query("SELECT COUNT(*) FROM otp_entries")
    suspend fun count(): Int
}

@Dao
interface TokenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tokens: List<TokenEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(token: TokenEntity)

    @Query("SELECT * FROM tokens WHERE chainId = :chainId ORDER BY orderIndex ASC")
    fun observeByChain(chainId: String): Flow<List<TokenEntity>>

    @Query("SELECT * FROM tokens ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<TokenEntity>>

    @Query("SELECT * FROM tokens ORDER BY orderIndex ASC")
    suspend fun all(): List<TokenEntity>

    @Query("SELECT * FROM tokens WHERE id = :id")
    suspend fun findById(id: String): TokenEntity?

    @Query("DELETE FROM tokens")
    suspend fun deleteAll()

    @Query("DELETE FROM tokens WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tokens WHERE chainId = :chainId AND contractAddress = :contract")
    suspend fun deleteByContract(chainId: String, contract: String)
}

@Dao
interface AddressBookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AddressBookEntity)

    @Query("SELECT * FROM address_book ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AddressBookEntity>>

    @Query("SELECT * FROM address_book ORDER BY createdAt DESC")
    suspend fun all(): List<AddressBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<AddressBookEntity>)

    @Query("DELETE FROM address_book")
    suspend fun deleteAll()

    @Query(
        "SELECT * FROM address_book WHERE name LIKE '%' || :query || '%' " +
            "OR address LIKE '%' || :query || '%' ORDER BY createdAt DESC",
    )
    suspend fun search(query: String): List<AddressBookEntity>

    @Query("SELECT * FROM address_book WHERE address = :address LIMIT 1")
    suspend fun findByAddress(address: String): AddressBookEntity?

    @Query("DELETE FROM address_book WHERE id = :id")
    suspend fun deleteById(id: String)
}
