package com.lotuswallet.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 剪贴板。
 *
 * Android 13+ 会把剪贴板内容展示给用户确认，并可能在系统 UI 中预览 ——
 * 这对助记词、私钥是致命的。因此敏感内容一律走 [copySensitive]：
 * 它显式标记 `EXTRA_IS_SENSITIVE = true`，让系统不生成预览缩略图。
 */
object Clipboard {

    /** 普通内容（地址、交易哈希、链接）。 */
    fun copy(context: Context, text: String, label: String = "LotusWallet") {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** 敏感内容（助记词、私钥、OTP 密钥、密码）：禁止系统预览。 */
    fun copySensitive(context: Context, text: String, label: String = "LotusWallet") {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle().apply {
                putBoolean(ClipDescriptionExtras.SENSITIVE, true)
            }
            clip.description.extras = extras
        }
        manager.setPrimaryClip(clip)
    }

    /** 读取剪贴板文本（用于「粘贴助记词 / otpauth 链接」）。 */
    fun read(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }

    private object ClipDescriptionExtras {
        /** `ClipDescription.EXTRA_IS_SENSITIVE` 的常量值（33+ 才有，这里固定字符串避免分支）。 */
        const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

/** Compose 便捷入口：`val copy = rememberClipboard()`。 */
@Composable
fun rememberClipboard(): (String, Boolean) -> Unit {
    val context = LocalContext.current
    return { text, sensitive ->
        if (sensitive) Clipboard.copySensitive(context, text) else Clipboard.copy(context, text)
    }
}
