package com.lotuswallet.app.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lotuswallet.app.R
import com.lotuswallet.app.security.findActivity

/** 一个顶层 Tab 的定义。 */
data class AppTab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

val AppTabs: List<AppTab> = listOf(
    AppTab(Routes.WALLET, R.string.tab_wallet, Icons.Outlined.AccountBalanceWallet),
    AppTab(Routes.VAULT, R.string.tab_vault, Icons.Outlined.Shield),
    AppTab(Routes.MARKET, R.string.tab_market, Icons.Outlined.Storefront),
    AppTab(Routes.OTP, R.string.tab_otp, Icons.Outlined.Password),
    AppTab(Routes.MINE, R.string.tab_mine, Icons.Outlined.Person),
)

/**
 * 自适应外壳。
 *
 * ## 三种形态
 * | 宽度 | 导航形态 | 理由 |
 * |---|---|---|
 * | Compact（< 600dp，手机竖屏） | 底部导航栏 | 单手持握，拇指可达 |
 * | Medium（600–839dp，折叠屏展开 / 小平板） | 左侧导航栏（Rail） | 底部空间被拉宽，横跨整屏的底栏会浪费高度 |
 * | Expanded（≥ 840dp，平板 / 桌面） | 左侧导航栏 + 内容限宽 | 内容区过宽会导致行阅读长度失控 |
 *
 * 二级页面（详情、编辑、转账…）**不显示**导航栏 —— 这些页面需要完整的
 * 屏幕高度来展示表单与二维码，同时避免用户在「确定」与「切 Tab」之间误触。
 */
@Composable
fun AdaptiveShell(
    currentRoute: String?,
    onSelectTab: (String) -> Unit,
    windowSizeClass: WindowSizeClass,
    content: @Composable (PaddingValues) -> Unit,
) {
    val showNavigation = currentRoute in Routes.topLevel
    val useRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    if (showNavigation && useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            LotusNavigationRail(currentRoute, onSelectTab)
            Box(modifier = Modifier.weight(1f)) {
                content(PaddingValues(0.dp))
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                if (showNavigation) {
                    LotusNavigationBar(currentRoute, onSelectTab)
                }
            },
        ) { padding ->
            content(padding)
        }
    }
}

@Composable
private fun LotusNavigationBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        AppTabs.forEach { tab ->
            val selected = currentRoute == tab.route
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = label) },
                label = { Text(label) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun LotusNavigationRail(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                val label = stringResource(tab.labelRes)
                NavigationRailItem(
                    selected = selected,
                    onClick = { if (!selected) onSelect(tab.route) },
                    icon = { Icon(tab.icon, contentDescription = label) },
                    label = { Text(label) },
                    alwaysShowLabel = true,
                )
            }
        }
    }
}

/** 判断当前 Context 是否处于「大屏」形态；取不到 Activity 时按手机处理。 */
@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberIsWideScreen(): Boolean {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return false
    return androidx.compose.material3.windowsizeclass
        .calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact
}
