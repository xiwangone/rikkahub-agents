package me.rerere.rikkahub.ui.components.vault

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import org.koin.compose.koinInject

/** 引用前缀：与运行时解析层（provider 密钥解析）保持一致。 */
private const val REF_PREFIX = "\$\$"

/**
 * 密钥引用输入框 —— "引用已有 / 粘贴即入库"的统一入口。
 *
 * 凡是需要填写密钥或令牌的地方（provider 的 apiKey、MCP 的令牌、SSH 凭据引用等）都应改用它，
 * 目的是让这些位置**默认不留下明文**：
 * - 点钥匙图标 → 从凭证库挑一条 → 回填 `$$名字` 引用（运行时由解析层取真值）；
 * - 直接粘贴明文后，点存入图标 → 立即入库（自动定名、自动判类型并做重复检查）→ 输入框替换为引用；
 * - 仍保留原有的"显示/隐藏"能力。
 *
 * 状态提示只回显**凭证名**与符号，不引入新文案（避免多语言负担）。
 */
@Composable
fun SecretRefField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    /** "粘贴即入库"时的默认命名依据（如 provider 名）；会被规范化成合法名。 */
    nameHint: String = "KEY",
    enabled: Boolean = true,
    /** 是否保留"显示/隐藏"图标（原先各页面自带的那个）。 */
    showVisibilityToggle: Boolean = true,
    /** 是否单行（按各页面原有样式传入，避免接入后观感变化）。 */
    singleLine: Boolean = false,
    /** 非单行时的最大行数（多行密钥/私钥等场景用）。 */
    maxLines: Int = 3,
    /** 空值时的占位提示；默认提示可直接写 `$$名字` 引用（语法可发现性）。 */
    placeholder: String? = null,
    /**
     * 该引用位的**用途**（凭证类型名，如 `ssh-key` / `basic-auth`）。
     * 选择器打开时会预筛到该类型，避免跨用途误选。
     */
    typeHint: String? = null,
) {
    val repository: CredentialVaultRepository = koinInject()
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<VaultCredentialEntity>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entries = runCatching { repository.getAll() }.getOrDefault(emptyList())
    }

    val isReference = value.startsWith(REF_PREFIX)

    OutlinedTextField(
        value = value,
        onValueChange = {
            onValueChange(it)
            notice = null
        },
        label = { Text(label) },
        placeholder = { Text(placeholder ?: stringResource(R.string.vault_ref_hint)) },
        supportingText = {
            val hint = notice ?: description
            if (hint != null) Text(hint)
        },
        enabled = enabled && !busy,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else maxLines,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row {
                if (showVisibilityToggle) {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = null,
                        )
                    }
                }
                // 只有"确实是一段明文"时才提供入库：已是引用则无意义
                if (value.isNotBlank() && !isReference) {
                    IconButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching {
                                    repository.quickImport(rawValue = value, preferredName = nameHint)
                                }.onSuccess { r ->
                                    onValueChange(REF_PREFIX + r.name)
                                    // 复用已有条目时用不同符号，让用户知道没有产生重复
                                    notice = if (r.reusedExisting) "♻ ${r.name}" else "✅ ${r.name}"
                                    entries = runCatching { repository.getAll() }.getOrDefault(entries)
                                }.onFailure { e ->
                                    notice = "❌ ${e.message ?: ""}"
                                }
                                busy = false
                            }
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileImport,
                            contentDescription = stringResource(R.string.vault_ref_store),
                        )
                    }
                }
                IconButton(onClick = { showPicker = true }) {
                    Icon(
                        imageVector = HugeIcons.Key01,
                        contentDescription = stringResource(R.string.vault_ref_pick),
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )

    if (showPicker) {
        VaultCredentialPickerDialog(
            entries = entries,
            initialTypeFilter = typeHint,
            onPick = {
                onValueChange(REF_PREFIX + it.name)
                notice = null
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}
