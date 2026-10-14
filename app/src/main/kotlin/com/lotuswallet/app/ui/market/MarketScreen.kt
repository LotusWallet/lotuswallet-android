package com.lotuswallet.app.ui.market

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lotuswallet.app.R
import com.lotuswallet.app.data.StartupRepository
import com.lotuswallet.app.ui.common.LotusTextField
import com.lotuswallet.app.ui.common.SnackbarEffect
import com.lotuswallet.app.ui.common.WarningCard
import com.lotuswallet.app.util.Browser
import com.lotuswallet.core.designsystem.component.AddressAvatar
import com.lotuswallet.core.designsystem.component.EmptyState
import com.lotuswallet.core.designsystem.component.LotusCard
import com.lotuswallet.core.designsystem.component.LotusChip
import com.lotuswallet.core.designsystem.component.LotusTopBar
import com.lotuswallet.core.designsystem.theme.LotusTheme
import com.lotuswallet.core.network.chain.ChainRepository
import com.lotuswallet.core.network.market.MarketCategory
import com.lotuswallet.core.network.market.MarketDapp
import com.lotuswallet.core.network.market.MarketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DApp 市场。
 *
 * ## Serverless 的市场
 * 清单是一份静态只读 JSON（放在 `LotusWallet/assets` 仓库）。客户端**首次打开时抓取**，
 * 之后本地缓存并**立即展示缓存**；缓存超过 24 小时才在后台重新抓取替换。
 * 因此**离线也能用**：展示的是上一次成功拉取的内容，或者应用内置的精选清单。
 * 这里没有任何「服务端推荐」「点击上报」—— 用户点开哪个 DApp 只有他自己知道。
 *
 * ## 为什么跳系统浏览器
 * 本版本不提供 `window.ethereum` 注入。若用应用内 WebView 打开，
 * 用户会以为「钱包正在和 DApp 通信」，而实际上并没有 —— 那是误导。
 *
 * ## 顶部区域为什么随滚动折叠
 * 「链管理 + 搜索」是低频操作，「分类」是高频筛选。让低频区域在滚动时收起来、
 * 高频的分类吸顶常驻，同一屏就能多放一整行 DApp。滚回顶部时它们自动回来。
 */
