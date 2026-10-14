package com.lotuswallet.app.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.data.StartupRepository
import com.lotuswallet.app.security.VaultManager
import com.lotuswallet.app.security.SessionKeyStore
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.datastore.AppSettings
import com.lotuswallet.core.datastore.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 根 ViewModel：决定用户此刻应该看到哪个「阶段」。
 *
 * ## 阶段机
 * ```
 * Loading ──► Onboarding ──► Locked ──► Ready
 *                 ▲              ▲          │
 *                 └── 导入备份 ───┘          │
 *                              （自动锁定 / 手动锁定）
 * ```
 * 阶段完全由**两个持久化事实**推导：`vaultInitialized`（磁盘上有没有密钥信封）
 * 与 `session.unlocked`（内存里有没有 DEK）。这样即使进程被杀、
 * 从最近任务恢复，也永远回到正确的界面，不需要额外记录「上次在哪」。
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val vaultManager: VaultManager,
    private val session: SessionKeyStore,
    private val startup: StartupRepository,
) : ViewModel() {

    sealed interface Stage {
        data object Loading : Stage
        data object Onboarding : Stage
        data object Locked : Stage
        data object Ready : Stage
    }

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val vaultReady = MutableStateFlow<Boolean?>(null)

    val stage: StateFlow<Stage> = combine(vaultReady, session.unlocked, appSettings) { ready, unlocked, s ->
        when {
            ready == null -> Stage.Loading
            !s.vaultInitialized -> Stage.Onboarding
            !unlocked -> Stage.Locked
            else -> Stage.Ready
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Stage.Loading)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 生物识别是否可用（已配置且硬件密钥仍在）。 */
    private val _biometricAvailable = MutableStateFlow(false)
    val biometricAvailable: StateFlow<Boolean> = _biometricAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            vaultReady.value = vaultManager.isInitialized()
            _biometricAvailable.value = vaultManager.biometricAvailable()
            // 启动引导：先读缓存（即时可用），再后台刷新远端（失败静默）
            startup.primeFromCache()
            startup.refreshRemote()
        }
        viewModelScope.launch {
            session.unlocked.collect { unlocked ->
                if (!unlocked) _biometricAvailable.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** 完成引导介绍（还没设密码）。 */
    fun completeIntro() {
        viewModelScope.launch { settings.setOnboardingCompleted(true) }
    }

    /** 创建保险库。成功后会直接进入 Ready。 */
    fun createVault(password: CharArray, biometricAfter: Boolean) {
        if (password.size < MIN_PASSWORD_LENGTH) {
            _error.value = "主密码至少 $MIN_PASSWORD_LENGTH 位"
            password.fill('\u0000')
            return
        }
        perform {
            try {
                vaultManager.create(password)
                vaultReady.value = true
                if (biometricAfter) markBiometricRequested()
            } finally {
                // 必须在**派生完成之后**再清零：Argon2id 是异步跑的，
                // 提前清零会让 KDF 拿到一串 0，用户下次就再也解不开了。
                password.fill('\u0000')
            }
        }
    }

    /** 用主密码解锁。 */
    fun unlock(password: CharArray) {
        viewModelScope.launch {
            _busy.value = true
            val ok = runCatching { vaultManager.unlock(password) }.getOrElse {
                _error.value = it.toUserMessage()
                false
            }
            _busy.value = false
            if (!ok) _error.value = "密码错误，请重试"
            password.fill('\u0000')
        }
    }

    /** 从备份恢复：写入信封并立即解锁。 */
    fun restoreFromSnapshot(record: com.lotuswallet.core.datastore.VaultEnvelopeRecord, password: CharArray) {
        viewModelScope.launch {
            _busy.value = true
            val ok = runCatching { vaultManager.restoreEnvelope(record, password) }.getOrElse {
                _error.value = it.toUserMessage()
                false
            }
            _busy.value = false
            if (ok) vaultReady.value = true else _error.value = "密码错误，无法打开该备份"
            password.fill('\u0000')
        }
    }

    /** 手动锁定。 */
    fun lock() {
        vaultManager.lock()
        _biometricAvailable.value = false
        viewModelScope.launch { _biometricAvailable.value = vaultManager.biometricAvailable() }
    }

    /** 自动锁定触发（应用退到后台超过设定时长）。 */
    fun autoLockIfNeeded(elapsedMillis: Long, autoLockMinutes: Int) {
        if (autoLockMinutes <= 0) {
            if (session.isUnlocked) lock()
            return
        }
        if (elapsedMillis >= autoLockMinutes * 60_000L && session.isUnlocked) lock()
    }

    private fun markBiometricRequested() {
        viewModelScope.launch { _biometricAvailable.value = vaultManager.biometricAvailable() }
    }

    private inline fun perform(crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (t: Throwable) {
                _error.value = t.toUserMessage()
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
