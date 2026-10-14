package com.lotuswallet.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
import com.lotuswallet.core.database.entity.VaultFieldType
import com.lotuswallet.core.database.entity.VaultItemEntity
import com.lotuswallet.core.database.entity.VaultItemType
import com.lotuswallet.core.database.entity.WalletEntity
import com.lotuswallet.core.database.entity.WalletType

/**
 * 本地数据库。
 *
 * ## 存储原则
 * - **只存密文与元数据**：助记词、私钥、OTP 密钥、保险库字段值全部以密文落库；
 *   解密所需的 DEK 只存在于内存，由用户主密码或生物识别解封。
 * - **不存任何用户标识**：没有用户表、没有账号、没有设备绑定。
 *
 * ## 迁移策略
 * 每个版本都必须显式提供 `Migration`。禁止使用 `fallbackToDestructiveMigration()`
 * —— 对钱包应用而言，静默丢库等于丢资产。
 */
@Database(
    entities = [
        WalletEntity::class,
        AccountEntity::class,
        VaultItemEntity::class,
        VaultFieldEntity::class,
        OtpEntryEntity::class,
        TokenEntity::class,
        AddressBookEntity::class,
    ],
    version = LotusDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(LotusConverters::class)
abstract class LotusDatabase : RoomDatabase() {

    abstract fun walletDao(): WalletDao

    abstract fun accountDao(): AccountDao

    abstract fun vaultDao(): VaultDao

    abstract fun otpDao(): OtpDao

    abstract fun tokenDao(): TokenDao

    abstract fun addressBookDao(): AddressBookDao

    companion object {
        const val VERSION = 1
        const val NAME = "lotuswallet.db"

        /**
         * 禁止在发布版本上开启 `fallbackToDestructiveMigration`。
         * 这里只允许显式迁移：`addMigrations(*Migrations.ALL)`。
         */
        fun build(context: Context): LotusDatabase =
            Room.databaseBuilder(context.applicationContext, LotusDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

/** 所有历史迁移的登记处。新增版本时在此追加，并在 `schemas/` 下留下 JSON。 */
object Migrations {
    val ALL: Array<androidx.room.migration.Migration> = emptyArray()
}

/**
 * 枚举转换器。
 *
 * 显式指定存储名（而不是依赖 `ordinal`），保证枚举顺序调整后历史数据仍可读。
 */
class LotusConverters {

    @TypeConverter
    fun walletTypeToString(value: WalletType): String = value.name

    @TypeConverter
    fun stringToWalletType(value: String): WalletType =
        runCatching { WalletType.valueOf(value) }.getOrDefault(WalletType.MNEMONIC)

    @TypeConverter
    fun vaultItemTypeToString(value: VaultItemType): String = value.name

    @TypeConverter
    fun stringToVaultItemType(value: String): VaultItemType =
        runCatching { VaultItemType.valueOf(value) }.getOrDefault(VaultItemType.CUSTOM)

    @TypeConverter
    fun vaultFieldTypeToString(value: VaultFieldType): String = value.name

    @TypeConverter
    fun stringToVaultFieldType(value: String): VaultFieldType =
        runCatching { VaultFieldType.valueOf(value) }.getOrDefault(VaultFieldType.TEXT)

    @TypeConverter
    fun otpTypeToString(value: OtpType): String = value.name

    @TypeConverter
    fun stringToOtpType(value: String): OtpType =
        runCatching { OtpType.valueOf(value) }.getOrDefault(OtpType.TOTP)
}
