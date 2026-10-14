package com.lotuswallet.core.datastore

/**
 * 应用偏好设置（非敏感）。
 *
 * 明确约束：**这里不允许出现任何秘密**。密钥信封（密文）可以放，
 * 但主密码、DEK 明文、助记词一律不得落在这里。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // 默认关闭：壁纸取色会替换品牌青，导致全应用观感不可控（需要个性化时手动打开）
    val dynamicColor: Boolean = false,
    /** 语言标签；null 表示跟随系统。 */
    val language: String? = null,
    /** 计价法币。 */
    val fiatCurrency: String = "CNY",
    /** 是否在列表中隐藏金额（公共场所防偷窥）。 */
    val hideBalances: Boolean = false,
    /** 是否禁止敏感页面截屏。 */
    val screenshotProtection: Boolean = true,
    /** 自动锁定时间（分钟），0 表示立即锁定。 */
    val autoLockMinutes: Int = 5,
    /** 是否启用生物识别解锁。 */
    val biometricEnabled: Boolean = false,
    /** 是否已完成首次引导。 */
    val onboardingCompleted: Boolean = false,
    /** 保险库是否已初始化（即是否已存在密钥信封）。 */
    val vaultInitialized: Boolean = false,
    /** 是否为默认链启用测试网。 */
    val showTestnets: Boolean = false,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 密钥信封（密文）的持久化结构。 */
data class VaultEnvelopeRecord(
    /** Argon2id 参数串（含盐）。 */
    val kdfParams: String,
    /** 被 KEK 包裹后的 DEK。 */
    val wrappedDek: String,
    /** 信封版本。 */
    val version: Int,
)

/** 生物识别路径下被硬件密钥包裹的 DEK。 */
data class BiometricRecord(
    val wrappedDek: String,
    val keyAlias: String,
)
