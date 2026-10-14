package com.lotuswallet.app.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.WalletRepository
import com.lotuswallet.app.security.SecureScreen
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.app.util.Clipboard
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusChip
import com.lotuswallet.core.designsystem.theme.LotusTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 助记词备份。
 *
 * ## 为什么要有「抄写校验」这一步
 * 只显示一次然后让用户点「我已抄好」，实测有相当比例的用户其实没抄对。
 * 备份是**唯一**的恢复手段，因此这里强制做一次抽查：随机挑 3 个位置让用户按顺序点出来。
 * 校验通过才写入 `backedUp = true`，首页的警示条才会消失。
 */
@HiltViewModel
class MnemonicBackupViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walletId: String = savedStateHandle.get<String>("walletId").orEmpty()

    enum class Step { REVEAL, VERIFY, DONE }

    data class UiState(
        val step: Step = Step.REVEAL,
        val words: List<String> = emptyList(),
        val walletName: String = "",
        /** 待校验的位置（从 0 开始）与选项。 */
        val challengeIndices: List<Int> = emptyList(),
        val challengeOptions: List<String> = emptyList(),
        val picked: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun notifyCopied() {
        _message.value = "已复制到剪贴板，请勿粘贴到任何联网应用"
    }

    fun consumeMessage() {
        _message.value = null
    }

    init {
        viewModelScope.launch {
            val wallet = walletRepository.wallet(walletId) ?: return@launch
            val secret = runCatching { walletRepository.secretOf(wallet) }.getOrNull() ?: return@launch
            val words = secret.mnemonic?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
            _state.value = _state.value.copy(words = words, walletName = wallet.name)
            buildChallenge(words)
        }
    }

    private fun buildChallenge(words: List<String>) {
        if (words.size < 6) return
        val indices = words.indices.shuffled().take(CHALLENGE_COUNT).sorted()
        val distractors = com.lotuswallet.core.crypto.Bip39.words
            .filter { it !in words }
            .shuffled()
            .take(6)
        val options = (indices.map { words[it] } + distractors).shuffled()
        _state.value = _state.value.copy(
            challengeIndices = indices,
            challengeOptions = options,
            picked = emptyList(),
        )
    }

    fun startVerify() {
        _state.value = _state.value.copy(step = Step.VERIFY, error = null)
        buildChallenge(_state.value.words)
    }

    fun pick(word: String) {
        val s = _state.value
        if (s.picked.size >= s.challengeIndices.size) return
        val picked = s.picked + word
        val expected = s.challengeIndices.take(picked.size).map { s.words[it] }

        if (picked != expected) {
            // 点错就整轮重来，避免用户靠排除法把答案试出来
            _state.value = s.copy(picked = emptyList(), error = "顺序不正确，请再核对一次")
            buildChallengeOptions()
            return
        }
        _state.value = s.copy(picked = picked, error = null)
        if (picked.size == s.challengeIndices.size) confirm()
    }

    fun resetChallenge() {
        _state.value = _state.value.copy(picked = emptyList(), error = null)
        buildChallenge(_state.value.words)
    }

    /** 只重排选项，不改挑战位置（用户点错时用）。 */
    private fun buildChallengeOptions() {
        val s = _state.value
        val wrongWords = s.challengeIndices.map { s.words[it] }
        val distractors = com.lotuswallet.core.crypto.Bip39.words
            .filter { it !in s.words }
            .shuffled()
            .take(6)
        _state.value = s.copy(challengeOptions = (wrongWords + distractors).shuffled())
    }

    private fun confirm() {
        viewModelScope.launch {
            runCatching { walletRepository.markBackedUp(walletId) }
            _state.value = _state.value.copy(step = Step.DONE)
            _finished.value = true
        }
    }

    private companion object {
        const val CHALLENGE_COUNT = 3
    }
}

@Composable
fun MnemonicBackupScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: MnemonicBackupViewModel = hiltViewModel(),
) {
    SecureScreen(enabled = true)

    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    DetailScaffold(
        title = stringResource(R.string.wallet_backup_title),
        subtitle = state.walletName.takeIf { it.isNotBlank() },
        onBack = onBack,
        snackbarHostState = snackbar,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                when (state.step) {
                    MnemonicBackupViewModel.Step.REVEAL -> PrimaryButton(
                        text = stringResource(R.string.wallet_backup_done),
                        enabled = state.words.isNotEmpty(),
                        onClick = { viewModel.startVerify() },
                    )

                    MnemonicBackupViewModel.Step.VERIFY -> PrimaryButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { viewModel.resetChallenge() },
                    )

                    MnemonicBackupViewModel.Step.DONE -> PrimaryButton(
                        text = stringResource(R.string.action_done),
                        onClick = onDone,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state.step) {
                MnemonicBackupViewModel.Step.REVEAL -> {
                    WarningCard(
                        text = stringResource(R.string.wallet_backup_warning),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    LotusCard {
                        Text(
                            text = "请按顺序抄写下列 ${state.words.size} 个单词：",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        // 三列网格：列优先填，符合「按顺序念下来」的习惯
                        val columns = 3
                        val perColumn = (state.words.size + columns - 1) / columns
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (col in 0 until columns) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    state.words.drop(col * perColumn).take(perColumn).forEachIndexed { i, word ->
                                        val number = col * perColumn + i + 1
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "%2d.".format(number),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(26.dp),
                                            )
                                            Text(
                                                text = word,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    TextButton(onClick = {
                        Clipboard.copySensitive(context, state.words.joinToString(" "))
                        viewModel.notifyCopied()
                    }) {
                        Text(stringResource(R.string.action_copy_all))
                    }
                }

                MnemonicBackupViewModel.Step.VERIFY -> {
                    val expectedNumbers = state.challengeIndices.map { it + 1 }
                    LotusCard {
                        Text(
                            text = stringResource(
                                R.string.wallet_backup_verify,
                                expectedNumbers.joinToString("、"),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "已选：${state.picked.joinToString(" ") { it }.ifBlank { "—" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = LotusTheme.semantic.danger)
                        }
                        Spacer(Modifier.height(14.dp))
                        Box {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 用可换行的 chip 流式排列
                                Column {
                                    state.challengeOptions.chunked(3).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { option ->
                                                FilterChip(
                                                    selected = option in state.picked,
                                                    onClick = { viewModel.pick(option) },
                                                    label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                MnemonicBackupViewModel.Step.DONE -> {
                    LotusCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LotusChip(
                                text = stringResource(R.string.wallet_backed_up),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.wallet_backup_success),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "助记词是你资产的唯一凭证。请确认抄写件存放在防火、防水且只有你能拿到的地方。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
