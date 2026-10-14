package com.lotuswallet.app.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * 截屏 / 录屏防护。
 *
 * `FLAG_SECURE` 会让系统拒绝截屏、把内容从最近任务列表中抹掉、并禁止投屏，
 * 这是加密钱包的标准做法（助记词、私钥、OTP 口令都不应出现在用户相册或录屏里）。
 *
 * 通过「Window 级开关 + 组合项生命周期」实现：进入敏感页面时打开，离开时还原，
 * 因此不会全局牺牲体验（例如市场页允许正常截图分享）。
 */
object ScreenGuard {

    fun apply(activity: Activity, protect: Boolean) {
        if (protect) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** 在组合项存续期间开启截屏保护；[enabled] 为 false 时不做任何事。 */
@Composable
fun SecureScreen(enabled: Boolean = true) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context.findActivity()
        if (enabled && activity != null) ScreenGuard.apply(activity, true)
        onDispose {
            // 离开时一定要还原，否则后续页面会被永久保护，用户无法截图分享
            if (enabled && activity != null) ScreenGuard.apply(activity, false)
        }
    }
    // 引用一次 view，保证 LocalView 变化时组合项会重组（分屏 / 多窗口切换 Activity 时需要）
    @Suppress("UNUSED_EXPRESSION")
    view
}

/** 从任意 Context 向上找到宿主 Activity。 */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
