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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AddCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CappedLazyColumn
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.vault.CredentialValueSanitizer
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultReferenceSync
import me.rerere.rikkahub.data.vault.CredentialMeta
import me.rerere.rikkahub.data.vault.CredentialType
import me.rerere.rikkahub.data.vault.SecretGenerator
import me.rerere.rikkahub.data.vault.VaultBiometric
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.SshKeyPairDialog
import me.rerere.rikkahub.ui.components.ui.Select
import org.koin.compose.koinInject

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

    Scaffold(
        topBar = {
            TopAppBar(
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
            )
        },
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
                val query = searchQuery.trim().lowercase()
                val filtered = entries.filter {
                    (query.isEmpty() ||
                        it.name.lowercase().contains(query) ||
                        it.description.lowercase().contains(query) ||
                        it.grp.lowercase().contains(query)) &&
                        (typeFilter.isEmpty() || it.type == typeFilter)
                }
                // 排序函数：平铺时全局排，按分组时组内排
                fun sortedByOrder(list: List<VaultCredentialEntity>): List<VaultCredentialEntity> =
                    when (sortOrder) {
                        "created" -> list.sortedWith(
                            compareByDescending<VaultCredentialEntity> { it.createdAt }.thenBy { it.name.lowercase() },
                        )
                        "name" -> list.sortedBy { it.name.lowercase() }
                        else -> list.sortedWith(
                            compareByDescending<VaultCredentialEntity> { it.updatedAt }.thenBy { it.name.lowercase() },
                        )
                    }
                if (groupedView) {
                    // 按分组分块：组间按本地化组名字母序（“好找”优先），组内按 sortOrder
                    val grouped = filtered.groupBy { it.grp }
                    val groupLabels = grouped.keys.associateWith { vaultGroupLabel(ctx, it) }
                    val orderedGroups =
                        grouped.keys.sortedBy { g -> groupLabels[g]?.lowercase() ?: g.lowercase() }
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
                        sortedByOrder(grouped[group]!!).forEach { entry ->
                            item(key = entry.id) {
                                CredentialRow(
                                    entry = entry,
                                    revealed = entry.name in revealedNames,
                                    onRevealToggle = { toggleReveal(entry) },
                                    onEdit = { showEditor = EditorMode.Edit(entry) },
                                    onDelete = { deleteTarget = entry },
                                )
                            }
                        }
                    }
                } else {
                    // 平铺：全库按 sortOrder 连排（默认最近更新在前），行内带组名小标
                    sortedByOrder(filtered).forEach { entry ->
                        item(key = entry.id) {
                            CredentialRow(
                                entry = entry,
                                revealed = entry.name in revealedNames,
                                onRevealToggle = { toggleReveal(entry) },
                                onEdit = { showEditor = EditorMode.Edit(entry) },
                                onDelete = { deleteTarget = entry },
                            groupLabel = vaultGroupLabel(ctx, entry.grp),
                            )
                        }
                    }
                }
            }
        }
    }

    // 新增/编辑 BottomSheet 弹窗
    val context = androidx.compose.ui.platform.LocalContext.current
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
                            repository.save(
                                name = name,
                                value = value,
                                description = description,
                                group = group,
                                publicKey = publicKey,
                                type = type,
                                metaJson = metaJson,
                            )
                            runCatching {
                                VaultReferenceSync.renameEverywhere(
                                    settingsStore, sshHostRepository, oldName, name,
                                )
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
                        // 校验类失败（命名规范/值不可见）：提示并留在编辑器，不崩（2026-09-23 闪退修复）
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
                                    masked + "　·　fp " + CredentialVaultRepository.fingerprint(v).take(8)
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

/** 编辑器模式：新建 or 编辑已有 */
sealed class EditorMode {
    data class Create(val initialName: String = "") : EditorMode()
    data class Edit(val entry: VaultCredentialEntity) : EditorMode()
}

/** 单条凭证行：名称 + 描述 + 脱敏/明文切换 + 编辑/删除 */
@Composable
private fun CredentialRow(
    entry: VaultCredentialEntity,
    revealed: Boolean,
    onRevealToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** 平铺视图下在名称旁显示的组名（按分组视图下不传）。 */
    groupLabel: String? = null,
) {
    val repository: CredentialVaultRepository = koinInject()
    val vaultPreferences: VaultPreferences = koinInject()
    val biometricBuffer: BiometricResultBuffer = koinInject()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var plaintext by remember(entry.id) { mutableStateOf<String?>(null) }
    var biometricEnabled by remember { mutableStateOf(true) }

    // 读取指纹开关
    LaunchedEffect(Unit) {
        vaultPreferences.biometricEnabled.collect { biometricEnabled = it }
    }

    // 需要显示明文时才解密（内存，用完即弃）
    if (revealed && plaintext == null) {
        plaintext = repository.decryptValue(entry)
    }

    // 展开前弹指纹门禁（开关开启时）；验证通过才真正展开
    fun requestReveal() {
        if (!biometricEnabled) {
            onRevealToggle()
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            val ok = VaultBiometric.authenticate(
                context = appContext,
                buffer = biometricBuffer,
                title = context.getString(R.string.vault_biometric_view_title),
                subtitle = entry.name,
            )
            if (ok) {
                plaintext = null // 强制重新解密
                onRevealToggle()
                scope.launch { repository.logAccess(entry.name, "manual", "view") }
            }
        }
    }

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    if (!groupLabel.isNullOrBlank()) {
                        Text(
                            text = groupLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (entry.description.isNotBlank()) {
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // SSH 公钥（明文，公开信息，直接展示；可长按复制）
                if (entry.publicKey.isNotBlank()) {
                    Text(
                        entry.publicKey,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(entry.publicKey))
                                android.widget.Toast.makeText(context, context.getString(R.string.vault_pubkey_copied), android.widget.Toast.LENGTH_SHORT).show()
                            },
                    )
                }
                Text(
                    if (revealed && plaintext != null) plaintext!! else CredentialVaultRepository.mask(entry.valueLength.takeIf { it > 0 }?.let { "x".repeat(it) } ?: "******"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (revealed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { requestReveal() }) {
                Icon(if (revealed) HugeIcons.ViewOff else HugeIcons.View, if (revealed) stringResource(R.string.vault_hide) else stringResource(R.string.vault_show))
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Edit02, stringResource(R.string.vault_editor_value_label))
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete02, stringResource(R.string.vault_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 新增/编辑对话框 */
@Composable
private fun CredentialEditorDialog(
    mode: EditorMode,
    existingGroups: List<String>,
    onDismiss: () -> Unit,
    onSave: (oldName: String?, name: String, value: String, description: String, group: String, publicKey: String, type: String, metaJson: String) -> Unit,
) {
    val repository: CredentialVaultRepository = koinInject()
    var name by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.name ?: (mode as? EditorMode.Create)?.initialName ?: "") }
    var value by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.let { repository.decryptValue(it) } ?: "") }
    var description by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.description ?: "") }
    var publicKey by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.publicKey ?: "") }
    var group by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.grp ?: "Other") }
    var type by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.type ?: "") }
    // 非敏感元数据（明文，键白名单见 CredentialMeta）：编辑时回填，按类型显示对应字段
    var meta by remember {
        mutableStateOf(CredentialMeta.decode((mode as? EditorMode.Edit)?.entry?.metaJson ?: ""))
    }
    var showGroupInput by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var valueError by remember { mutableStateOf(false) }

    val isEdit = mode is EditorMode.Edit

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) stringResource(R.string.vault_editor_value_edit_label) else stringResource(R.string.vault_new)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.uppercase().replace(Regex("[^A-Z0-9_ ]"), "_").replace(" ", "_")
                        nameError = false
                    },
                    label = { Text(stringResource(R.string.vault_editor_name_label)) },
                    singleLine = true,
                    isError = nameError,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; valueError = false },
                    label = { Text(if (isEdit) stringResource(R.string.vault_editor_value_edit_label) else stringResource(R.string.vault_editor_value_label)) },
                    singleLine = false,
                    isError = valueError,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 随机初值：生成后直接在输入框可见、可复制，只以密文入库。
                // 已经存在的凭证也可重新生成（用于轮换密钥）。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { value = SecretGenerator.token(); valueError = false },
                    ) { Text(stringResource(R.string.vault_generate_token)) }
                    OutlinedButton(
                        onClick = { value = SecretGenerator.password(); valueError = false },
                    ) { Text(stringResource(R.string.vault_generate_password)) }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.vault_editor_desc_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = publicKey,
                    onValueChange = { publicKey = it },
                    label = { Text(stringResource(R.string.vault_ssh_pubkey_label)) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 凭据类型：存储值是与通用交换格式对齐的英文标识，显示层本地化（值不变）。
                // "auto" = 保存时按名称与结构自动判断，避免要求用户先做分类。
                Select(
                    options = listOf("auto") + CredentialType.KNOWN.toList(),
                    selectedOption = type.ifEmpty { "auto" },
                    onOptionSelected = { type = if (it == "auto") "" else it },
                    optionToString = {
                        val typeCtx = androidx.compose.ui.platform.LocalContext.current
                        if (it == "auto") stringResource(R.string.vault_type_auto) else vaultTypeLabel(typeCtx, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 元数据字段：按类型显示（标签用语言中立的技术词，与类型标识同款约定，不进翻译资源）
                // 字段集按类型推荐（键名保持英文，与工具/落库一致；标签本地化见 vaultMetaLabel）
                val metaCtx = androidx.compose.ui.platform.LocalContext.current
                val metaFieldKeys = when (type) {
                    CredentialType.API_KEY -> listOf("endpoint", "header", "prefix", "account", "access_key_id", "region")
                    CredentialType.BASIC_AUTH -> listOf("username", "account")
                    CredentialType.TOTP -> listOf("algorithm", "digits", "period")
                    CredentialType.CLOUD_AK -> listOf("access_key_id", "account", "region", "project_id")
                    else -> listOf("account", "access_key_id", "user_id", "domain_id", "project_id", "region")
                }
                metaFieldKeys.forEach { key ->
                    OutlinedTextField(
                        value = meta[key].orEmpty(),
                        onValueChange = { v -> meta = if (v.isBlank()) meta - key else meta + (key to v) },
                        label = { Text(vaultMetaLabel(metaCtx, key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 自定义字段（custom-fields）：多组 k=v，键走 custom. 前缀白名单放行；值为明文备注（方案 A）
                if (type == CredentialType.CUSTOM) {
                    val customKeys = meta.keys.filter { it.startsWith(CredentialMeta.CUSTOM_PREFIX) }
                    customKeys.forEach { fullKey ->
                        val label = fullKey.removePrefix(CredentialMeta.CUSTOM_PREFIX)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = label,
                                onValueChange = { newLabel ->
                                    val newFull = CredentialMeta.CUSTOM_PREFIX + newLabel
                                    // 保行位重建键（直接删加会把行挪到末尾，输入时会跳动）
                                    meta = buildMap {
                                        meta.forEach { (k, v) -> if (k == fullKey) put(newFull, v) else put(k, v) }
                                    }
                                },
                                label = { Text("key") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = meta[fullKey].orEmpty(),
                                onValueChange = { v -> meta = if (v.isBlank()) meta - fullKey else meta + (fullKey to v) },
                                label = { Text("value") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { meta = meta - fullKey }) {
                                Icon(HugeIcons.Delete02, stringResource(R.string.vault_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    TextButton(onClick = {
                        var i = customKeys.size + 1
                        var k = CredentialMeta.CUSTOM_PREFIX + "key$i"
                        while (k in meta) { i++; k = CredentialMeta.CUSTOM_PREFIX + "key$i" }
                        meta = meta + (k to "")
                    }) { Text(stringResource(R.string.vault_editor_custom_add)) }
                    Text(
                        stringResource(R.string.vault_editor_custom_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showGroupInput) {
                    OutlinedTextField(
                        value = group,
                        onValueChange = { group = it },
                        label = { Text(stringResource(R.string.vault_editor_new_group_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // 下拉选择已有分组 + 「新建分组…」入口
                    val newGroupOption = stringResource(R.string.vault_new_group_option)
                    Select(
                        options = existingGroups + newGroupOption,
                        selectedOption = group,
                        onOptionSelected = {
                            if (it == newGroupOption) {
                                showGroupInput = true
                                group = ""
                            } else {
                                group = it
                            }
                        },
                        optionToString = {
                            if (it == newGroupOption) it
                            else vaultGroupLabel(androidx.compose.ui.platform.LocalContext.current, it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
        onClick = {
            if (name.isBlank()) { nameError = true; return@Button }
            if (!isEdit && value.isBlank()) { valueError = true; return@Button }
            // 只含不可见字符的值等于空密钥：与 save/importEntries 同语义，前置拦截
            if (value.isNotBlank() && CredentialValueSanitizer.sanitize(value).isEmpty()) {
                valueError = true; return@Button
            }
            // 编辑模式：value 留空 = 保留原值（在 onSave 里处理）；改名传旧名
            val oldName = (mode as? EditorMode.Edit)?.entry?.name
            // 新建/改名走命名规范化（与 SshKeyPairDialog 同语义，自动转大写蛇形）；名字未改则保留存量原名
            if (oldName != name) {
                name = CredentialVaultRepository.normalizeName(name)
            }
            onSave(oldName, name, value, description, group, publicKey, type, CredentialMeta.encode(meta))
        },
            ) { Text(stringResource(R.string.vault_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_cancel)) } },
    )
}

/** 分组显示名：存储值（英文 id）→ 本地化标签；未知分组回退原值。（普通函数：便于在排序 lambda 里调用） */
private fun vaultGroupLabel(context: android.content.Context, grp: String): String =
    when (grp) {
        "Git" -> context.getString(R.string.vault_group_git)
        "AI" -> context.getString(R.string.vault_group_ai)
        "ECS" -> context.getString(R.string.vault_group_ecs)
        "SSH" -> context.getString(R.string.vault_group_ssh)
        "Network" -> context.getString(R.string.vault_group_network)
        "MCP" -> context.getString(R.string.vault_group_mcp)
        "Backend" -> context.getString(R.string.vault_group_backend)
        "System" -> context.getString(R.string.vault_group_system)
        "Notification" -> context.getString(R.string.vault_group_notification)
        "Other" -> context.getString(R.string.vault_group_other)
        else -> grp
    }

/** 类型显示名：存储值（与交换格式对齐的英文标识）→ 本地化标签；未知值回退原值。 */
private fun vaultTypeLabel(context: android.content.Context, type: String): String =
    when (type) {
        CredentialType.SSH_KEY -> context.getString(R.string.vault_type_ssh_key)
        CredentialType.API_KEY -> context.getString(R.string.vault_type_api_key)
        CredentialType.BASIC_AUTH -> context.getString(R.string.vault_type_basic_auth)
        CredentialType.TOTP -> context.getString(R.string.vault_type_totp)
        CredentialType.CUSTOM -> context.getString(R.string.vault_type_custom_fields)
        CredentialType.CLOUD_AK -> context.getString(R.string.vault_type_cloud_ak)
        else -> type
    }

/** 元数据字段的显示名：键名保持英文（与工具/落库一致），界面显示本地化标签；未知键回退原值。 */
private fun vaultMetaLabel(context: android.content.Context, key: String): String =
    when (key) {
        "endpoint" -> context.getString(R.string.vault_meta_endpoint)
        "path" -> context.getString(R.string.vault_meta_path)
        "header" -> context.getString(R.string.vault_meta_header)
        "prefix" -> context.getString(R.string.vault_meta_prefix)
        "username" -> context.getString(R.string.vault_meta_username)
        "account" -> context.getString(R.string.vault_meta_account)
        "access_key_id" -> context.getString(R.string.vault_meta_access_key_id)
        "user_id" -> context.getString(R.string.vault_meta_user_id)
        "domain_id" -> context.getString(R.string.vault_meta_domain_id)
        "project_id" -> context.getString(R.string.vault_meta_project_id)
        "region" -> context.getString(R.string.vault_meta_region)
        "algorithm" -> context.getString(R.string.vault_meta_algorithm)
        "digits" -> context.getString(R.string.vault_meta_digits)
        "period" -> context.getString(R.string.vault_meta_period)
        else -> key
    }
