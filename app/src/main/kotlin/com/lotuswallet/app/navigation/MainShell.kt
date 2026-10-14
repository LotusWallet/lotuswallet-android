package com.lotuswallet.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lotuswallet.app.security.findActivity
import com.lotuswallet.app.ui.market.MarketScreen
import com.lotuswallet.app.ui.mine.AboutScreen
import com.lotuswallet.app.ui.mine.AddressBookScreen
import com.lotuswallet.app.ui.mine.BackupScreen
import com.lotuswallet.app.ui.mine.ChainEditScreen
import com.lotuswallet.app.ui.mine.ChainsScreen
import com.lotuswallet.app.ui.mine.MineScreen
import com.lotuswallet.app.ui.mine.SecurityScreen
import com.lotuswallet.app.ui.mine.SettingsScreen
import com.lotuswallet.app.ui.otp.OtpEditScreen
import com.lotuswallet.app.ui.otp.OtpScreen
import com.lotuswallet.app.ui.vault.PasswordGeneratorScreen
import com.lotuswallet.app.ui.vault.VaultDetailScreen
import com.lotuswallet.app.ui.vault.VaultEditScreen
import com.lotuswallet.app.ui.vault.VaultHomeScreen
import com.lotuswallet.app.ui.wallet.AccountDetailScreen
import com.lotuswallet.app.ui.wallet.AddWalletScreen
import com.lotuswallet.app.ui.wallet.MnemonicBackupScreen
import com.lotuswallet.app.ui.wallet.SendScreen
import com.lotuswallet.app.ui.wallet.WalletDetailScreen
import com.lotuswallet.app.ui.wallet.WalletHomeScreen

/**
 * 主界面外壳：五个 Tab + 全部二级页面。
 *
 * ## 为什么把导航集中在一个文件
 * 路由表是**全局事实**：哪两个页面之间能跳、跳转时带什么参数，散落在各页面里
 * 会迅速失控（旧版就是这样，十几个 `startActivity` 散落各处，改一个页面要全局搜）。
 * 这里所有 `navigate` 只出现在本文件，页面本身只暴露「意图」回调
 * （`onOpenAccount`、`onSend`…），因此页面可以被单独预览与测试。
 *
 * ## Tab 切换语义
 * 走 `popUpTo(startDestination) + saveState + restoreState`：切走再切回时，
 * 该 Tab 的滚动位置与下级页面栈会被恢复，符合用户对「标签页」的直觉；
 * 同时 `launchSingleTop` 防止连点同一个 Tab 压出多层重复页面。
 */
@Composable
fun MainShell(onLock: () -> Unit = {}) {
    val navController = rememberNavController()
    val windowSizeClass = rememberWindowSizeClass()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    AdaptiveShell(
        currentRoute = currentRoute,
        onSelectTab = { route -> navController.switchTab(route) },
        windowSizeClass = windowSizeClass,
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.WALLET,
            modifier = Modifier.padding(padding),
        ) {
            walletGraph(navController, onLock)
            vaultGraph(navController)
            otpGraph(navController)
            marketGraph(navController)
            mineGraph(navController)
        }
    }
}

// ==================================================================== 钱包

