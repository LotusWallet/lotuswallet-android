package com.lotuswallet.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 用系统浏览器打开链接。
 *
 * 刻意不内置 WebView：DApp 页面里可能有 `window.ethereum` 之类的注入点，
 * 用应用内 WebView 打开会诱导用户以为「钱包在和 DApp 通信」，而本版本并不
 * 提供 dApp 注入能力。跳到系统浏览器语义更诚实 —— 那是一个外部站点。
 */
object Browser {

    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) {
            Toast.makeText(context, "未找到可以打开该链接的应用", Toast.LENGTH_SHORT).show()
        }
    }
}

/** 把任意文本交给系统分享面板。 */
object Sharing {

    fun shareText(context: Context, text: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        val chooser = Intent.createChooser(intent, subject).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }
}
