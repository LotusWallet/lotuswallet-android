package com.lotuswallet.app.navigation

/**
 * 全部导航路由。
 *
 * 约定：
 * - 顶层 Tab 的路由（[WALLET] / [VAULT] / [MARKET] / [OTP] / [MINE]）是**无参数**的短字符串，
 *   因为 [AdaptiveShell] 要靠它们判断「当前是否在 Tab 页」来决定显不显示导航栏；
 * - 二级页面一律带参数占位，参数集中在这里定义，避免各处硬编码字符串拼错。
 */
object Routes {

    // ---------------------------------------------------------------- 顶层 Tab
    const val WALLET = "wallet"
    const val VAULT = "vault"
    const val MARKET = "market"
    const val OTP = "otp"
    const val MINE = "mine"

    val topLevel: List<String> = listOf(WALLET, VAULT, MARKET, OTP, MINE)

    // ---------------------------------------------------------------- 钱包
    const val ADD_WALLET = "add_wallet"
    const val CHAINS = "chains"

    /**
     * 链的新建 / 编辑。
     *
     * `chainId` 用 [NEW] 表示「新增」；`family` 只在新增时有意义（决定表单显示哪些字段）。
     * 与 [VAULT_EDIT] 同样刻意避开空路由段。
     */
    const val CHAIN_EDIT = "chain_edit/{chainId}?family={family}"

    fun chainEdit(chainId: String? = null, family: String? = null): String {
        val base = "chain_edit/${chainId ?: NEW}"
        return if (family.isNullOrBlank()) base else "$base?family=$family"
    }

    const val WALLET_DETAIL = "wallet_detail/{walletId}"
    fun walletDetail(walletId: String) = "wallet_detail/$walletId"

    const val ACCOUNT_DETAIL = "account_detail/{accountId}"
    fun accountDetail(accountId: String) = "account_detail/$accountId"

    const val SEND = "send/{accountId}?tokenId={tokenId}"

    /** tokenId 为空时**完全不拼查询串** —— 空值查询串在各版本导航库上的匹配行为不一致。 */
    fun send(accountId: String, tokenId: String? = null): String {
        val base = "send/$accountId"
        return if (tokenId.isNullOrBlank()) base else "$base?tokenId=$tokenId"
    }

    const val MNEMONIC_BACKUP = "mnemonic_backup/{walletId}"
    fun mnemonicBackup(walletId: String) = "mnemonic_backup/$walletId"

    // ---------------------------------------------------------------- 保险库
    const val VAULT_DETAIL = "vault_detail/{itemId}"
    fun vaultDetail(itemId: String) = "vault_detail/$itemId"

    /**
     * 新建 / 编辑保险库条目。
     *
     * 「新建」用常量 [NEW] 占位而不是空串 —— 导航库的路由段正则要求至少一个字符，
     * `vault_edit/` 这种空段根本匹配不到，会直接抛异常。
     */
    const val VAULT_EDIT = "vault_edit/{itemId}?type={type}"

    fun vaultEdit(itemId: String? = null, type: String? = null): String {
        val base = "vault_edit/${itemId ?: NEW}"
        return if (type.isNullOrBlank()) base else "$base?type=$type"
    }

    const val PASSWORD_GENERATOR = "password_generator"

    // ---------------------------------------------------------------- OTP
    const val OTP_EDIT = "otp_edit/{entryId}"
    fun otpEdit(entryId: String? = null) = "otp_edit/${entryId ?: NEW}"

    /** 「新建」占位符。 */
    const val NEW = "new"

    // ---------------------------------------------------------------- 我的
    const val SETTINGS = "settings"
    const val SECURITY = "security"
    const val BACKUP = "backup"
    const val ADDRESS_BOOK = "address_book"
    const val ABOUT = "about"
}