private fun NavGraphBuilder.walletGraph(navController: NavHostController, onLock: () -> Unit) {

    composable(Routes.WALLET) {
        WalletHomeScreen(
            onOpenAccount = { navController.navigate(Routes.accountDetail(it)) },
            onAddWallet = { navController.navigate(Routes.ADD_WALLET) },
            onOpenWallet = { navController.navigate(Routes.walletDetail(it)) },
            onBackupWallet = { navController.navigate(Routes.mnemonicBackup(it)) },
            onLock = onLock,
        )
    }

    composable(Routes.ADD_WALLET) {
        AddWalletScreen(
            onBack = { navController.popBackStack() },
            onCreated = { walletId, needsBackup ->
                if (needsBackup) {
                    // 用备份页**顶替**本页：从备份页返回时直接回到钱包首页，
                    // 而不是回到一个「已经用过的」创建表单。
                    navController.navigate(Routes.mnemonicBackup(walletId)) {
                        popUpTo(Routes.ADD_WALLET) { inclusive = true }
                    }
                } else {
                    navController.popBackStack()
                }
            },
        )
    }

    composable(
        route = Routes.WALLET_DETAIL,
        arguments = listOf(navArgument("walletId") { type = NavType.StringType }),
    ) {
        WalletDetailScreen(
            onBack = { navController.popBackStack() },
            onOpenAccount = { navController.navigate(Routes.accountDetail(it)) },
            onBackupMnemonic = { navController.navigate(Routes.mnemonicBackup(it)) },
        )
    }

    composable(
        route = Routes.ACCOUNT_DETAIL,
        arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
    ) {
        AccountDetailScreen(
            onBack = { navController.popBackStack() },
            onSend = { accountId -> navController.navigate(Routes.send(accountId)) },
        )
    }

    composable(
        route = Routes.SEND,
        arguments = listOf(
            navArgument("accountId") { type = NavType.StringType },
            // 可选查询参数必须给出默认值，否则「不带 tokenId」的跳转会匹配失败
            navArgument("tokenId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        SendScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = Routes.MNEMONIC_BACKUP,
        arguments = listOf(navArgument("walletId") { type = NavType.StringType }),
    ) {
        MnemonicBackupScreen(
            onBack = { navController.popBackStack() },
            onDone = { navController.popBackStack() },
        )
    }
}

// ==================================================================== 保险库

private fun NavGraphBuilder.vaultGraph(navController: NavHostController) {

    composable(Routes.VAULT) {
        VaultHomeScreen(
            onOpenItem = { navController.navigate(Routes.vaultDetail(it)) },
            onNewItem = { navController.navigate(Routes.vaultEdit()) },
            onOpenGenerator = { navController.navigate(Routes.PASSWORD_GENERATOR) },
        )
    }

    composable(
        route = Routes.VAULT_DETAIL,
        arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
    ) {
        VaultDetailScreen(
            onBack = { navController.popBackStack() },
            onEdit = { itemId -> navController.navigate(Routes.vaultEdit(itemId = itemId)) },
        )
    }

    composable(
        route = Routes.VAULT_EDIT,
        arguments = listOf(
            navArgument("itemId") { type = NavType.StringType },
            navArgument("type") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) {
        VaultEditScreen(
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }

    composable(Routes.PASSWORD_GENERATOR) {
        PasswordGeneratorScreen(onBack = { navController.popBackStack() })
    }
}

// ==================================================================== 验证码

private fun NavGraphBuilder.otpGraph(navController: NavHostController) {

    composable(Routes.OTP) {
        OtpScreen(
            onNewEntry = { navController.navigate(Routes.otpEdit()) },
            onEditEntry = { navController.navigate(Routes.otpEdit(it)) },
        )
    }

    composable(
        route = Routes.OTP_EDIT,
        arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
    ) {
        OtpEditScreen(
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }
}

// ==================================================================== 市场

private fun NavGraphBuilder.marketGraph(navController: NavHostController) {
    composable(Routes.MARKET) {
        MarketScreen(onOpenChains = { navController.navigate(Routes.CHAINS) })
    }
}

// ==================================================================== 我的

private fun NavGraphBuilder.mineGraph(navController: NavHostController) {

    composable(Routes.MINE) {
        MineScreen(
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            onOpenSecurity = { navController.navigate(Routes.SECURITY) },
            onOpenBackup = { navController.navigate(Routes.BACKUP) },
            onOpenAddressBook = { navController.navigate(Routes.ADDRESS_BOOK) },
            onOpenChains = { navController.navigate(Routes.CHAINS) },
            onOpenAbout = { navController.navigate(Routes.ABOUT) },
        )
    }

    composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
    composable(Routes.SECURITY) { SecurityScreen(onBack = { navController.popBackStack() }) }
    composable(Routes.BACKUP) { BackupScreen(onBack = { navController.popBackStack() }) }
    composable(Routes.ADDRESS_BOOK) { AddressBookScreen(onBack = { navController.popBackStack() }) }
    composable(Routes.CHAINS) {
        ChainsScreen(
            onBack = { navController.popBackStack() },
            onAddChain = { navController.navigate(Routes.chainEdit()) },
            onEditChain = { chainId -> navController.navigate(Routes.chainEdit(chainId = chainId)) },
        )
    }

    composable(
        route = Routes.CHAIN_EDIT,
        arguments = listOf(
            navArgument("chainId") { type = NavType.StringType },
            navArgument("family") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) {
        ChainEditScreen(
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }
    composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }
}

// ==================================================================== 工具

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 取窗口尺寸类别。
 *
 * 优先问 Activity（折叠屏展开、分屏、旋转都能拿到真实窗口尺寸）；
 * 极端情况下（例如在预览或测试环境里没有 Activity）退回用配置里的屏幕宽高，
 * 保证不会因为拿不到 Activity 而直接崩溃。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun rememberWindowSizeClass(): WindowSizeClass {
    val activity = LocalContext.current.findActivity()
    if (activity != null) return calculateWindowSizeClass(activity)

    val configuration = LocalConfiguration.current
    return WindowSizeClass.calculateFromSize(
        DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp),
    )
}
