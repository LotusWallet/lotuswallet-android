package com.lotuswallet.app.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.WalletRepository
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.app.util.Clipboard
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.database.entity.WalletType
import com.lotuswallet.core.designsystem.component.LotusChip
import com.lotuswallet.core.designsystem.component.SegmentedControl
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.network.chain.supportsKeyDerivation
import com.lotuswallet.core.datastore.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 创建/导入方式。 */
enum class AddWalletMode { CREATE, IMPORT_MNEMONIC, IMPORT_PRIVATE_KEY, WATCH_ONLY }

@HiltViewModel
class AddWalletViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val chainRepository: ChainRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class UiState(
        val mode: AddWalletMode = AddWalletMode.CREATE,
        val name: String = "",
        val mnemonicInput: String = "",
        val privateKeyInput: String = "",
        val watchAddress: String = "",
        val watchChainId: String = "",
        val generatedMnemonic: String = "",
        val chains: List<ChainConfig> = emptyList(),
        val selectedChainIds: Set<String> = emptySet(),
        val busy: Boolean = false,
    ) {
        val isWatchOnly: Boolean get() = mode == AddWalletMode.WATCH_ONLY

        /**
         * 当前模式下可选的链。
         *
         * 观察钱包可以绑定任意链（用户手上就有地址）；其余模式需要**能派生**，
         * 因此把「暂不支持派生」的链（当前是 Chia）从选项里去掉 ——
         * 让用户选一条选完必然报错的链，是把问题推给用户。
         */
        val selectableChains: List<ChainConfig>
            get() = if (isWatchOnly) chains else chains.filter { it.supportsKeyDerivation }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 创建成功后的钱包 id，用于跳转到助记词备份页。 */
    private val _created = MutableStateFlow<Pair<String, Boolean>?>(null)
    val created: StateFlow<Pair<String, Boolean>?> = _created.asStateFlow()

    init {
        // 默认模式就是 CREATE：必须**立刻**生成助记词，不能等用户点击分段控件。
        // 之前只在 setMode(CREATE) 里生成，而用户落地时 CREATE 已选中、不触发点击，
        // 直接提交会拿着空字符串去校验 →「本地生成的助记词校验失败」。
        if (_state.value.generatedMnemonic.isEmpty()) {
            _state.value = _state.value.copy(generatedMnemonic = walletRepository.generateMnemonic(128))
        }
        viewModelScope.launch {
            val showTestnets = settings.current().showTestnets
            // 默认只派生主流链，避免一创建就出现十几条空地址
            val defaultIds = setOf("bitcoin", "ethereum", "bsc", "tron")
            val chains = chainRepository.current().filter { it.enabled && (showTestnets || !it.testnet) }
            _state.value = _state.value.copy(
                chains = chains,
                selectedChainIds = chains.map { it.id }.filter { it in defaultIds }.toSet()
                    .ifEmpty { chains.filter { !it.testnet }.take(2).map { it.id }.toSet() },
            )
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeCreated() {
        _created.value = null
    }

    fun setMode(mode: AddWalletMode) {
        _state.value = _state.value.copy(mode = mode)
        if (mode == AddWalletMode.CREATE && _state.value.generatedMnemonic.isEmpty()) {
            _state.value = _state.value.copy(generatedMnemonic = walletRepository.generateMnemonic(128))
        }
    }

    /**
     * 重新生成一组助记词。
     *
     * 用途：用户对新生成的词组不满意（例如担心已被别人用过、或者只是想换一组），
     * 可以在**尚未创建钱包之前**反复重掷。已经创建的钱包不会被影响 ——
     * 这里改的只是「待提交的草稿」。
     */
    fun regenerateMnemonic() {
        _state.value = _state.value.copy(generatedMnemonic = walletRepository.generateMnemonic(128))
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun setMnemonic(value: String) {
        _state.value = _state.value.copy(mnemonicInput = value)
    }

    fun setPrivateKey(value: String) {
        _state.value = _state.value.copy(privateKeyInput = value)
    }

    fun setWatchAddress(value: String) {
        _state.value = _state.value.copy(watchAddress = value)
    }

    fun setWatchChain(chainId: String) {
        _state.value = _state.value.copy(watchChainId = chainId)
    }

    fun toggleChain(chainId: String) {
        val current = _state.value.selectedChainIds
        _state.value = _state.value.copy(
            selectedChainIds = if (chainId in current) current - chainId else current + chainId,
        )
    }

    fun pasteFromClipboard(text: String) {
        when (_state.value.mode) {
            AddWalletMode.IMPORT_MNEMONIC -> setMnemonic(text)
            AddWalletMode.IMPORT_PRIVATE_KEY -> setPrivateKey(text)
            AddWalletMode.WATCH_ONLY -> setWatchAddress(text)
            AddWalletMode.CREATE -> Unit
        }
    }

    fun submit() {
        viewModelScope.launch {
            val s = _state.value
            _state.value = s.copy(busy = true)
            try {
                when (s.mode) {
                    AddWalletMode.CREATE -> {
                        // 兜底：无论上游状态如何，提交时绝不允许空助记词走到校验。
                        val mnemonic = s.generatedMnemonic.ifBlank {
                            walletRepository.generateMnemonic(128).also { fresh ->
                                _state.value = _state.value.copy(generatedMnemonic = fresh)
                            }
                        }
                        if (!walletRepository.isValidMnemonic(mnemonic)) {
                            _message.value = "本地生成的助记词校验失败，请重试"
                            return@launch
                        }
                        val id = walletRepository.createMnemonicWallet(
                            name = s.name.ifBlank { "我的钱包" },
                            mnemonic = mnemonic,
                            chains = selectedChains(),
                        )
                        _created.value = id to true
                    }

                    AddWalletMode.IMPORT_MNEMONIC -> {
                        val id = walletRepository.createMnemonicWallet(
                            name = s.name.ifBlank { "导入钱包" },
                            mnemonic = s.mnemonicInput,
                            chains = selectedChains(),
                        )
                        _created.value = id to false
                    }

                    AddWalletMode.IMPORT_PRIVATE_KEY -> {
                        val id = walletRepository.importPrivateKey(
                            name = s.name.ifBlank { "私钥钱包" },
                            privateKeyHex = s.privateKeyInput,
                            chains = selectedChains(),
                        )
                        _created.value = id to false
                    }

                    AddWalletMode.WATCH_ONLY -> {
                        val chain = chainRepository.findById(s.watchChainId)
                            ?: s.chains.firstOrNull()
                            ?: error("没有可用的链")
                        val id = walletRepository.importWatchOnly(
                            name = s.name.ifBlank { "观察钱包" },
                            chain = chain,
                            address = s.watchAddress,
                        )
                        _created.value = id to false
                    }
                }
            } catch (t: Throwable) {
                _message.value = t.toUserMessage()
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** 观察钱包只需要一条链；其它模式用多选。 */
    private fun selectedChains(): List<ChainConfig> {
        val s = _state.value
        if (s.isWatchOnly) {
            return listOfNotNull(chainRepository.findById(s.watchChainId) ?: s.chains.firstOrNull())
        }
        return s.chains.filter { it.id in s.selectedChainIds }
    }
}

/**
 * 创建 / 导入钱包。
 *
 * 「观察钱包」单独分支：它只需要一条链、一个地址，且永远不能签名 ——
 * UI 上必须把这一点说清楚，否则用户会以为自己的资产「转不出去」。
 */
@Composable
fun AddWalletScreen(
    onBack: () -> Unit,
    onCreated: (walletId: String, needsBackup: Boolean) -> Unit,
    viewModel: AddWalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val created by viewModel.created.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var acknowledged by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    // 创建成功后立刻离开本页（是否需要备份由上层决定）
    if (created != null && !acknowledged) {
        acknowledged = true
        val (id, needsBackup) = created!!
        viewModel.consumeCreated()
        onCreated(id, needsBackup)
    }

    DetailScaffold(
        title = stringResource(R.string.wallet_import_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                PrimaryButton(
                    text = when (state.mode) {
                        AddWalletMode.CREATE -> stringResource(R.string.wallet_create)
                        else -> stringResource(R.string.action_import)
                    },
                    enabled = !state.busy && submitEnabled(state),
                    onClick = viewModel::submit,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------------------------------------------- 模式
            // 用分段控件而不是四个平铺按钮：分段控件明确表达「我在切换输入方式」，
            // 而四个并列大按钮读起来像四个独立动作，用户会犹豫该点哪个。
            // 这里刻意不包一层卡片 —— 表单顶部的切换器直接放在页面背景上更透气。
            val modeLabels = listOf(
                stringResource(R.string.wallet_generate_title),
                stringResource(R.string.wallet_import_mnemonic),
                stringResource(R.string.wallet_import_private_key),
                stringResource(R.string.wallet_import_watch_only),
            )
            val modeValues = listOf(
                AddWalletMode.CREATE,
                AddWalletMode.IMPORT_MNEMONIC,
                AddWalletMode.IMPORT_PRIVATE_KEY,
                AddWalletMode.WATCH_ONLY,
            )
            SegmentedControl(
                options = modeLabels,
                selected = modeValues.indexOf(state.mode).coerceAtLeast(0),
                onSelect = { index -> viewModel.setMode(modeValues[index]) },
            )

            LotusTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = stringResource(R.string.wallet_name_label),
                placeholder = stringResource(R.string.wallet_name_default),
            )

            // ---------------------------------------------------- 按模式输入
            when (state.mode) {
                AddWalletMode.CREATE -> {
                    SectionCard(title = stringResource(R.string.wallet_generate_title)) {
                        Text(
                            text = stringResource(R.string.wallet_generate_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        com.lotuswallet.core.designsystem.component.MonoText(
                            text = state.generatedMnemonic,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            small = false,
                            maxLines = 6,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { Clipboard.copySensitive(context, state.generatedMnemonic) },
                                modifier = Modifier.padding(start = 8.dp),
                            ) { Text(stringResource(R.string.action_copy)) }

                            // 重掷按钮刻意做得轻：它是「还没创建之前的草稿操作」，
                            // 不应该和「复制 / 创建」抢视觉重心。
                            TextButton(
                                onClick = viewModel::regenerateMnemonic,
                                enabled = !state.busy,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.wallet_generate_regenerate),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }

                AddWalletMode.IMPORT_MNEMONIC -> LotusTextField(
                    value = state.mnemonicInput,
                    onValueChange = viewModel::setMnemonic,
                    label = stringResource(R.string.wallet_import_mnemonic),
                    placeholder = stringResource(R.string.wallet_import_mnemonic_hint),
                    singleLine = false,
                    isError = state.mnemonicInput.isNotBlank() && !viewModel.isValidMnemonic(state.mnemonicInput),
                    supportingText = if (state.mnemonicInput.isNotBlank() && !viewModel.isValidMnemonic(state.mnemonicInput)) {
                        stringResource(R.string.wallet_import_invalid_mnemonic)
                    } else null,
                    trailingIcon = {
                        PasteButton { Clipboard.read(context)?.let(viewModel::pasteFromClipboard) }
                    },
                )

                AddWalletMode.IMPORT_PRIVATE_KEY -> LotusTextField(
                    value = state.privateKeyInput,
                    onValueChange = viewModel::setPrivateKey,
                    label = stringResource(R.string.wallet_import_private_key),
                    singleLine = false,
                    trailingIcon = {
                        PasteButton { Clipboard.read(context)?.let(viewModel::pasteFromClipboard) }
                    },
                )

                AddWalletMode.WATCH_ONLY -> {
                    LotusTextField(
                        value = state.watchAddress,
                        onValueChange = viewModel::setWatchAddress,
                        label = stringResource(R.string.wallet_address),
                        singleLine = false,
                        trailingIcon = {
                            PasteButton { Clipboard.read(context)?.let(viewModel::pasteFromClipboard) }
                        },
                    )
                    WarningCard(text = stringResource(R.string.wallet_watch_only_hint))
                }
            }

            // ---------------------------------------------------- 链选择
            SectionCard(title = stringResource(R.string.wallet_chain_select)) {
                if (state.isWatchOnly) {
                    Text(
                        text = "观察钱包只能绑定一条链",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                val chains = if (state.isWatchOnly) state.selectableChains.take(8) else state.selectableChains
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    chains.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { chain ->
                                val selected = if (state.isWatchOnly) {
                                    state.watchChainId == chain.id || (state.watchChainId.isEmpty() && chain == chains.first())
                                } else {
                                    chain.id in state.selectedChainIds
                                }
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        if (state.isWatchOnly) viewModel.setWatchChain(chain.id)
                                        else viewModel.toggleChain(chain.id)
                                    },
                                    label = { Text("${chain.name} · ${chain.symbol}", style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            if (!state.isWatchOnly) {
                WarningCard(
                    text = "同一助记词在所有 EVM 链上的地址是相同的，因此勾选多条 EVM 链不会产生多个地址主体。",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PasteButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.action_paste)) }
}

/** 判断提交按钮是否可用（纯本地校验，避免无效请求）。 */
private fun submitEnabled(state: AddWalletViewModel.UiState): Boolean = when (state.mode) {
    AddWalletMode.CREATE -> state.selectedChainIds.isNotEmpty()
    AddWalletMode.IMPORT_MNEMONIC -> state.mnemonicInput.isNotBlank() && state.selectedChainIds.isNotEmpty()
    AddWalletMode.IMPORT_PRIVATE_KEY -> state.privateKeyInput.isNotBlank() && state.selectedChainIds.isNotEmpty()
    AddWalletMode.WATCH_ONLY -> state.watchAddress.isNotBlank()
}

/** 供 UI 复用的小工具。 */
private fun AddWalletViewModel.isValidMnemonic(text: String): Boolean =
    com.lotuswallet.core.crypto.Bip39.validate(text)
