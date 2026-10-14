package com.lotuswallet.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lotuswallet.app.R
import com.lotuswallet.core.datastore.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 应用内语言切换入口。
 *
 * 用户反馈：一个外国人装完应用若默认看到中文，应**在登录/引导界面就能切换**，
 * 而不是先进系统再翻设置。本组件就是那个入口 —— 放在引导页与解锁页顶部。
 *
 * 选择持久化到 DataStore（`SettingsRepository.setLanguage`），
 * [com.lotuswallet.app.LotusWalletApp] 的监听会立即把新语言应用到
 * `AppCompatDelegate`，Activity 自动重建、界面即时切换；下次冷启动
 * 由 Application 同步读回，首帧即为所选语言。
 */
@Composable
fun LanguageSwitcher(viewModel: LanguageViewModel = hiltViewModel()) {
    val current by viewModel.language.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    // 三个选项里，「跟随系统」跟随界面语言翻译；两个具体语言**永远用自己的语言显示**
    // （用户看不懂当前界面语言时，必须能认出「简体中文 / English」字样）。
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when (current) {
                    "zh-CN" -> "简体中文"
                    "en" -> "English"
                    else -> stringResource(R.string.settings_language_system)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_language_system)) },
                leadingIcon = if (current == null) {
                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                } else null,
                onClick = {
                    expanded = false
                    viewModel.select(null)
                },
            )
            DropdownMenuItem(
                text = { Text("简体中文") },
                leadingIcon = if (current == "zh-CN") {
                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                } else null,
                onClick = {
                    expanded = false
                    viewModel.select("zh-CN")
                },
            )
            DropdownMenuItem(
                text = { Text("English") },
                leadingIcon = if (current == "en") {
                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                } else null,
                onClick = {
                    expanded = false
                    viewModel.select("en")
                },
            )
        }
    }
}

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val language = settings.settings
        .map { it.language }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null = 跟随系统。 */
    fun select(tag: String?) {
        viewModelScope.launch { settings.setLanguage(tag) }
    }
}
