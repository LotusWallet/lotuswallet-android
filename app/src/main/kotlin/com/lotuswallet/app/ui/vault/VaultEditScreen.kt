package com.lotuswallet.app.ui.vault

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.VaultRepository
import com.lotuswallet.app.navigation.Routes
import com.lotuswallet.app.ui.common.ConfirmDialog
import com.lotuswallet.app.ui.common.DetailScaffold
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.PrimaryButton
import com.lotuswallet.app.ui.common.SectionCard
import com.lotuswallet.app.ui.common.SecretTextField
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.util.toUserMessage
import com.lotuswallet.core.database.entity.VaultFieldType
import com.lotuswallet.core.database.entity.VaultItemType
import com.lotuswallet.core.designsystem.component.LotusDropdownField
import com.lotuswallet.core.designsystem.component.ReadableContent
import com.lotuswallet.core.designsystem.theme.LotusTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 保险库条目编辑器。
 *
 * ## 模板
 * 新建条目时按分类自动套用一套推荐字段（见 [VaultTemplates]）。
 * 用户可以在此基础上增删 —— 目的是消除「面对空白表单不知道该填什么」这一门槛。
 * 一旦用户手动改过字段，就不再自动套模板，避免把他的输入冲掉。
 */
@HiltViewModel
class VaultEditViewModel @Inject constructor(
    private val repository: VaultRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val rawId: String = savedStateHandle.get<String>("itemId").orEmpty()

    /** null 表示新建。 */
    private val itemId: String? = rawId.takeIf { it.isNotBlank() && it != Routes.NEW }

    private val presetType: VaultItemType? = savedStateHandle.get<String>("type")
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { VaultItemType.valueOf(it) }.getOrNull() }

    companion object {
        /** 新建条目的默认类别：网站登录（使用频率最高）。 */
        val DEFAULT_TYPE: VaultItemType = VaultItemType.WEBSITE_LOGIN
    }

    data class UiState(
        val title: String = "",
        val subtitle: String = "",
        // 默认「网站登录」：这是保险库里最高频的类别，用它做默认值能省掉一次点选
        val type: VaultItemType = DEFAULT_TYPE,
        val favorite: Boolean = false,
        val fields: List<VaultRepository.DraftField> = emptyList(),
        val loading: Boolean = false,
        val busy: Boolean = false,
        val saved: Boolean = false,
        val dirty: Boolean = false,
    )

    private val _state = MutableStateFlow(
        UiState(loading = itemId != null, type = presetType ?: DEFAULT_TYPE),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val isNew: Boolean = itemId == null

    init {
        if (itemId == null) {
            // 新建：先套模板，让表单不是空的
            applyTemplate(_state.value.type)
        } else {
            load()
        }
    }

    private fun load() {
        viewModelScope.launch {
            runCatching {
                val item = repository.item(itemId!!) ?: return@runCatching
                val drafts = repository.plainFields(item.id).map { field ->
                    VaultRepository.DraftField(
                        localId = field.id,
                        label = field.label,
                        value = field.value,
                        sensitive = field.sensitive,
                        type = field.type,
                    )
                }
                _state.value = _state.value.copy(
                    title = item.title,
                    subtitle = item.subtitle.orEmpty(),
                    type = item.type,
                    favorite = item.favorite,
                    fields = drafts,
                    loading = false,
                    dirty = false,
                )
            }.onFailure {
                _message.value = it.toUserMessage()
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    // ------------------------------------------------------------ 表单

    fun setTitle(value: String) {
        _state.value = _state.value.copy(title = value, dirty = true)
    }

    fun setSubtitle(value: String) {
        _state.value = _state.value.copy(subtitle = value, dirty = true)
    }

    fun setType(type: VaultItemType) {
        _state.value = _state.value.copy(type = type, dirty = true)
        // 新建且用户还没填任何内容时，跟随分类切换模板
        if (itemId == null && _state.value.fields.none { it.value.isNotBlank() }) {
            applyTemplate(type)
        }
    }

    fun applyTemplate(type: VaultItemType = _state.value.type) {
        val drafted = VaultTemplates.fieldsFor(type).map { spec ->
            VaultRepository.DraftField(label = spec.label, type = spec.type, sensitive = spec.sensitive)
        }
        _state.value = _state.value.copy(fields = drafted)
    }

    fun addField() {
        _state.value = _state.value.copy(
            fields = _state.value.fields + VaultRepository.DraftField(),
            dirty = true,
        )
    }

    fun removeField(localId: String) {
        _state.value = _state.value.copy(
            fields = _state.value.fields.filterNot { it.localId == localId },
            dirty = true,
        )
    }

    fun updateLabel(localId: String, value: String) = updateField(localId) { it.copy(label = value) }

    fun updateValue(localId: String, value: String) = updateField(localId) { it.copy(value = value) }

    fun updateFieldType(localId: String, type: VaultFieldType) =
        updateField(localId) { it.copy(type = type) }

    fun toggleSensitive(localId: String) =
        updateField(localId) { it.copy(sensitive = !it.sensitive) }

    private fun updateField(localId: String, transform: (VaultRepository.DraftField) -> VaultRepository.DraftField) {
        _state.value = _state.value.copy(
            fields = _state.value.fields.map { if (it.localId == localId) transform(it) else it },
            dirty = true,
        )
    }

    // ------------------------------------------------------------ 保存

    fun save() {
        val s = _state.value
        if (s.title.isBlank()) {
            _message.value = "请填写标题"
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(busy = true)
            runCatching {
                repository.save(
                    itemId = itemId,
                    type = s.type,
                    title = s.title,
                    subtitle = s.subtitle,
                    favorite = s.favorite,
                    fields = s.fields,
                )
            }.onSuccess {
                _state.value = _state.value.copy(busy = false, saved = true, dirty = false)
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
fun VaultEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: VaultEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 待填入密码生成结果的字段 id
    var generatorFor by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    if (state.saved) {
        onSaved()
        return
    }

    /** 有未保存修改时先确认，避免误触返回丢掉刚填的内容。 */
    fun attemptBack() {
        if (state.dirty) confirmDiscard = true else onBack()
    }

    DetailScaffold(
        title = stringResource(
            if (viewModel.isNew) R.string.vault_new else R.string.vault_edit,
        ),
        onBack = { attemptBack() },
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { viewModel.applyTemplate() }) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = stringResource(R.string.vault_template_apply),
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ------------------------------------------------ 分类
                // 用下拉框而不是把 5 个类别平铺成大按钮：分类是低频决策，
                // 平铺会占据表单顶部最显眼的位置、并把真正的输入项挤到折叠线以下。
                // 下拉框只占一行，且默认值（网站登录）直接可见 —— 大多数用户无需改动。
                LotusDropdownField(
                    options = VaultItemType.entries,
                    selected = state.type,
                    label = { typeLabel(it) },
                    onSelected = { viewModel.setType(it) },
                    placeholder = stringResource(R.string.vault_type_label),
                )

                LotusTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = stringResource(R.string.vault_title_label),
                    supportingText = stringResource(R.string.vault_title_required)
                        .takeIf { state.title.isBlank() },
                    isError = state.title.isBlank(),
                )

                LotusTextField(
                    value = state.subtitle,
                    onValueChange = viewModel::setSubtitle,
                    label = stringResource(R.string.vault_subtitle_label),
                    placeholder = stringResource(R.string.label_optional),
                )

                // ------------------------------------------------ 字段
                state.fields.forEachIndexed { index, field ->
                    FieldEditor(
                        index = index,
                        field = field,
                        onLabelChange = { viewModel.updateLabel(field.localId, it) },
                        onValueChange = { viewModel.updateValue(field.localId, it) },
                        onTypeChange = { viewModel.updateFieldType(field.localId, it) },
                        onToggleSensitive = { viewModel.toggleSensitive(field.localId) },
                        onRemove = { viewModel.removeField(field.localId) },
                        onGenerate = { generatorFor = field.localId },
                    )
                }

                TextButton(onClick = viewModel::addField) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.vault_add_field))
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ------------------------------------------------ 生成器（底部弹层）
    generatorFor?.let { localId ->
        PasswordGeneratorSheet(
            onDismiss = { generatorFor = null },
            onUse = { password -> viewModel.updateValue(localId, password) },
            onCopied = {},
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.action_back),
            message = stringResource(R.string.vault_unsaved_changes),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/** 单个字段的编辑块：字段名 + 类型 + 敏感开关 + 值。 */
@Composable
private fun FieldEditor(
    index: Int,
    field: VaultRepository.DraftField,
    onLabelChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onTypeChange: (VaultFieldType) -> Unit,
    onToggleSensitive: () -> Unit,
    onRemove: () -> Unit,
    onGenerate: () -> Unit,
) {
    SectionCard {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    LotusTextField(
                        value = field.label,
                        onValueChange = onLabelChange,
                        label = stringResource(R.string.vault_field_label),
                        placeholder = "字段 ${index + 1}",
                    )
                }
                Spacer(Modifier.width(8.dp))
                FieldTypeMenu(current = field.type, onSelect = onTypeChange)
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = LotusTheme.semantic.danger,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            FieldValueInput(
                field = field,
                onValueChange = onValueChange,
            )

            if (field.type == VaultFieldType.PASSWORD || field.type == VaultFieldType.TOTP_SECRET) {
                TextButton(onClick = onGenerate) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.height(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.vault_generator),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldValueInput(
    field: VaultRepository.DraftField,
    onValueChange: (String) -> Unit,
) {
    val label = stringResource(R.string.vault_field_value)
    when (field.type) {
        VaultFieldType.PASSWORD, VaultFieldType.TOTP_SECRET -> SecretTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = label,
        )

        VaultFieldType.MULTILINE -> LotusTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = label,
            singleLine = false,
        )

        VaultFieldType.EMAIL -> LotusTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = label,
            keyboardType = KeyboardType.Email,
        )

        VaultFieldType.PHONE -> LotusTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = label,
            keyboardType = KeyboardType.Phone,
        )

        VaultFieldType.NUMBER -> LotusTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = label,
            keyboardType = KeyboardType.Number,
        )

        VaultFieldType.URL -> LotusTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = label,
            keyboardType = KeyboardType.Uri,
        )

        VaultFieldType.DATE, VaultFieldType.TEXT -> LotusTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = label,
        )
    }
}

@Composable
private fun FieldTypeMenu(
    current: VaultFieldType,
    onSelect: (VaultFieldType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = stringResource(R.string.vault_field_type),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VaultFieldType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(fieldTypeLabel(type)) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun fieldTypeLabel(type: VaultFieldType): String = stringResource(
    when (type) {
        VaultFieldType.TEXT -> R.string.vault_field_text
        VaultFieldType.PASSWORD -> R.string.vault_field_password
        VaultFieldType.URL -> R.string.vault_field_url
        VaultFieldType.EMAIL -> R.string.vault_field_email
        VaultFieldType.PHONE -> R.string.vault_field_phone
        VaultFieldType.NUMBER -> R.string.vault_field_number
        VaultFieldType.DATE -> R.string.vault_field_date
        VaultFieldType.MULTILINE -> R.string.vault_field_multiline
        VaultFieldType.TOTP_SECRET -> R.string.vault_field_totp_secret
    },
)
