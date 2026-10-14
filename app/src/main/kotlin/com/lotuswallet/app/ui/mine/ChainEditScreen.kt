package com.lotuswallet.app.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.ChainEditor
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.SwitchRow
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.core.designsystem.component.SegmentedControl
import com.lotuswallet.core.network.chain.ChainConfig
import com.lotuswallet.core.network.chain.ChainDraft
import com.lotuswallet.core.network.chain.Ed25519Derivation
import com.lotuswallet.core.network.chain.Ed25519Variant
import com.lotuswallet.core.network.chain.UtxoAddressType
import com.lotuswallet.core.network.chain.commonCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 链的新建 / 编辑表单。
 *
 * 表单字段随**家族**变化：EVM 只关心 chainId 与 EIP-1559，UTXO 关心地址类型与三个版本字节，
 * ed25519 系关心地址编码与派生路径。把不相关的字段藏起来，比一次铺 20 个输入框可用得多。
 */
@HiltViewModel
class ChainEditViewModel @Inject constructor(
    private val editor: ChainEditor,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val chainId: String = savedState.get<String>("chainId").orEmpty()
    private val familyArg: String = savedState.get<String>("family").orEmpty()

    data class UiState(
        val draft: ChainDraft = ChainDraft(),
        val isNew: Boolean = true,
        val saving: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            if (chainId.isBlank() || chainId == "new") {
                val family = parseFamily(familyArg)
                _state.value = UiState(draft = ChainDraft.empty(family), isNew = true)
            } else {
                val existing = editor.find(chainId)
                _state.value = if (existing == null) {
                    UiState(draft = ChainDraft.empty(ChainConfig.Family.EVM), isNew = true)
                } else {
                    UiState(draft = ChainDraft.from(existing), isNew = false)
                }
            }
        }
    }

    fun update(transform: (ChainDraft) -> ChainDraft) {
        _state.value = _state.value.copy(draft = transform(_state.value.draft))
    }

    fun setRpc(index: Int, value: String) = update { draft ->
        draft.copy(rpcUrls = draft.rpcUrls.toMutableList().also { it[index] = value })
    }

    fun addRpc() = update { draft -> draft.copy(rpcUrls = draft.rpcUrls + "") }

    fun removeRpc(index: Int) = update { draft ->
        val list = draft.rpcUrls.toMutableList().also { it.removeAt(index) }
        draft.copy(rpcUrls = list.ifEmpty { mutableListOf("") })
    }

    fun save() {
        val draft = _state.value.draft
        draft.validationError()?.let {
            _message.value = it
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            val config = draft.build()
            if (config == null) {
                _message.value = "配置不完整，请检查必填项"
                _state.value = _state.value.copy(saving = false)
                return@launch
            }
            val id = if (draft.isNew) editor.suggestId(draft.name.ifBlank { draft.symbol }) else draft.id
            editor.upsert(withId(config, id))
            _state.value = _state.value.copy(saving = false)
            _saved.value = true
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun withId(config: ChainConfig, id: String): ChainConfig = config.commonCopy(id = id)

    private fun parseFamily(raw: String): ChainConfig.Family = when (raw.lowercase()) {
        "utxo" -> ChainConfig.Family.UTXO
        "tron" -> ChainConfig.Family.TRON
        "ed25519" -> ChainConfig.Family.ED25519
        "filecoin" -> ChainConfig.Family.FILECOIN
        "chia" -> ChainConfig.Family.CHIA
        else -> ChainConfig.Family.EVM
    }
}

@Composable
fun ChainEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ChainEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var acknowledged by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    LaunchedEffect(saved) {
        if (saved && !acknowledged) {
            acknowledged = true
            onSaved()
        }
    }

    val draft = state.draft

    DetailScaffold(
        title = stringResource(if (state.isNew) R.string.chain_add_title else R.string.chain_edit_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.action_save),
                    enabled = !state.saving,
                    onClick = viewModel::save,
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
            // ---------------------------------------------------- 家族
            if (state.isNew) {
                SectionCard(title = stringResource(R.string.chain_field_family)) {
                    val families = listOf(
                        ChainConfig.Family.EVM,
                        ChainConfig.Family.UTXO,
                        ChainConfig.Family.ED25519,
                        ChainConfig.Family.FILECOIN,
                        ChainConfig.Family.TRON,
                        ChainConfig.Family.CHIA,
                    )
                    val labels = listOf("EVM", "UTXO", "ed25519", "Filecoin", "TRON", "Chia")
                    SegmentedControl(
                        options = labels,
                        selected = families.indexOf(draft.family).coerceAtLeast(0),
                        onSelect = { index ->
                            val family = families[index]
                            viewModel.update { ChainDraft.empty(family).copy(id = it.id) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = familyHint(draft.family),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // ---------------------------------------------------- 基本信息
            SectionCard(title = stringResource(R.string.chain_section_basic)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    LotusTextField(
                        value = draft.name,
                        onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                        label = stringResource(R.string.chain_field_name),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    LotusTextField(
                        value = draft.symbol,
                        onValueChange = { v -> viewModel.update { it.copy(symbol = v) } },
                        label = stringResource(R.string.chain_field_symbol),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LotusTextField(
                            value = draft.decimals,
                            onValueChange = { v -> viewModel.update { it.copy(decimals = v.filter { c -> c.isDigit() }) } },
                            label = stringResource(R.string.chain_field_decimals),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        LotusTextField(
                            value = draft.coinType,
                            onValueChange = { v -> viewModel.update { it.copy(coinType = v.filter { c -> c.isDigit() }) } },
                            label = stringResource(R.string.chain_field_coin_type),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (draft.family == ChainConfig.Family.EVM) {
                        Spacer(Modifier.height(10.dp))
                        LotusTextField(
                            value = draft.chainId,
                            onValueChange = { v -> viewModel.update { it.copy(chainId = v.filter { c -> c.isDigit() }) } },
                            label = stringResource(R.string.chain_field_chain_id),
                            singleLine = true,
                        )
                    }
                }
            }

            // ---------------------------------------------------- RPC 节点（列表）
            SectionCard(title = stringResource(R.string.chain_field_rpc)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.chain_rpc_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    draft.rpcUrls.forEachIndexed { index, url ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LotusTextField(
                                value = url,
                                onValueChange = { v -> viewModel.setRpc(index, v) },
                                label = stringResource(R.string.chain_rpc_item, index + 1),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { viewModel.removeRpc(index) },
                                enabled = draft.rpcUrls.size > 1,
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = viewModel::addRpc) {
                            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.chain_rpc_add))
                        }
                        Text(
                            text = stringResource(R.string.chain_rpc_add),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // ---------------------------------------------------- 家族特有字段
            when (draft.family) {
                ChainConfig.Family.EVM -> SectionCard(title = stringResource(R.string.chain_section_family)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SwitchRow(
                            title = stringResource(R.string.chain_field_eip1559),
                            checked = draft.supportsEip1559,
                            onCheckedChange = { v -> viewModel.update { it.copy(supportsEip1559 = v) } },
                            modifier = Modifier.padding(horizontal = 0.dp),
                        )
                    }
                }

                ChainConfig.Family.UTXO -> SectionCard(title = stringResource(R.string.chain_section_family)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        val types = UtxoAddressType.entries
                        FieldLabel(stringResource(R.string.chain_field_address_type))
                        SegmentedControl(
                            options = types.map { it.name },
                            selected = types.indexOf(draft.addressType).coerceAtLeast(0),
                            onSelect = { index -> viewModel.update { it.copy(addressType = types[index]) } },
                        )
                        Spacer(Modifier.height(10.dp))
                        LotusTextField(
                            value = draft.hrp,
                            onValueChange = { v -> viewModel.update { it.copy(hrp = v) } },
                            label = stringResource(R.string.chain_field_hrp),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LotusTextField(
                                value = draft.p2pkhVersion,
                                onValueChange = { v -> viewModel.update { it.copy(p2pkhVersion = v.filter { c -> c.isDigit() }) } },
                                label = stringResource(R.string.chain_field_p2pkh),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            LotusTextField(
                                value = draft.p2shVersion,
                                onValueChange = { v -> viewModel.update { it.copy(p2shVersion = v.filter { c -> c.isDigit() }) } },
                                label = stringResource(R.string.chain_field_p2sh),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LotusTextField(
                            value = draft.dustThreshold,
                            onValueChange = { v -> viewModel.update { it.copy(dustThreshold = v.filter { c -> c.isDigit() }) } },
                            label = stringResource(R.string.chain_field_dust),
                            singleLine = true,
                        )
                    }
                }

                ChainConfig.Family.ED25519 -> SectionCard(title = stringResource(R.string.chain_section_family)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        val variants = Ed25519Variant.entries
                        FieldLabel(stringResource(R.string.chain_field_variant))
                        SegmentedControl(
                            options = variants.map { it.name },
                            selected = variants.indexOf(draft.variant).coerceAtLeast(0),
                            onSelect = { index -> viewModel.update { it.copy(variant = variants[index]) } },
                        )
                        Spacer(Modifier.height(10.dp))
                        val derivations = Ed25519Derivation.entries
                        FieldLabel(stringResource(R.string.chain_field_derivation))
                        SegmentedControl(
                            options = derivations.map { it.name },
                            selected = derivations.indexOf(draft.derivation).coerceAtLeast(0),
                            onSelect = { index -> viewModel.update { it.copy(derivation = derivations[index]) } },
                        )
                        Spacer(Modifier.height(10.dp))
                        LotusTextField(
                            value = draft.path,
                            onValueChange = { v -> viewModel.update { it.copy(path = v) } },
                            label = stringResource(R.string.chain_field_path),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        LotusTextField(
                            value = draft.addressPrefix,
                            onValueChange = { v -> viewModel.update { it.copy(addressPrefix = v.filter { c -> c.isDigit() }) } },
                            label = stringResource(R.string.chain_field_address_prefix),
                            singleLine = true,
                        )
                    }
                }

                ChainConfig.Family.FILECOIN -> SectionCard(title = stringResource(R.string.chain_section_family)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        LotusTextField(
                            value = draft.path,
                            onValueChange = { v -> viewModel.update { it.copy(path = v) } },
                            label = stringResource(R.string.chain_field_path),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        LotusTextField(
                            value = draft.network,
                            onValueChange = { v -> viewModel.update { it.copy(network = v.take(1)) } },
                            label = stringResource(R.string.chain_field_network),
                            singleLine = true,
                        )
                    }
                }

                ChainConfig.Family.CHIA -> SectionCard(title = stringResource(R.string.chain_section_family)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        LotusTextField(
                            value = draft.path,
                            onValueChange = { v -> viewModel.update { it.copy(path = v) } },
                            label = stringResource(R.string.chain_field_path),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        LotusTextField(
                            value = draft.chiaHrp,
                            onValueChange = { v -> viewModel.update { it.copy(chiaHrp = v) } },
                            label = stringResource(R.string.chain_field_hrp),
                            singleLine = true,
                        )
                    }
                }

                ChainConfig.Family.TRON -> Unit
            }

            // ---------------------------------------------------- 浏览器与状态
            SectionCard(title = stringResource(R.string.chain_section_misc)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    LotusTextField(
                        value = draft.explorerTxUrl,
                        onValueChange = { v -> viewModel.update { it.copy(explorerTxUrl = v) } },
                        label = stringResource(R.string.chain_field_explorer_tx),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    LotusTextField(
                        value = draft.explorerAddressUrl,
                        onValueChange = { v -> viewModel.update { it.copy(explorerAddressUrl = v) } },
                        label = stringResource(R.string.chain_field_explorer_address),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        title = stringResource(R.string.chain_field_testnet),
                        checked = draft.testnet,
                        onCheckedChange = { v -> viewModel.update { it.copy(testnet = v) } },
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                    SwitchRow(
                        title = stringResource(R.string.chain_field_enabled),
                        checked = draft.enabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } },
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                }
            }

            WarningCard(
                text = stringResource(R.string.chain_edit_warning),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun familyHint(family: ChainConfig.Family): String = when (family) {
    ChainConfig.Family.EVM -> stringResource(R.string.chain_family_hint_evm)
    ChainConfig.Family.UTXO -> stringResource(R.string.chain_family_hint_utxo)
    ChainConfig.Family.TRON -> stringResource(R.string.chain_family_hint_tron)
    ChainConfig.Family.ED25519 -> stringResource(R.string.chain_family_hint_ed25519)
    ChainConfig.Family.FILECOIN -> stringResource(R.string.chain_family_hint_filecoin)
    ChainConfig.Family.CHIA -> stringResource(R.string.chain_family_hint_chia)
}

/**
 * 分段选择器的小标题。
 *
 * 少了它，「地址编码 / 派生算法 / 地址类型」这三排按钮就是**一串没有题目的选项**：
 * 用户看到 `SOLANA | ICP | MONERO` 却不知道这是在选什么，改错了会直接导致地址变化。
 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp),
    )
}
