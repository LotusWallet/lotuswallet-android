package com.lotuswallet.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "lotus_settings")

/**
 * 偏好设置仓库。全部走 DataStore（协程 + 事务，替代旧版的 SharedPreferences）。
 *
 * 安全约束：本仓库**只存非敏感偏好与密文信封**。
 * 旧版把用于解密数据的 `pblkey` 明文写在 SharedPreferences 里，是一次严重的设计失误，
 * 这里从结构上杜绝（DEK 只能被主密码或硬件密钥包裹后落盘）。
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
            language = prefs[KEY_LANGUAGE],
            fiatCurrency = prefs[KEY_FIAT] ?: "CNY",
            hideBalances = prefs[KEY_HIDE_BALANCES] ?: false,
            screenshotProtection = prefs[KEY_SCREENSHOT_PROTECT] ?: true,
            autoLockMinutes = prefs[KEY_AUTO_LOCK] ?: 5,
            biometricEnabled = prefs[KEY_BIOMETRIC] ?: false,
            onboardingCompleted = prefs[KEY_ONBOARDING_DONE] ?: false,
            vaultInitialized = prefs[KEY_VAULT_INITIALIZED] ?: false,
            showTestnets = prefs[KEY_SHOW_TESTNETS] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setLanguage(tag: String?) = edit { prefs ->
        if (tag == null) prefs.remove(KEY_LANGUAGE) else prefs[KEY_LANGUAGE] = tag
    }

    suspend fun setFiatCurrency(code: String) = edit { it[KEY_FIAT] = code }

    suspend fun setHideBalances(hide: Boolean) = edit { it[KEY_HIDE_BALANCES] = hide }

    suspend fun setScreenshotProtection(enabled: Boolean) = edit { it[KEY_SCREENSHOT_PROTECT] = enabled }

    suspend fun setAutoLockMinutes(minutes: Int) = edit { it[KEY_AUTO_LOCK] = minutes.coerceIn(0, 120) }

    suspend fun setBiometricEnabled(enabled: Boolean) = edit { it[KEY_BIOMETRIC] = enabled }

    suspend fun setOnboardingCompleted(done: Boolean) = edit { it[KEY_ONBOARDING_DONE] = done }

    suspend fun setShowTestnets(show: Boolean) = edit { it[KEY_SHOW_TESTNETS] = show }

    // ---------------------------------------------------------------- 密钥信封

    /** 读取密钥信封；尚未初始化时返回 null。 */
    suspend fun vaultEnvelope(): VaultEnvelopeRecord? {
        val prefs = context.settingsStore.data.first()
        val params = prefs[KEY_ENVELOPE_KDF] ?: return null
        val wrapped = prefs[KEY_ENVELOPE_DEK] ?: return null
        return VaultEnvelopeRecord(
            kdfParams = params,
            wrappedDek = wrapped,
            version = prefs[KEY_ENVELOPE_VERSION] ?: 1,
        )
    }

    suspend fun saveVaultEnvelope(record: VaultEnvelopeRecord) = edit { prefs ->
        prefs[KEY_ENVELOPE_KDF] = record.kdfParams
        prefs[KEY_ENVELOPE_DEK] = record.wrappedDek
        prefs[KEY_ENVELOPE_VERSION] = record.version
        prefs[KEY_VAULT_INITIALIZED] = true
    }

    /** 生物识别路径的被包裹 DEK。 */
    suspend fun biometricRecord(): BiometricRecord? {
        val prefs = context.settingsStore.data.first()
        val wrapped = prefs[KEY_BIOMETRIC_DEK] ?: return null
        return BiometricRecord(wrappedDek = wrapped, keyAlias = prefs[KEY_BIOMETRIC_ALIAS] ?: "")
    }

    suspend fun saveBiometricRecord(record: BiometricRecord) = edit { prefs ->
        prefs[KEY_BIOMETRIC_DEK] = record.wrappedDek
        prefs[KEY_BIOMETRIC_ALIAS] = record.keyAlias
    }

    suspend fun clearBiometricRecord() = edit { prefs ->
        prefs.remove(KEY_BIOMETRIC_DEK)
        prefs.remove(KEY_BIOMETRIC_ALIAS)
        prefs[KEY_BIOMETRIC] = false
    }

    // ---------------------------------------------------------------- 远端清单缓存

    suspend fun lastChainsJson(): String? = context.settingsStore.data.first()[KEY_CHAINS_JSON]

    /** 链清单缓存时间（epoch millis）；从未缓存过时为 0。 */
    suspend fun lastChainsCachedAt(): Long = context.settingsStore.data.first()[KEY_CHAINS_CACHED_AT] ?: 0L

    suspend fun saveChainsJson(raw: String, cachedAt: Long = System.currentTimeMillis()) = edit {
        it[KEY_CHAINS_JSON] = raw
        it[KEY_CHAINS_CACHED_AT] = cachedAt
    }

    suspend fun lastMarketJson(): String? = context.settingsStore.data.first()[KEY_MARKET_JSON]

    /** DApp 清单缓存时间（epoch millis）；从未缓存过时为 0。 */
    suspend fun lastMarketCachedAt(): Long = context.settingsStore.data.first()[KEY_MARKET_CACHED_AT] ?: 0L

    suspend fun saveMarketJson(raw: String, cachedAt: Long = System.currentTimeMillis()) = edit {
        it[KEY_MARKET_JSON] = raw
        it[KEY_MARKET_CACHED_AT] = cachedAt
    }

    // ---------------------------------------------------------------- 用户自定义链

    /** 用户在本机「链管理」里新增/修改过的链清单（JSON）。 */
    suspend fun userChainsJson(): String? = context.settingsStore.data.first()[KEY_USER_CHAINS]

    suspend fun saveUserChainsJson(raw: String?) = edit { prefs ->
        if (raw.isNullOrBlank()) prefs.remove(KEY_USER_CHAINS) else prefs[KEY_USER_CHAINS] = raw
    }

    /**
     * 清空全部偏好（用于「重置应用」）。
     * 注意：**不会**删除数据库与密钥信封，重置需由上层显式处理。
     */
    suspend fun clearAll() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit(block)
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_FIAT = stringPreferencesKey("fiat_currency")
        val KEY_HIDE_BALANCES = booleanPreferencesKey("hide_balances")
        val KEY_SCREENSHOT_PROTECT = booleanPreferencesKey("screenshot_protection")
        val KEY_AUTO_LOCK = intPreferencesKey("auto_lock_minutes")
        val KEY_BIOMETRIC = booleanPreferencesKey("biometric_enabled")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val KEY_VAULT_INITIALIZED = booleanPreferencesKey("vault_initialized")
        val KEY_SHOW_TESTNETS = booleanPreferencesKey("show_testnets")

        val KEY_ENVELOPE_KDF = stringPreferencesKey("vault_envelope_kdf")
        val KEY_ENVELOPE_DEK = stringPreferencesKey("vault_envelope_dek")
        val KEY_ENVELOPE_VERSION = intPreferencesKey("vault_envelope_version")

        val KEY_BIOMETRIC_DEK = stringPreferencesKey("biometric_wrapped_dek")
        val KEY_BIOMETRIC_ALIAS = stringPreferencesKey("biometric_key_alias")

        val KEY_CHAINS_JSON = stringPreferencesKey("cache_chains_json")
        val KEY_CHAINS_CACHED_AT = longPreferencesKey("cache_chains_cached_at")
        val KEY_MARKET_JSON = stringPreferencesKey("cache_market_json")
        val KEY_MARKET_CACHED_AT = longPreferencesKey("cache_market_cached_at")

        val KEY_USER_CHAINS = stringPreferencesKey("user_chains_json")
    }
}
