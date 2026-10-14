package com.lotuswallet.app.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotuswallet.app.BuildConfig
import com.lotuswallet.app.R
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.DetailRow
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.util.Browser
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.network.chain.ChainRegistry

/**
 * 关于页。
 *
 * 把「数据去哪了」「密钥怎么保护的」「更新从哪来」直接写在应用里 —— 这是本产品与
 * 大多数钱包最大的差异点，值得用一页讲清楚，而不是藏在隐私政策链接后面。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    DetailScaffold(
        title = stringResource(R.string.about_title),
        onBack = onBack,
    ) { padding ->
        ReadableContent(maxWidth = 720.dp) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------------------------------------------- 头
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AddressAvatar(seed = "lotuswallet", label = "LW", size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.about_slogan),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ---------------------------------------------------- 版本
                SectionCard {
                    DetailRow(
                        label = stringResource(R.string.settings_about_version),
                        value = stringResource(
                            R.string.about_version_format,
                            viewModel.currentVersionName,
                            viewModel.currentVersionCode,
                        ),
                    )
                    DetailRow(
                        label = stringResource(R.string.settings_about_backend),
                        value = stringResource(R.string.settings_about_backend_none),
                    )
                    DetailRow(
                        label = stringResource(R.string.settings_about_chains),
                        value = stringResource(
                            R.string.settings_about_chains_value,
                            ChainRegistry.builtin().count { it.enabled },
                        ),
                    )
                    DetailRow(
                        label = stringResource(R.string.about_remote_data),
                        value = stringResource(R.string.about_remote_data_value),
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    UpdateRow(state = updateState, onCheck = viewModel::checkForUpdates)
                }

                // ---------------------------------------------------- 隐私
                SectionCard(title = stringResource(R.string.about_privacy_title)) {
                    Text(
                        text = stringResource(R.string.about_privacy_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // ---------------------------------------------------- 安全
                SectionCard(title = stringResource(R.string.about_security_title)) {
                    Text(
                        text = stringResource(R.string.about_security_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // ---------------------------------------------------- 开源组件
                SectionCard(title = stringResource(R.string.about_opensource)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OPEN_SOURCE.forEach { item ->
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.about_licenses),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 更新提示放在页面之外，避免被 ReadableContent 的限宽裁掉
    UpdateDialogs(state = updateState, onDismiss = viewModel::dismissUpdate)
}

/**
 * 「检查更新」行。
 *
 * 状态提示直接写在按钮左边：检查失败要和「已是最新」明确区分开 ——
 * 把网络错误显示成「已是最新」会让用户以为一切正常，然后一直用着旧版本。
 */
@Composable
private fun UpdateRow(
    state: AboutViewModel.UpdateUiState,
    onCheck: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.about_check_update),
                style = MaterialTheme.typography.bodyMedium,
            )
            val hint = when (state) {
                AboutViewModel.UpdateUiState.Idle -> R.string.about_update_never_on_startup
                AboutViewModel.UpdateUiState.Checking -> R.string.about_update_checking
                AboutViewModel.UpdateUiState.UpToDate -> R.string.about_update_uptodate
                AboutViewModel.UpdateUiState.Failed -> R.string.about_update_failed
                is AboutViewModel.UpdateUiState.Available -> R.string.about_update_available_title
            }
            Text(
                text = if (state is AboutViewModel.UpdateUiState.Available) {
                    stringResource(hint, state.release.versionName)
                } else {
                    stringResource(hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    AboutViewModel.UpdateUiState.Failed -> MaterialTheme.colorScheme.error
                    is AboutViewModel.UpdateUiState.Available -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        if (state == AboutViewModel.UpdateUiState.Checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            TextButton(onClick = onCheck) {
                Text(stringResource(R.string.about_check_update))
            }
        }
    }
}

/** 新版本对话框。只把用户送到下载页，绝不在应用内下载或安装 APK。 */
@Composable
private fun UpdateDialogs(
    state: AboutViewModel.UpdateUiState,
    onDismiss: () -> Unit,
) {
    if (state !is AboutViewModel.UpdateUiState.Available) return
    val release = state.release
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!state.required) onDismiss() },
        title = {
            Text(
                text = if (state.required) {
                    stringResource(R.string.about_update_required_title, release.versionName)
                } else {
                    stringResource(R.string.about_update_available_title, release.versionName)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (state.required) {
                        stringResource(
                            R.string.about_update_required_body,
                            BuildConfig.VERSION_NAME,
                            release.versionName,
                        )
                    } else {
                        stringResource(
                            R.string.about_update_available_body,
                            BuildConfig.VERSION_NAME,
                            release.versionName,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                release.releaseDate?.let { date ->
                    // sizeBytes 来自另一个模块，编译器无法智能转换跨模块的公开属性，
                    // 先取到局部变量再判空。
                    val sizeBytes = release.sizeBytes
                    Text(
                        text = if (sizeBytes != null) {
                            stringResource(
                                R.string.about_update_meta,
                                date,
                                formatSize(sizeBytes),
                            )
                        } else {
                            stringResource(R.string.about_update_meta, date, release.versionName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 优先打开版本页/固定直链：让用户自己看到「从哪下的」，
                    // 而不是由应用替他决定一个后台下载。
                    val url = release.releasePageUrl ?: release.bestDownloadUrl
                    if (url != null) Browser.open(context, url)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.about_update_download))
            }
        },
        dismissButton = if (state.required) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_update_later)) } }
        },
    )
}

/** 人类可读的文件大小。只用于展示，不做精确换算。 */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 这些是本产品真正依赖的组件。刻意保持短 ——
 * 一个钱包的信任面应该小到用户可以逐条读完。
 */
private val OPEN_SOURCE = listOf(
    "Jetpack Compose · Material 3 —— 界面",
    "Room · DataStore —— 本地存储",
    "BouncyCastle —— secp256k1 / Ed25519 / Argon2",
    "OkHttp —— 只读的公共节点请求",
    "ZXing —— 二维码编解码（全本地，不上传）",
)
