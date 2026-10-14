package com.lotuswallet.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lotuswallet.app.ui.root.AppRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一的 Activity。
 *
 * ## 为什么继承 [AppCompatActivity] 而不是 ComponentActivity
 * 两个硬性依赖都要求 AppCompatActivity 这一层：
 * 1. [androidx.biometric.BiometricPrompt] 的契约基于 Fragment 生命周期，
 *    必须拿到 [androidx.fragment.app.FragmentManager]；
 * 2. **应用内语言切换**（Android 13 以下）依赖 `AppCompatDelegate` 重建 Activity，
 *    只有 AppCompatActivity 会在配置里真正应用 `setApplicationLocales` 的覆盖值。
 * AppCompatActivity 本身就是 FragmentActivity 的子类，二者不冲突；
 * 同时不引入 ActionBar 包袱（主题用 DayNight.NoActionBar，Compose 自己管 UI）。
 *
 * ## 为什么 configChanges 里声明了那么多
 * 折叠屏展开/折叠、分屏、旋转、深色模式切换时，**重建 Activity 会让已解锁的
 * 会话密钥丢失**，用户会被反复要求重新输入主密码。声明这些配置变更并交由
 * Compose 自行处理重组，是钱包类应用的正确做法（避免「一展开就锁屏」）。
 *
 * ⚠️ 语言切换**刻意不**放进 configChanges：切语言必须重建，
 * 否则已 inflated 的字符串资源不会更新。重建带来的「重新解锁」是可接受代价。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前，才能接管冷启动窗口
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}
