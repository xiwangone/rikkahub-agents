package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultBiometric
import me.rerere.rikkahub.data.vault.VaultSessionInfo
import me.rerere.rikkahub.data.vault.VaultSessionManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel

private const val CLIPBOARD_LABEL = "vault-session"

/**
 * 安全凭证库：会话签发与会话列表分组（与审计分组同一拆分口径）。
 */
@Composable
internal fun VaultSessionSection(
    vaultSessionManager: VaultSessionManager,
    biometricEnabled: Boolean,
    biometricBuffer: BiometricResultBuffer,
    sessionToken: String?,
    onSessionTokenChange: (String?) -> Unit,
    sessionResult: String?,
    onSessionResultChange: (String?) -> Unit,
    sessionListState: List<VaultSessionInfo>,
    onRefreshSessionList: () -> Unit,
) {
    val context = LocalContext.current
    val biometricExportTitleText = stringResource(R.string.vault_biometric_export_title)
    val sessionCancelledText = stringResource(R.string.vault_session_cancelled)
    val sessionIssuedManualText = stringResource(R.string.vault_session_issued_manual)
    val sessionIssuedTtlText = stringResource(R.string.vault_session_issued_ttl)
    val exportFailedFormat = stringResource(R.string.vault_export_failed)
    val sessionCopiedText = stringResource(R.string.vault_session_copied)
    val tokenCopiedText = stringResource(R.string.vault_session_token_copied)
    val sessionRevokedText = stringResource(R.string.vault_session_revoked)
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        var sessionMode by remember { mutableStateOf(false) }
        var allowedNamesInput by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            sessionMode = vaultSessionManager.getSessionMode()
            if (vaultSessionManager.hasSession()) {
                onSessionTokenChange(vaultSessionManager.reissueToken())
            }
        }

        CardGroup(
            title = { Text(stringResource(R.string.vault_session_title)) },
        ) {
            formItem(
                leadingContent = { CardGroupIcon(HugeIcons.Clock02) },
                headlineContent = { Text(stringResource(R.string.vault_session_title)) },
                supportingContent = { Text(stringResource(R.string.vault_session_desc)) },
            ) {
                ConnectedChoiceButtons(
                    options = listOf(false, true),
                    selected = sessionMode,
                    onSelect = { sessionMode = it },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) { manualMode, isSelected ->
                    ConnectedChoiceLabel(
                        text =
                            stringResource(
                                if (manualMode) {
                                    R.string.vault_session_mode_manual
                                } else {
                                    R.string.vault_session_mode_ttl
                                },
                            ),
                        selected = isSelected,
                        icon = HugeIcons.Tick01,
                    )
                }
                Text(
                    text =
                        if (sessionMode) {
                            stringResource(R.string.vault_session_mode_manual_desc)
                        } else {
                            stringResource(R.string.vault_session_mode_ttl_desc)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = allowedNamesInput,
                    onValueChange = { allowedNamesInput = it },
                    label = { Text(stringResource(R.string.vault_session_allowed_names)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                if (biometricEnabled) {
                                    val ok = VaultBiometric.authenticate(context, biometricBuffer, biometricExportTitleText)
                                    if (!ok) {
                                        onSessionResultChange(sessionCancelledText)
                                        return@launch
                                    }
                                }
                                val token =
                                    vaultSessionManager.issueToken(
                                        sessionMode = sessionMode,
                                        allowedNames =
                                            allowedNamesInput
                                                .split(',')
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() },
                                    )
                                onSessionTokenChange(token)
                                onSessionResultChange(
                                    if (sessionMode) {
                                        sessionIssuedManualText
                                    } else {
                                        sessionIssuedTtlText
                                    })
                                onRefreshSessionList()
                            }.onFailure { e ->
                                onSessionResultChange(exportFailedFormat.format(e.message ?: ""))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = HugeIcons.LockKey,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.vault_session_issue))
                }
                sessionToken?.let { token ->
                    var sessionTokenVisible by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (sessionTokenVisible) token else CredentialVaultRepository.mask(token),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { sessionTokenVisible = !sessionTokenVisible }) {
                            Icon(
                                imageVector = if (sessionTokenVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription = null,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(CLIPBOARD_LABEL, token))
                                onSessionResultChange(sessionCopiedText)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.vault_session_copy))
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    vaultSessionManager.revoke()
                                    onSessionTokenChange(null)
                                    onSessionResultChange(null)
                                    onRefreshSessionList()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.vault_session_revoke))
                        }
                    }
                }
                sessionResult?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        LaunchedEffect(Unit) { onRefreshSessionList() }
        CardGroup(
            title = { Text(stringResource(R.string.vault_session_list_title)) },
        ) {
            if (sessionListState.isEmpty()) {
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.Key01) },
                    headlineContent = { Text(stringResource(R.string.vault_session_empty)) },
                    supportingContent = { Text(stringResource(R.string.vault_session_list_desc)) },
                )
            } else {
                sessionListState.forEach { s ->
                    val labelFallback = stringResource(R.string.vault_session_label_default)
                    val remaining = s.remainingMs()
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Key01) },
                        headlineContent = { Text(s.label.ifBlank { labelFallback }) },
                        supportingContent = {
                            Text(
                                when {
                                    remaining == Long.MAX_VALUE -> stringResource(R.string.vault_session_remaining_session)
                                    remaining >= 24L * 60 * 60 * 1000 -> stringResource(R.string.vault_session_remaining_day, remaining / (24L * 60 * 60 * 1000))
                                    else -> stringResource(R.string.vault_session_remaining_min, (remaining / 60000L).toInt())
                                },
                            )
                        },
                        trailingContent = {
                            Row {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val token = vaultSessionManager.reissueTokenFor(s.id)
                                            if (token != null) {
                                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(CLIPBOARD_LABEL, token))
                                                onSessionResultChange(tokenCopiedText)
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.vault_session_copy))
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            vaultSessionManager.revokeSession(s.id)
                                            onRefreshSessionList()
                                            onSessionResultChange(sessionRevokedText)
                                            if (sessionToken != null && vaultSessionManager.listSessions().isEmpty()) {
                                                onSessionTokenChange(null)
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.vault_session_revoke),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
