package com.lotuswallet.app.ui.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.BuildConfig
import com.lotuswallet.core.network.update.AppRelease
import com.lotuswallet.core.network.update.UpdateCheckResult
import com.lotuswallet.core.network.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「关于」页里的检查更新。
 *
 * 三条刻意的设计选择：
 *
 * 1. **只在用户点击时联网。** 不在启动时检查、不在后台轮询。一个本地钱包不该为了
 *    推销新版本而在你手机上维持任何长期连接。
 * 2. **只提示，不下载、不安装。** 拿到新版本信息后只弹一次对话框，把用户送到下载页。
 *    静默下载 + 提示安装看上去「体验更好」，但那意味着应用能在你不知情时取回并落盘
 *    一个可执行文件 —— 这与本产品的定位相悖。
 * 3. **失败就说失败。** 不把网络错误吞成「已是最新」—— 那会让用户以为一切正常，
 *    然后一直用着旧版本还找不到原因。
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updates: UpdateRepository,
) : ViewModel() {

    /** 检查更新的界面状态。 */
    sealed interface UpdateUiState {
        /** 还没查过。 */
        data object Idle : UpdateUiState

        data object Checking : UpdateUiState

        /** 有新版本，[required] 为 true 表示当前版本已不受支持。 */
        data class Available(val release: AppRelease, val required: Boolean) : UpdateUiState

        data object UpToDate : UpdateUiState

        data object Failed : UpdateUiState
    }

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /** 当前安装包的版本，直接取构建期注入的常量，避免和 version.properties 漂移。 */
    val currentVersionName: String = BuildConfig.VERSION_NAME
    val currentVersionCode: Int = BuildConfig.VERSION_CODE

    fun checkForUpdates() {
        if (_updateState.value == UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            _updateState.value = when (val result = updates.check(currentVersionCode)) {
                is UpdateCheckResult.Available -> UpdateUiState.Available(result.release, result.required)
                UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.Failed -> UpdateUiState.Failed
            }
        }
    }

    /** 用户关掉「发现新版本」对话框：回到未检查状态，下次点按钮可以再查。 */
    fun dismissUpdate() {
        _updateState.value = UpdateUiState.Idle
    }
}