@HiltViewModel
class MarketViewModel @Inject constructor(
    private val market: MarketRepository,
    private val chains: ChainRepository,
    private val startup: StartupRepository,
) : ViewModel() {

    data class UiState(
        val dapps: List<MarketDapp> = emptyList(),
        val categories: List<MarketCategory> = emptyList(),
        val query: String = "",
        val category: String? = null,
        val refreshing: Boolean = false,
        val builtin: Boolean = true,
        val updatedAt: String? = null,
        val chainNames: Map<String, String> = emptyMap(),
    )

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val revision = MutableStateFlow(0)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<UiState> = combine(query, category, refreshing, revision) { q, c, isRefreshing, _ ->
        val manifest = market.current()
        val base = if (c == null) manifest.dapps.sortedBy { it.order } else market.byCategory(c)
        UiState(
            dapps = if (q.isBlank()) base else base.filter { it.matches(q) },
            categories = manifest.categories.sortedBy { it.order },
            query = q,
            category = c,
            refreshing = isRefreshing,
            builtin = manifest.version == 0,
            updatedAt = manifest.updatedAt,
            chainNames = chains.current().associate { it.id to it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        // 先用缓存秒开，再（按 24 小时有效期决定是否）后台刷新
        viewModelScope.launch {
            startup.primeFromCache()
            startup.refreshRemote(force = false)
            revision.value++
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setCategory(id: String?) {
        category.value = id
    }

    /** 用户主动刷新：忽略缓存有效期，强制联网。 */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            runCatching { startup.refreshRemote(force = true) }
                .onFailure { _message.value = "刷新失败，继续使用本地清单" }
            revision.value++
            refreshing.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

@Composable
fun MarketScreen(
    onOpenChains: () -> Unit,
    viewModel: MarketViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    SnackbarEffect(snackbar, message) { viewModel.consumeMessage() }

    Scaffold(
        topBar = {
            LotusTopBar(
                title = stringResource(R.string.tab_market),
                actions = {
                    if (state.refreshing) {
                        Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val gridState = rememberLazyGridState()

        // 「链管理 + 搜索」只有在列表**完全停在顶部**时才展开。
        // 留 8px 容差，避免手指刚动一下（亚像素抖动）就触发折叠动画。
        val showHeader by remember {
            derivedStateOf {
                gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset <= 8
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---------------------------------------------- 会折叠的顶部区域
            AnimatedVisibility(
                visible = showHeader,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    WarningCard(
                        text = stringResource(R.string.market_risk_warning),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        actionText = stringResource(R.string.mine_chains),
                        onAction = onOpenChains,
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        LotusTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            label = stringResource(R.string.action_search),
                            placeholder = stringResource(R.string.market_search_hint),
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }

            // ---------------------------------------------- 常驻：分类筛选
            CategoryRow(
                categories = state.categories,
                selected = state.category,
                onSelect = viewModel::setCategory,
            )

            Spacer(Modifier.height(4.dp))

            // 清单来源提示（内置 / 远端时间）跟在分类下面，属于「不常看」的信息，
            // 放在这里而不是顶部，折叠时它也一并收起更省空间。
            StatusLine(
                builtin = state.builtin,
                updatedAt = state.updatedAt,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(8.dp))

            if (state.dapps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.market_empty_title),
                        description = stringResource(R.string.market_empty_desc),
                        action = {
                            TextButton(onClick = viewModel::refresh) {
                                Text(stringResource(R.string.action_retry))
                            }
                        },
                    )
                }
            } else {
                // 自适应列数：手机 1 列，折叠屏展开 2 列，平板 3 列 —— 不需要写断点
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 320.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.market_dapps_count, state.dapps.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.dapps, key = { it.id }) { dapp ->
                        DappCard(
                            dapp = dapp,
                            chainNames = state.chainNames,
                            onOpen = { Browser.open(context, dapp.url) },
                        )
                    }
                }
            }
        }
    }
}

/** 分类筛选行：横向可滚动，**常驻顶部**，不随列表滚动隐藏。 */
@Composable
private fun CategoryRow(
    categories: List<MarketCategory>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = {
                Text(
                    text = stringResource(R.string.market_category_all),
                    style = MaterialTheme.typography.labelMedium,
                )
            },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selected == category.id,
                onClick = { onSelect(category.id) },
                label = {
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Composable
private fun StatusLine(
    builtin: Boolean,
    updatedAt: String?,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (builtin) {
            stringResource(R.string.market_offline)
        } else {
            stringResource(R.string.market_online, updatedAt ?: "—")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}

@Composable
private fun DappCard(
    dapp: MarketDapp,
    chainNames: Map<String, String>,
    onOpen: () -> Unit,
) {
    LotusCard(onClick = onOpen) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AddressAvatar(seed = dapp.id, label = dapp.name.take(2), size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dapp.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (dapp.reviewed) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Outlined.Verified,
                                contentDescription = stringResource(R.string.market_verified),
                                tint = LotusTheme.semantic.verified,
                                modifier = Modifier.height(16.dp),
                            )
                        }
                    }
                    Text(
                        text = dapp.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.market_open),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            dapp.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dapp.featured) {
                    LotusChip(
                        text = stringResource(R.string.market_featured),
                        containerColor = LotusTheme.semantic.warningContainer,
                        contentColor = LotusTheme.semantic.onWarningContainer,
                    )
                }
                LotusChip(text = dapp.category)
                if (dapp.chains.isNotEmpty()) {
                    LotusChip(
                        text = stringResource(
                            R.string.market_chain_support,
                            dapp.chains.mapNotNull { chainNames[it] ?: it }.take(3).joinToString("/"),
                        ),
                    )
                }
            }
        }
    }
}
