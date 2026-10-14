package com.lotuswallet.app.ui.otp

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.OtpRepository
import com.lotuswallet.app.navigation.Routes
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SecretTextField
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.scan.QrScannerDialog
import com.lotuswallet.app.util.Clipboard
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.crypto.Base32
import com.lotuswallet.core.crypto.OtpEngine
import com.lotuswallet.core.database.entity.OtpType
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.MonoText
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * OTP 条目编辑器。
 *
 * 支持三种录入方式：手动输入、扫描网站给出的 otpauth 二维码、粘贴 otpauth 链接。
 * 三种最终都汇成同一组合法参数，因此后端的 [OtpRepository.save] 只需要一条路径。
 */
@HiltViewModel
class OtpEditViewModel @Inject constructor(
    private val repository: OtpRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val rawId: String = savedStateHandle.get<String>("entryId").orEmpty()

    /** null 表示新建。 */
    private val entryId: String? = rawId.takeIf { it.isNotBlank() && it != Routes.NEW }

    data class UiState(
        val issuer: String = "",
        val accountName: String = "",
        val secret: String = "",
        val type: OtpType = OtpType.TOTP,
        val algorithm: String = "SHA1",
        val digits: Int = 6,
        val periodSeconds: Int = 30,
        val counter: Long = 0,
        val loading: Boolean = false,
        val busy: Boolean = false,
        val saved: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState(loading = entryId != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val isNew: Boolean = entryId == null

    init {
        if (entryId != null) load()
    }

    private fun load() {
        viewModelScope.launch {
            runCatching {
                val entity = repository.entry(entryId!!) ?: return@runCatching
                _state.value = _state.value.copy(
                    issuer = entity.issuer.orEmpty(),
                    accountName = entity.accountName,
                    secret = repository.secretOf(entity.id),
                    type = entity.type,
                    algorithm = entity.algorithm,
                    digits = entity.digits,
                    periodSeconds = entity.periodSeconds,
                    counter = entity.counter,
                    loading = false,
                )
            }.onFailure {
                _message.value = it.toUserMessage()
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun setIssuer(value: String) {
        _state.value = _state.value.copy(issuer = value)
    }

    fun setAccountName(value: String) {
        _state.value = _state.value.copy(accountName = value)
    }

    /** 输入时顺手规范化：去空格、转大写 —— 从网页复制密钥经常带上这些字符。 */
    fun setSecret(value: String) {
        _state.value = _state.value.copy(secret = value.uppercase().filter { it.isLetterOrDigit() })
    }

    fun setType(type: OtpType) {
        _state.value = _state.value.copy(type = type)
    }

    fun setAlgorithm(algorithm: String) {
        _state.value = _state.value.copy(algorithm = algorithm)
    }

    fun setDigits(digits: Int) {
        _state.value = _state.value.copy(digits = digits)
    }

    fun setPeriod(seconds: Int) {
        _state.value = _state.value.copy(periodSeconds = seconds)
    }

    fun setCounter(value: String) {
        _state.value = _state.value.copy(counter = value.toLongOrNull()?.coerceAtLeast(0) ?: 0L)
    }

    fun generateSecret() {
        _state.value = _state.value.copy(secret = repository.randomSecret())
    }

    /** 粘贴 otpauth 链接 / Base32 密钥并填充表单。 */
    fun fillFromText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        runCatching { repository.parseOtpAuth(text) }
            .onSuccess { parsed ->
                _state.value = _state.value.copy(
                    issuer = parsed.issuer.orEmpty(),
                    accountName = parsed.label.takeIf { it != "未命名" } ?: _state.value.accountName,
                    secret = parsed.secret,
                    type = if (parsed.type.equals("hotp", ignoreCase = true)) OtpType.HOTP else OtpType.TOTP,
                    algorithm = parsed.algorithm.otpauthName,
                    digits = parsed.digits,
                    periodSeconds = parsed.periodSeconds,
                    counter = parsed.counter ?: 0L,
                )
                _message.value = "已解析 otpauth 链接"
            }
            .onFailure { _message.value = "无法解析该链接，请确认是 otpauth:// 格式" }
    }

    fun save() {
        val s = _state.value
        if (s.accountName.isBlank()) {
            _message.value = "请填写账号"
            return
        }
        if (Base32.decodeLenient(s.secret).isEmpty()) {
            _message.value = "密钥无效，应为 Base32 编码"
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(busy = true)
            runCatching {
                repository.save(
                    entryId = entryId,
                    issuer = s.issuer,
                    accountName = s.accountName,
                    secret = s.secret,
                    type = s.type,
                    algorithm = s.algorithm,
                    digits = s.digits,
                    periodSeconds = s.periodSeconds,
                    counter = s.counter,
                )
            }.onSuccess {
                _state.value = _state.value.copy(busy = false, saved = true)
            }.onFailure {
                _message.value = it.toUserMessage()
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

@Composable
fun OtpEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: OtpEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showScanner by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    if (state.saved) {
        onSaved()
        return
    }

    DetailScaffold(
        title = stringResource(if (viewModel.isNew) R.string.otp_new else R.string.otp_edit),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(
                onClick = {
                    Clipboard.read(context)?.let { viewModel.fillFromText(it) }
                },
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.action_paste))
            }
            IconButton(onClick = { showScanner = true }) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = stringResource(R.string.otp_scan),
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.action_save),
                    enabled = !state.busy,
                    onClick = viewModel::save,
                )
            }
        },
    ) { padding ->
        ReadableContent(maxWidth = 720.dp) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                // 实时预览**固定吸顶**，不参与滚动。
                // 用户往下改参数时，最需要看到的就是「改完之后验证码变成什么样」；
                // 如果预览卡跟着内容滚走，这个即时反馈就失去了意义。
                LivePreviewCard(state)
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LotusTextField(
                        value = state.issuer,
                        onValueChange = viewModel::setIssuer,
                        label = stringResource(R.string.otp_issuer_label),
                        placeholder = "GitHub / Google / 币安…",
                    )

                LotusTextField(
                    value = state.accountName,
                    onValueChange = viewModel::setAccountName,
                    label = stringResource(R.string.otp_account_label),
                    placeholder = "you@example.com",
                )

                // ------------------------------------------------ 密钥
                SectionCard(title = stringResource(R.string.otp_secret_label)) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                        SecretTextField(
                            value = state.secret,
                            onValueChange = viewModel::setSecret,
                            label = stringResource(R.string.otp_secret_label),
                            singleLine = false,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = viewModel::generateSecret) {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.height(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.otp_generate_secret),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            TextButton(onClick = { showScanner = true }) {
                                Text(
                                    text = stringResource(R.string.otp_scan),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        if (state.secret.isNotBlank() && Base32.decodeLenient(state.secret).isEmpty()) {
                            Text(
                                text = stringResource(R.string.otp_secret_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = LotusTheme.semantic.danger,
                            )
                        }
                    }
                }

                // ------------------------------------------------ 类型
                SectionCard(title = stringResource(R.string.otp_type_label)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.type == OtpType.TOTP,
                            onClick = { viewModel.setType(OtpType.TOTP) },
                            label = { Text(stringResource(R.string.otp_type_totp)) },
                        )
                        FilterChip(
                            selected = state.type == OtpType.HOTP,
                            onClick = { viewModel.setType(OtpType.HOTP) },
                            label = { Text(stringResource(R.string.otp_type_hotp)) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.type == OtpType.TOTP) {
                            "验证码随时间变化，每 ${state.periodSeconds} 秒更新一次。绝大多数网站使用这种。"
                        } else {
                            "验证码由计数器决定，每使用一次需要手动推进计数器。较少见。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                // ------------------------------------------------ 算法 / 位数 / 周期
                SectionCard(title = stringResource(R.string.otp_algorithm_label)) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                        AlgorithmPicker(
                            current = state.algorithm,
                            onSelect = viewModel::setAlgorithm,
                        )
                    }
                }

                SectionCard(title = stringResource(R.string.otp_digits_label)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(6, 7, 8).forEach { digits ->
                            FilterChip(
                                selected = state.digits == digits,
                                onClick = { viewModel.setDigits(digits) },
                                label = { Text(digits.toString()) },
                            )
                        }
                    }
                }

                if (state.type == OtpType.TOTP) {
                    SectionCard(title = stringResource(R.string.otp_period_label)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(30, 60).forEach { seconds ->
                                FilterChip(
                                    selected = state.periodSeconds == seconds,
                                    onClick = { viewModel.setPeriod(seconds) },
                                    label = { Text("$seconds s") },
                                )
                            }
                        }
                    }
                } else {
                    SectionCard(title = stringResource(R.string.otp_counter_label)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            LotusTextField(
                                value = state.counter.toString(),
                                onValueChange = viewModel::setCounter,
                                label = stringResource(R.string.otp_counter_label),
                                keyboardType = KeyboardType.Number,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onDecoded = {
                showScanner = false
                viewModel.fillFromText(it)
            },
            onDismiss = { showScanner = false },
            title = stringResource(R.string.otp_scan),
        )
    }
}

/**
 * 实时预览。
 *
 * 让用户在保存前就能看到「这个密钥真的会生成验证码」，
 * 是发现「扫错码 / 少复制了几位」最直接的手段。
 */
@Composable
private fun LivePreviewCard(state: OtpEditViewModel.UiState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.type) {
        if (state.type != OtpType.TOTP) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val code = remember(
        state.secret,
        state.type,
        state.algorithm,
        state.digits,
        state.periodSeconds,
        state.counter,
        now,
    ) {
        if (state.secret.isBlank()) {
            ""
        } else {
            runCatching {
                if (state.type == OtpType.HOTP) {
                    OtpEngine.hotp(
                        secret = state.secret,
                        counter = state.counter,
                        digits = state.digits,
                        algorithm = OtpEngine.Algorithm.fromOtpAuth(state.algorithm),
                    )
                } else {
                    OtpEngine.totp(
                        secret = state.secret,
                        timeMillis = now,
                        periodSeconds = state.periodSeconds,
                        digits = state.digits,
                        algorithm = OtpEngine.Algorithm.fromOtpAuth(state.algorithm),
                    )
                }
            }.getOrDefault("")
        }
    }

    val remaining = if (state.type == OtpType.TOTP) {
        OtpEngine.remainingSeconds(now, state.periodSeconds)
    } else {
        -1
    }

    LotusCard {
        Column {
            Text(
                text = stringResource(R.string.otp_live_preview),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = code.chunked(if (code.isNotEmpty() && code.length % 3 == 0) 3 else 4)
                        .joinToString(" ")
                        .ifEmpty { "— — —" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (code.isEmpty()) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(12.dp))
                if (remaining >= 0) {
                    Text(
                        text = stringResource(R.string.otp_remaining, remaining),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (code.isNotEmpty() && remaining >= 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { 1f - remaining.toFloat() / state.periodSeconds },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AlgorithmPicker(
    current: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        MonoText(text = current, small = false, modifier = Modifier.weight(1f))
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.otp_algorithm_label))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OtpEngine.Algorithm.entries.forEach { algorithm ->
                DropdownMenuItem(
                    text = { Text(algorithm.otpauthName) },
                    onClick = {
                        onSelect(algorithm.otpauthName)
                        expanded = false
                    },
                )
            }
        }
    }
}
