package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Key01
import androidx.compose.ui.graphics.Shape
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.vault.CredentialValueSanitizer
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.SshKeyGenerator
import me.rerere.rikkahub.data.vault.CredentialMeta
import me.rerere.rikkahub.data.vault.CredentialType
import me.rerere.rikkahub.data.vault.SecretGenerator
import me.rerere.rikkahub.data.vault.VaultBiometric
import me.rerere.rikkahub.data.vault.VaultKeyHealth
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.ui.components.vault.vaultGroupLabel
import me.rerere.rikkahub.ui.components.vault.vaultTypeLabel
import me.rerere.rikkahub.ui.components.ui.Select
import org.koin.compose.koinInject

/** 编辑器模式：新建 or 编辑已有 */
sealed class EditorMode {
    data class Create(val initialName: String = "") : EditorMode()
    data class Edit(val entry: VaultCredentialEntity) : EditorMode()
}

/** 单条凭证行：名称 + 描述 + 脱敏/明文切换 + 编辑/删除 */
@Composable
internal fun CredentialRow(
    entry: VaultCredentialEntity,
    revealed: Boolean,
    onRevealToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** 平铺视图下在名称旁显示的组名（按分组视图下不传）。 */
    groupLabel: String? = null,
    shape: Shape = cardGroupItemShape(0, 1),
) {
    val repository: CredentialVaultRepository = koinInject()
    val vaultPreferences: VaultPreferences = koinInject()
    val biometricBuffer: BiometricResultBuffer = koinInject()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // 资源取值走 LocalResources（configuration-aware）：直接 LocalContext.current.getString 会被 lint 的
    // LocalContextGetResourceValueCall 判为「配置变化后可能返回过期值」（本项目其余页面同此写法）。
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var plaintext by remember(entry.id) { mutableStateOf<String?>(null) }
    var biometricEnabled by remember { mutableStateOf(true) }
    // 导出私钥：弹窗确认后由系统文件选择器选定落地位置
    var exportDialog by remember { mutableStateOf(false) }
    var exportContent by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberSafeLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file")
    ) { uri ->
        val content = exportContent
        exportContent = null
        if (uri != null && content != null) {
            scope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } ?: error("openOutputStream returned null")
                    }
                }.isSuccess
                android.widget.Toast.makeText(
                    context,
                    resources.getString(if (ok) R.string.vault_keyexport_done else R.string.vault_keyexport_failed),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

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
                title = resStr(resources, R.string.vault_biometric_view_title),
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
        shape = shape,
        color = CustomColors.cardColors.containerColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardGroupIcon(
                icon = HugeIcons.Key01,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
                iconSize = 18.dp,
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!groupLabel.isNullOrBlank()) {
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = groupLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (entry.type.isNotBlank()) {
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = vaultTypeLabel(context, entry.type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
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
                                android.widget.Toast.makeText(context, resStr(resources, R.string.vault_pubkey_copied), android.widget.Toast.LENGTH_SHORT).show()
                            },
                    )
                    // 体检入参的私钥 = 已解密到内存的明文（仅在点开小眼睛后可用）；
                    // 未点开时传 null，此时只报公钥侧的形态/结构问题。
                    // 类型不是 SSH 的条目即使带了公钥字段也不送私钥——否则会把别的凭据
                    // 值当成"解析不出的私钥"报出来。
                    val privateKeyForHealth = plaintext.takeIf { entry.type == CredentialType.SSH_KEY }
                    val healthIssues = remember(entry.publicKey, privateKeyForHealth) {
                        VaultKeyHealth.check(entry.publicKey, privateKeyForHealth)
                    }
                    VaultKeyHealthNotes(healthIssues)
                }
                Text(
                    if (revealed && plaintext != null) plaintext!! else CredentialVaultRepository.mask(entry.valueLength.takeIf { it > 0 }?.let { "x".repeat(it) } ?: "******"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (revealed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.type == CredentialType.SSH_KEY) {
                IconButton(onClick = { exportDialog = true }) {
                    Icon(HugeIcons.Download01, stringResource(R.string.vault_keyexport_action))
                }
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

    if (exportDialog) {
        VaultKeyExportDialog(
            entryName = entry.name,
            publicKeyLine = entry.publicKey,
            privateKeyPem = repository.decryptValue(entry).orEmpty(),
            onDismiss = { exportDialog = false },
            onExport = { content, fileName ->
                exportContent = content
                exportDialog = false
                exportLauncher.launch(fileName)
            },
        )
    }
}

/**
 * [VaultKeyHealth] 结论的展示：ERROR 用 error 色，WARN 用次级色，无问题不渲染。
 *
 * 只按 [VaultKeyHealth.Issue.code] 取资源文案 —— `Issue.detail` 是排查用的自由文本，
 * 不进界面（否则绕过文案资源化）。
 */
@Composable
internal fun VaultKeyHealthNotes(issues: List<VaultKeyHealth.Issue>) {
    if (issues.isEmpty()) return
    // 同一 code 只留一条，取该 code 下最严重的等级：PUBLIC_FORMAT 可能同时给出
    // 「多余填充」(WARN) 与「无法解码」(ERROR)，后者已经涵盖前者。
    val severityByCode = issues.groupBy { it.code }.map { (code, group) ->
        code to if (group.any { it.severity == VaultKeyHealth.Severity.ERROR }) {
            VaultKeyHealth.Severity.ERROR
        } else {
            VaultKeyHealth.Severity.WARN
        }
    }.toMap()
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Text(
            stringResource(R.string.vault_keyhealth_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (code in VaultKeyHealth.Code.entries) {
            val severity = severityByCode[code] ?: continue
            Text(
                stringResource(vaultKeyHealthIssueRes(code)),
                style = MaterialTheme.typography.bodySmall,
                color = if (severity == VaultKeyHealth.Severity.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 体检结论 -> 资源文案（按 [VaultKeyHealth.Code] 映射，`Issue.detail` 不进界面）。
 * 普通函数返回资源 id：便于在点击回调里先把 code 存进状态，渲染时再取文案。
 */
private fun vaultKeyHealthIssueRes(code: VaultKeyHealth.Code): Int =
    when (code) {
        VaultKeyHealth.Code.PRIVATE_UNPARSEABLE -> R.string.vault_keyhealth_private_unparseable
        VaultKeyHealth.Code.PUBLIC_FORMAT -> R.string.vault_keyhealth_public_format
        VaultKeyHealth.Code.PUBLIC_STRUCTURE -> R.string.vault_keyhealth_public_structure
        VaultKeyHealth.Code.KEY_MISMATCH -> R.string.vault_keyhealth_key_mismatch
    }

/**
 * 聚焦时把字段滚进可视范围（键盘弹出后不被遮挡）。
 */
@Composable
private fun rememberBringIntoViewModifier(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return Modifier
        .bringIntoViewRequester(requester)
        .onFocusChanged { state ->
            if (state.isFocused) scope.launch { requester.bringIntoView() }
        }
}

/** 新增/编辑对话框 */
@Composable
internal fun CredentialEditorDialog(
    mode: EditorMode,
    existingGroups: List<String>,
    onDismiss: () -> Unit,
    onSave: (oldName: String?, name: String, value: String, description: String, group: String, publicKey: String, type: String, metaJson: String) -> Unit,
) {
    val repository: CredentialVaultRepository = koinInject()
    var name by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.name ?: (mode as? EditorMode.Create)?.initialName ?: "") }
    // 值与公钥是多行文本（私钥几十行、公钥一行但很长），用 TextFieldState + TextArea：有界高度＋字段内滚动＋全屏编辑＋文件导入。
    // 旧版单行 String 状态配无上限 OutlinedTextField，粘贴一段就把弹窗撑爆、键盘还遮住底部。
    val valueState = rememberTextFieldState(
        initialText = (mode as? EditorMode.Edit)?.entry?.let { repository.decryptValue(it) } ?: "",
    )
    var description by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.description ?: "") }
    val publicKeyState = rememberTextFieldState(
        initialText = (mode as? EditorMode.Edit)?.entry?.publicKey ?: "",
    )
    var group by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.grp ?: "Other") }
    var type by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.type ?: "") }
    // 非敏感元数据（明文，键白名单见 CredentialMeta）：编辑时回填，按类型显示对应字段
    var meta by remember {
        mutableStateOf(CredentialMeta.decode((mode as? EditorMode.Edit)?.entry?.metaJson ?: ""))
    }
    var showGroupInput by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var valueError by remember { mutableStateOf(false) }
    // 公钥不合格时记住拦截原因（存 code，文案在渲染处取，避免在点击回调里读资源）
    var publicKeyError by remember { mutableStateOf<VaultKeyHealth.Code?>(null) }
    // 编辑后清除保存时立起的错误标记（TextFieldState 没有 onValueChange，用 snapshotFlow 跟踪文本变化）
    LaunchedEffect(valueState) {
        snapshotFlow { valueState.text }.drop(1).distinctUntilChanged().collect { valueError = false }
    }
    LaunchedEffect(publicKeyState) {
        snapshotFlow { publicKeyState.text }.drop(1).distinctUntilChanged().collect { publicKeyError = null }
    }

    val isEdit = mode is EditorMode.Edit

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) stringResource(R.string.vault_editor_value_edit_label) else stringResource(R.string.vault_new)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
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
                // 凭据类型：放在输入项之前 —— 后面的字段按类型收口（值提示 / 随机初值 / 公钥框 / 元数据字段集）
                // 存储值是与通用交换格式对齐的英文标识，显示层本地化（值不变）；auto = 保存时按名称与结构自动判断
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
                TextArea(
                    state = valueState,
                    label = if (isEdit) stringResource(R.string.vault_editor_value_edit_label) else stringResource(R.string.vault_editor_value_label),
                    minLines = 5,
                    maxLines = 10,
                    isError = valueError,
                    modifier = Modifier.then(rememberBringIntoViewModifier()),
                )
                // 随机初值：按类型给（未定类型两个都给；TOTP 种子 / SSH 私钥 / 云 SK 不给随机——随机值对它们没意义）。
                // 生成后直接在输入框可见、可复制，只以密文入库；已有凭证也可重生（轮换）。
                val showTokenGen =
                    type.isEmpty() || type == CredentialType.API_KEY || type == CredentialType.CUSTOM
                val showPasswordGen = type.isEmpty() || type == CredentialType.BASIC_AUTH
                if (showTokenGen || showPasswordGen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showTokenGen) {
                            OutlinedButton(
                                onClick = { valueState.setTextAndPlaceCursorAtEnd(SecretGenerator.token()); valueError = false },
                            ) { Text(stringResource(R.string.vault_generate_token)) }
                        }
                        if (showPasswordGen) {
                            OutlinedButton(
                                onClick = { valueState.setTextAndPlaceCursorAtEnd(SecretGenerator.password()); valueError = false },
                            ) { Text(stringResource(R.string.vault_generate_password)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.vault_editor_desc_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // SSH 公钥：只对 ssh-key 类型显示（以前无条件渲染 → 每种类型都冒一个公钥框，容易填错）
                if (type == CredentialType.SSH_KEY) {
                    val publicKeyErrorCode = publicKeyError
                    TextArea(
                        state = publicKeyState,
                        label = stringResource(R.string.vault_ssh_pubkey_label),
                        minLines = 2,
                        maxLines = 6,
                        isError = publicKeyErrorCode != null,
                        modifier = Modifier.then(rememberBringIntoViewModifier()),
                    )
                    if (publicKeyErrorCode != null) {
                        Text(
                            stringResource(
                                R.string.vault_keyhealth_save_error,
                                stringResource(vaultKeyHealthIssueRes(publicKeyErrorCode)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // 元数据字段：按类型推荐字段集 + **默认折叠**（表单别一屏全是输入框）。
                // 折叠标题带「已填 N 项」；展开后除推荐键外，还把「已有但不在推荐集」的键一并列出 —— 不隐藏存量值。
                // custom.* 备注（平台形态 / 控制台 / 主机…）也在这里增删改：任何类型都可编辑（原来只有 custom-fields 才有）
                val metaCtx = androidx.compose.ui.platform.LocalContext.current
                val recommendedKeys =
                    when (type) {
                        CredentialType.API_KEY -> listOf("endpoint", "header", "prefix", "account", "access_key_id", "region")
                        CredentialType.BASIC_AUTH -> listOf("username", "account")
                        CredentialType.TOTP -> listOf("algorithm", "digits", "period")
                        CredentialType.CLOUD_AK -> listOf("access_key_id", "account", "region", "project_id")
                        else -> listOf("account")
                    }
                val fixedKeys =
                    (recommendedKeys + meta.keys.filterNot { it.startsWith(CredentialMeta.CUSTOM_PREFIX) }).distinct()
                val customKeys = meta.keys.filter { it.startsWith(CredentialMeta.CUSTOM_PREFIX) }.sorted()
                var metaExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { metaExpanded = !metaExpanded }
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            stringResource(
                                R.string.vault_meta_section,
                                fixedKeys.count { !meta[it].isNullOrBlank() } + customKeys.size,
                            ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (metaExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (metaExpanded) {
                    fixedKeys.forEach { key ->
                        OutlinedTextField(
                            value = meta[key].orEmpty(),
                            onValueChange = { v -> meta = if (v.isBlank()) meta - key else meta + (key to v) },
                            label = { Text(vaultMetaLabel(metaCtx, key)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // 自定义备注（custom.*）：多组 k=v，键走 custom. 前缀白名单放行；值为明文备注
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
                                label = { Text(stringResource(R.string.vault_meta_custom_key)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = meta[fullKey].orEmpty(),
                                onValueChange = { v -> meta = if (v.isBlank()) meta - fullKey else meta + (fullKey to v) },
                                label = { Text(vaultMetaLabel(metaCtx, fullKey)) },
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
            val value = valueState.text.toString()
            val publicKey = publicKeyState.text.toString()
            if (name.isBlank()) { nameError = true; return@Button }
            if (!isEdit && value.isBlank()) { valueError = true; return@Button }
            // 只含不可见字符的值等于空密钥：与 save/importEntries 同语义，前置拦截
            if (value.isNotBlank() && CredentialValueSanitizer.sanitize(value).isEmpty()) {
                valueError = true; return@Button
            }
            // SSH 公钥前置校验：ERROR 拦住保存，WARN（写法非标准但仍可解析）放行。
            // 公钥为空 = 没有公钥可校验，跳过（允许保存不带公钥的条目）。
            // 编辑模式下 value 留空表示保留原值，对体检而言等同「没有私钥可比」→ 当 null 传，
            // 否则会把「留空」误判成坏私钥（PRIVATE_UNPARSEABLE）。
            if (type == CredentialType.SSH_KEY && publicKey.isNotBlank()) {
                val blocking = VaultKeyHealth
                    .check(publicKey, value.ifBlank { null })
                    .firstOrNull { it.severity == VaultKeyHealth.Severity.ERROR }
                publicKeyError = blocking?.code
                if (blocking != null) return@Button
            } else {
                publicKeyError = null
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

/** 类型显示名（与交换格式对齐的英文标识）→ 本地化标签：见 VaultLabels.kt 的 vaultTypeLabel。 */

/** 固定元数据键 → 本地化标签。 */
private val FIXED_META_LABELS: Map<String, Int> =
    mapOf(
        "endpoint" to R.string.vault_meta_endpoint,
        "path" to R.string.vault_meta_path,
        "header" to R.string.vault_meta_header,
        "prefix" to R.string.vault_meta_prefix,
        "username" to R.string.vault_meta_username,
        "account" to R.string.vault_meta_account,
        "access_key_id" to R.string.vault_meta_access_key_id,
        "user_id" to R.string.vault_meta_user_id,
        "domain_id" to R.string.vault_meta_domain_id,
        "project_id" to R.string.vault_meta_project_id,
        "region" to R.string.vault_meta_region,
        "algorithm" to R.string.vault_meta_algorithm,
        "digits" to R.string.vault_meta_digits,
        "period" to R.string.vault_meta_period,
    )

/** `custom.<标签>` 的本地化标签；不认识的回退「去掉前缀的键名」。 */
private val CUSTOM_META_LABELS: Map<String, Int> =
    mapOf(
        "kind" to R.string.vault_meta_custom_kind,
        "console" to R.string.vault_meta_custom_console,
        "docs" to R.string.vault_meta_custom_docs,
        "note" to R.string.vault_meta_custom_note,
        "host" to R.string.vault_meta_custom_host,
        "app_id" to R.string.vault_meta_custom_app_id,
        "account_id" to R.string.vault_meta_custom_account_id,
        "purpose" to R.string.vault_meta_custom_purpose,
        "alias" to R.string.vault_meta_custom_alias,
        "keystore_path" to R.string.vault_meta_custom_keystore_path,
        "dup_hint" to R.string.vault_meta_custom_dup_hint,
        "pub_archive" to R.string.vault_meta_custom_pub_archive,
    )

/** 元数据字段的显示名：键名保持英文（与工具/落库一致），界面显示本地化标签；未知键回退原值。 */
private fun vaultMetaLabel(context: android.content.Context, key: String): String =
    when {
        FIXED_META_LABELS.containsKey(key) -> context.getString(FIXED_META_LABELS.getValue(key))
        // custom.* 备注键：认识的给本地化名，不认识的回退「去掉前缀的键名」（别露出 custom. 前缀）
        key.startsWith(CredentialMeta.CUSTOM_PREFIX) -> {
            val inner = key.removePrefix(CredentialMeta.CUSTOM_PREFIX)
            CUSTOM_META_LABELS[inner]?.let { context.getString(it) } ?: inner
        }
        else -> key
    }

/**
 * 导出私钥对话框：说明明文风险 → 选容器格式 → 交给系统文件选择器落地。
 *
 * 「转换后」只在目标格式能生成时才可选（PKCS#8 ↔ OpenSSH 的支持范围见 SshKeyGenerator）。
 */
@Composable
internal fun VaultKeyExportDialog(
    entryName: String,
    publicKeyLine: String,
    privateKeyPem: String,
    onDismiss: () -> Unit,
    onExport: (content: String, suggestedFileName: String) -> Unit,
) {
    val sourceFormat = SshKeyGenerator.privateKeyFormat(privateKeyPem)
    val targetFormat = if (sourceFormat == SshKeyGenerator.PrivateKeyFormat.OPENSSH) {
        SshKeyGenerator.PrivateKeyFormat.PKCS8
    } else {
        SshKeyGenerator.PrivateKeyFormat.OPENSSH
    }
    var convert by remember { mutableStateOf(false) }
    val content = remember(privateKeyPem, convert) {
        when {
            !convert -> privateKeyPem
            targetFormat == SshKeyGenerator.PrivateKeyFormat.OPENSSH ->
                SshKeyGenerator.toOpenSshPrivateKey(privateKeyPem)
            else -> SshKeyGenerator.toPkcs8PrivateKey(privateKeyPem)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_keyexport_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.vault_keyexport_warning_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    entryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !convert, onClick = { convert = false })
                    Text(stringResource(R.string.vault_keyexport_format_original, sourceFormat.label))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = convert, onClick = { convert = true })
                    Text(stringResource(R.string.vault_keyexport_format_converted, targetFormat.label))
                }
                if (convert && content == null) {
                    Text(
                        stringResource(R.string.vault_keyexport_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.vault_keyexport_chmod_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            val exportContent = content
            TextButton(
                onClick = { onExport(exportContent!!, suggestedPrivateKeyFileName(publicKeyLine)) },
                enabled = !exportContent.isNullOrBlank(),
            ) { Text(stringResource(R.string.vault_keyexport_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_cancel)) }
        },
    )
}

/** 按公钥类型给出符合惯例的默认文件名（用户可在系统选择器里改）。 */
private fun suggestedPrivateKeyFileName(publicKeyLine: String): String = when {
    publicKeyLine.startsWith("ssh-rsa") -> "id_rsa"
    publicKeyLine.startsWith("ssh-ed25519") -> "id_ed25519"
    publicKeyLine.startsWith("ecdsa-sha2-") -> "id_ecdsa"
    else -> "id_key"
}

private fun resStr(res: android.content.res.Resources, id: Int): String = res.getString(id)
