package com.lotuswallet.app.ui.vault

import com.lotuswallet.core.database.entity.VaultFieldType
import com.lotuswallet.core.database.entity.VaultItemType

/**
 * 各分类的推荐字段模板。
 *
 * ## 为什么要有模板
 * 完全自由的「字段名 + 字段值」对首次使用者是负担：不知道该建哪些字段，
 * 于是要么建得乱七八糟，要么干脆不用。
 * 模板给出一个专业上合理的默认结构（例如银行卡该记卡号、有效期、CVV、发卡行），
 * 用户可以在此基础上增删。
 */
object VaultTemplates {

    data class FieldSpec(
        val label: String,
        val type: VaultFieldType,
        val sensitive: Boolean = true,
    )

    fun fieldsFor(type: VaultItemType): List<FieldSpec> = when (type) {
        VaultItemType.SECRET_KEY -> listOf(
            FieldSpec("助记词 / 私钥", VaultFieldType.MULTILINE),
            FieldSpec("钱包名称", VaultFieldType.TEXT, sensitive = false),
            FieldSpec("派生路径", VaultFieldType.TEXT, sensitive = false),
            FieldSpec("备注", VaultFieldType.MULTILINE, sensitive = false),
        )

        VaultItemType.IDENTITY -> listOf(
            FieldSpec("证件号码", VaultFieldType.TEXT),
            FieldSpec("姓名", VaultFieldType.TEXT, sensitive = false),
            FieldSpec("有效期", VaultFieldType.DATE, sensitive = false),
            FieldSpec("签发机关", VaultFieldType.TEXT, sensitive = false),
            FieldSpec("备注", VaultFieldType.MULTILINE, sensitive = false),
        )

        VaultItemType.BANK_CARD -> listOf(
            FieldSpec("卡号", VaultFieldType.TEXT),
            FieldSpec("有效期", VaultFieldType.DATE, sensitive = false),
            FieldSpec("安全码 CVV", VaultFieldType.PASSWORD),
            FieldSpec("持卡人", VaultFieldType.TEXT, sensitive = false),
            FieldSpec("发卡行", VaultFieldType.TEXT, sensitive = false),
            FieldSpec("取款密码", VaultFieldType.PASSWORD),
            FieldSpec("备注", VaultFieldType.MULTILINE, sensitive = false),
        )

        VaultItemType.WEBSITE_LOGIN -> listOf(
            FieldSpec("网址", VaultFieldType.URL, sensitive = false),
            FieldSpec("用户名", VaultFieldType.TEXT, sensitive = false),
            FieldSpec("密码", VaultFieldType.PASSWORD),
            FieldSpec("绑定邮箱", VaultFieldType.EMAIL, sensitive = false),
            FieldSpec("绑定手机", VaultFieldType.PHONE, sensitive = false),
            FieldSpec("两步验证密钥", VaultFieldType.TOTP_SECRET),
            FieldSpec("备用码", VaultFieldType.MULTILINE),
            FieldSpec("备注", VaultFieldType.MULTILINE, sensitive = false),
        )

        VaultItemType.CUSTOM -> emptyList()
    }
}
