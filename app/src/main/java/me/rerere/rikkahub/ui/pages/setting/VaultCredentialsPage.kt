package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import me.rerere.rikkahub.ui.theme.CustomColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AddCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CappedLazyColumn
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import androidx.compose.ui.platform.LocalResources
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultReferenceSync
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.SshKeyPairDialog
import me.rerere.rikkahub.ui.components.vault.vaultGroupLabel
import me.rerere.rikkahub.ui.components.vault.vaultTypeLabel
import org.koin.compose.koinInject

/** 按排序键给凭证列表排序（"created" 最近添加、"name" 名称、默认 "updated" 最近更新）。 */
private fun sortVaultEntries(list: List<VaultCredentialEntity>, sortOrder: String): List<VaultCredentialEntity> =
    when (sortOrder) {
        "created" -> list.sortedWith(
            compareByDescending<VaultCredentialEntity> { it.createdAt }.thenBy { it.name.lowercase() },
        )
        "name" -> list.sortedBy { it.name.lowercase() }
        else -> list.sortedWith(
            compareByDescending<VaultCredentialEntity> { it.updatedAt }.thenBy { it.name.lowercase() },
        )
    }

/**
 * 密钥列表（三级页）：分组展示 + 小眼睛显隐 + 新增/编辑/删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultCredentialsPage() {
    val repository: CredentialVaultRepository = koinInject()
    // 改名需要同步配置引用（与工具路径行为一致），因此拿到这两处依赖
    val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = koinInject()
    val sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository = koinInject()
    val telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences = koinInject()
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<VaultCredentialEntity>>(emptyList()) }
    var revealedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showEditor by remember { mutableStateOf<EditorMode?>(null) }
    var deleteTarget by remember { mutableStateOf<VaultCredentialEntity?>(null) }
    var showKeyGen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("") }
    // 视图：默认「平铺」（全库按排序连排，条目多时好找）；可切「按分组」分块浏览
    var groupedView by remember { mutableStateOf(false) }
    // 「按分组」视图下被收起的分组（只影响展示，不持久化）
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    // 排序：默认「最近更新」（凭证多时按时间比按名称常找）；可切「最近添加 / 名称」
    var sortOrder by remember { mutableStateOf("updated") }
    // 重复检测（按值指纹精确判定；只在需要时查，避免每次进页面都全库解密）
    var duplicateGroups by remember { mutableStateOf<List<List<String>>?>(null) }

    suspend fun refresh() {
        entries = repository.getAll()
    }

    LaunchedEffect(Unit) { refresh() }

    fun toggleReveal(entry: VaultCredentialEntity) {
        val name = entry.name
        revealedNames =
            if (name in revealedNames) revealedNames - name
            else revealedNames + name
    }

    duplicateGroups?.let { groups ->
        AlertDialog(
            onDismissRequest = { duplicateGroups = null },
            title = { Text(stringResource(R.string.vault_dupes_title)) },
            text = {
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.vault_dupes_empty))
                } else {
                    CappedLazyColumn {
                        items(groups.size, key = { it }) { index ->
                            Text(
                                text = groups[index].joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { duplicateGroups = null }) {
                    Text(stringResource(R.string.vault_cancel))
                }
            },
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.vault_list_title, entries.size)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showKeyGen = true }) {
                        Icon(HugeIcons.Key01, stringResource(R.string.vault_new_key))
                    }
                    IconButton(
                        onClick = {
                            scope.launch { duplicateGroups = repository.findDuplicateGroups() }
                        },
                    ) {
                        Icon(HugeIcons.Copy01, stringResource(R.string.vault_dupes_title))
                    }
                    IconButton(onClick = { showEditor = EditorMode.Create() }) {
                        Icon(HugeIcons.AddCircle, stringResource(R.string.vault_new))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(HugeIcons.Key01, null, modifier = Modifier.padding(8.dp))
                Text(stringResource(R.string.vault_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.vault_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { showEditor = EditorMode.Create() }) { Text(stringResource(R.string.vault_new)) }
            }
        } else {
            // 取 Context 须在 @Composable 作用域内：LazyColumn 的 content lambda 不是 @Composable。
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // 搜索过滤＋排序＋分组只在输入/条目变化时重算：原来在 LazyColumn content 里直接算，
            // 每敲一字都全量过滤＋排序＋重分组并重组整个列表（卡顿主因）。content lambda 不是
            // @Composable，不能在里面 remember，所以把派生列表提到这里。
            val query = searchQuery.trim().lowercase()
            val filtered = remember(entries, query, typeFilter) {
                entries.filter {
                    (query.isEmpty() ||
                        it.name.lowercase().contains(query) ||
                        it.description.lowercase().contains(query) ||
                        it.grp.lowercase().contains(query)) &&
                        (typeFilter.isEmpty() || it.type == typeFilter)
                }
            }
            val grouped = remember(filtered) { filtered.groupBy { it.grp } }
            val groupLabels = remember(grouped) { grouped.keys.associateWith { vaultGroupLabel(ctx, it) } }
            val orderedGroups = remember(grouped, groupLabels) {
                grouped.keys.sortedBy { g -> groupLabels[g]?.lowercase() ?: g.lowercase() }
            }
            val sortedGroups = remember(grouped, sortOrder) {
                grouped.mapValues { (_, list) -> sortVaultEntries(list, sortOrder) }
            }
            val flatList = remember(filtered, sortOrder) { sortVaultEntries(filtered, sortOrder) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 搜索框（名称/描述/分组过滤）
                item(key = "search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.vault_search_hint)) },
                        leadingIcon = { Icon(HugeIcons.Search01, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) { Icon(HugeIcons.Cancel01, null) }
                            }
                        },
                        singleLine = true,
                    )
                }

                // 类型筛选：条目多时按类型快速定位（存储值仍是英文标识，仅显示层本地化）
                val typeOptions = entries.map { it.type }.filter { it.isNotBlank() }.distinct().sorted()
                if (typeOptions.isNotEmpty()) {
                    item(key = "type_filter") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            FilterChip(
                                selected = typeFilter.isEmpty(),
                                onClick = { typeFilter = "" },
                                label = { Text(stringResource(R.string.vault_type_filter_all)) },
                            )
                            typeOptions.forEach { t ->
                                FilterChip(
                                    selected = typeFilter == t,
                                    onClick = { typeFilter = t },
                                    label = { Text(vaultTypeLabel(ctx, t)) },
                                )
                            }
                        }
                    }
                }

                // 排序选择：与类型筛选同风格（chips），默认为「最近更新」
                item(key = "sort") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        listOf(
                            "updated" to R.string.vault_sort_updated,
                            "created" to R.string.vault_sort_created,
                            "name" to R.string.vault_sort_name,
                        ).forEach { (key, res) ->
                            FilterChip(
                                selected = sortOrder == key,
                                onClick = { sortOrder = key },
                                label = { Text(stringResource(res)) },
                            )
                        }
                    }
                }

                // 视图切换：平铺（默认）/ 按分组
                item(key = "view") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = !groupedView,
                            onClick = { groupedView = false },
                            label = { Text(stringResource(R.string.vault_view_flat)) },
                        )
                        FilterChip(
                            selected = groupedView,
                            onClick = { groupedView = true },
                            label = { Text(stringResource(R.string.vault_view_grouped)) },
                        )
                    }
                }

                // 按组展示：组间按组名字母序（好找） + 组内排序 + 搜索过滤
                // 过滤/排序/分组已在 LazyColumn 外用 remember 算好（见上），这里只读结果。
                if (groupedView) {
                    orderedGroups.forEach { group ->
                        val collapsed = group in collapsedGroups
                        item(key = "group_$group") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            collapsedGroups =
                                                if (collapsed) collapsedGroups - group else collapsedGroups + group
                                        }
                                        .padding(top = 8.dp, bottom = 4.dp),
                            ) {
                                Icon(
                                    imageVector = if (collapsed) HugeIcons.ArrowRight01 else HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    vaultGroupLabel(ctx, group) + " (${grouped[group]!!.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (collapsed) return@forEach
                        val groupList = sortedGroups[group]!!
                        groupList.forEachIndexed { idx, entry ->
                            item(key = entry.id) {
                                CredentialRow(
                                    entry = entry,
                                    revealed = entry.name in revealedNames,
                                    onRevealToggle = { toggleReveal(entry) },
                                    onEdit = { showEditor = EditorMode.Edit(entry) },
                                    onDelete = { deleteTarget = entry },
                                    shape = cardGroupItemShape(idx, groupList.size),
                                )
                            }
                        }
                    }
                } else {
                    // 平铺：全库按 sortOrder 连排（默认最近更新在前），行内带组名小标
                    flatList.forEachIndexed { idx, entry ->
                        item(key = entry.id) {
                            CredentialRow(
                                entry = entry,
                                revealed = entry.name in revealedNames,
                                onRevealToggle = { toggleReveal(entry) },
                                onEdit = { showEditor = EditorMode.Edit(entry) },
                                onDelete = { deleteTarget = entry },
                                groupLabel = vaultGroupLabel(ctx, entry.grp),
                                shape = cardGroupItemShape(idx, flatList.size),
                            )
                        }
                    }
                }
            }
        }
    }

    // 新增/编辑 BottomSheet 弹窗
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = LocalResources.current
    showEditor?.let { mode ->
        CredentialEditorDialog(
            mode = mode,
            existingGroups = (entries.map { it.grp } + "Other").distinct().sorted(),
            onDismiss = { showEditor = null },
            onSave = { oldName, name, value, description, group, publicKey, type, metaJson ->
                scope.launch {
                    try {
                        if (oldName != null && oldName != name) {
                            // 改名：先建新名（沿用编辑框里的值）→ 同步配置引用 → 再删旧名
                            // （同步引用是必须的：配置里按名字引用，漏掉就会静默失效）
                            if (repository.getByName(name) != null) {
                                android.widget.Toast.makeText(
                                    context,
                                    resources.getString(R.string.vault_rename_target_exists),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                return@launch
                            }
                            repository.save(
                                name = name,
                                value = value,
                                description = description,
                                group = group,
                                publicKey = publicKey,
                                type = type,
                                metaJson = metaJson,
                            )
                            val syncResult =
                                runCatching {
                                    VaultReferenceSync.renameEverywhere(
                                        settingsStore, sshHostRepository, telegramBotPreferences, oldName, name,
                                    )
                                }
                            val syncError = syncResult.exceptionOrNull()
                            if (syncError != null) {
                                if (syncError is kotlinx.coroutines.CancellationException) throw syncError
                                android.widget.Toast.makeText(
                                    context,
                                    resources.getString(
                                        R.string.vault_rename_sync_failed,
                                        syncError.message?.let { " ($it)" }.orEmpty(),
                                    ),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                return@launch
                            }
                            repository.getByName(oldName)?.let { repository.delete(it) }
                            repository.logAccess(oldName, "manual", "rename_from")
                            repository.logAccess(name, "manual", "rename_to")
                        } else {
                            repository.save(
                                name = name,
                                value = value,
                                description = description,
                                group = group,
                                publicKey = publicKey,
                                type = type,
                                metaJson = metaJson,
                            )
                        }
                        showEditor = null
                        refresh()
                    } catch (e: IllegalArgumentException) {
                        // 校验类失败（命名规范/值不可见）：提示并留在编辑器，不崩（闪退修复）
                        android.widget.Toast.makeText(context, e.message, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    // 删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.vault_delete_confirm_title, target.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.vault_delete_confirm_text))
                    // 值预览：掩码 + 指纹（不是明文），用于确认删的是哪一条
                    val preview =
                        remember(target) {
                            runCatching {
                                repository.decryptValue(target)?.let { v ->
                                    val masked = if (v.length <= 8) "****" else v.take(4) + "…" + v.takeLast(4)
                                    masked + "　·　fp " + repository.fingerprint(v).take(8)
                                }
                            }.getOrNull()
                        }
                    if (preview != null) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            repository.delete(target)
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.vault_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.vault_cancel)) } },
        )
    }

    // 生成 SSH 密钥对（私钥存凭证库 + 显示公钥）
    if (showKeyGen) {
        SshKeyPairDialog(
            credentialName = "",
            defaultGroup = "SSH",
            onDismiss = { showKeyGen = false },
            onSaved = { _ ->
                showKeyGen = false
                scope.launch { refresh() }
            },
        )
    }
}

