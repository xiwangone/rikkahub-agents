package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Global
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.skills.CatalogEntry
import me.rerere.rikkahub.skills.MergedSkillCatalog
import me.rerere.rikkahub.skills.SkillStoreSource
import me.rerere.rikkahub.skills.SkillStoreSourceStatus
import me.rerere.rikkahub.skills.SkillStoreSourceStore
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.utils.plus


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeaturedCatalogSheet(
    merged: MergedSkillCatalog,
    loading: Boolean,
    sources: List<SkillStoreSource>,
    installedNames: Set<String>,
    onRefresh: () -> Unit,
    onInstall: (CatalogEntry, onResult: () -> Unit) -> Unit,
    onManageSources: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    // null = all sources; otherwise the sourceId being filtered on.
    var selectedSource by rememberSaveable { mutableStateOf<String?>(null) }
    val enabledSources = sources.filter { it.enabled }
    val entries =
        remember(merged.entries, selectedSource) {
            if (selectedSource == null) {
                merged.entries
            } else {
                merged.entries.filter { it.sourceId == selectedSource }
            }
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skill_store_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.skill_store_refresh),
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.cancel))
                }
            }

            // Source switcher — only shown once there is more than the built-in source.
            if (enabledSources.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedSource == null,
                            onClick = { selectedSource = null },
                            label = { Text(stringResource(R.string.skill_store_tab_local)) },
                        )
                    }
                    items(enabledSources, key = { it.id }) { source ->
                        FilterChip(
                            selected = selectedSource == source.id,
                            onClick = { selectedSource = source.id },
                            label = { Text(source.name) },
                        )
                    }
                }
            }

            when {
                entries.isEmpty() && loading -> Unit

                entries.isEmpty() ->
                    Text(
                        text = stringResource(R.string.skills_page_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else ->
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // weight(1f)：列表只占标题行与底部「管理商店源」之间的剩余空间。
                        // 缺了它，固定 0.7f 高的 Column 会被列表内容撑爆，底部按钮被挤出
                        // 可见区（720x1280 机型上不可见、不可点）。
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        items(entries, key = { it.name }) { entry ->
                            CatalogRow(
                                entry = entry,
                                installed = entry.name in installedNames,
                                installing = entry.name in installing,
                                onInstall = {
                                    installing = installing + entry.name
                                    onInstall(entry) {
                                        installing = installing - entry.name
                                    }
                                },
                            )
                        }
                    }
            }

            TextButton(
                onClick = onManageSources,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Global, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.skill_store_manage),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Entry status for the catalog row badge; mirrors the tool-step badge vocabulary. */
private enum class SkillStoreEntryStatus { AVAILABLE, INSTALLING, INSTALLED, RESTORE }

/**
 * Status chip for store rows. Visual parameters match `ToolStatusBadge`
 * (CircleShape, labelSmallEmphasized, 1dp hairline border, 8x3dp padding) so the store
 * reads as the same design language as tool-call summaries.
 */
@Composable
private fun SkillStoreStatusBadge(
    status: SkillStoreEntryStatus,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val (labelRes, containerColor, contentColor) =
        when (status) {
            SkillStoreEntryStatus.AVAILABLE ->
                Triple(
                    R.string.skill_store_status_available,
                    colorScheme.surfaceContainerHigh,
                    colorScheme.onSurfaceVariant,
                )

            SkillStoreEntryStatus.INSTALLING ->
                Triple(
                    R.string.skill_catalog_installing,
                    colorScheme.surfaceContainerHigh,
                    colorScheme.onSurfaceVariant,
                )

            SkillStoreEntryStatus.INSTALLED ->
                Triple(
                    R.string.skill_catalog_installed,
                    colorScheme.secondaryContainer,
                    colorScheme.onSecondaryContainer,
                )

            SkillStoreEntryStatus.RESTORE ->
                Triple(
                    R.string.skill_store_restore,
                    colorScheme.primaryContainer,
                    colorScheme.onPrimaryContainer,
                )
        }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.12f)),
    ) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmallEmphasized,
            maxLines = 1,
        )
    }
}

/** Neutral provenance chip: "Built-in" for local entries, else the source name. */
@Composable
private fun SourceTagChip(
    sourceName: String?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colorScheme.surfaceContainerHigh,
        contentColor = colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Text(
            text = sourceName ?: stringResource(R.string.skill_store_source_local),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * One store row. Container treatment mirrors `ToolSummarySurface` (medium corners,
 * surfaceContainerLow, hairline outlineVariant border, 12/10dp padding, 8dp gaps);
 * the row itself follows the title/summary/detail split from RenderingStandards:
 * title + status badge on row 1, provenance + meta on row 2, description below.
 */
@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Puzzle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SkillStoreStatusBadge(
                    status =
                        when {
                            installing -> SkillStoreEntryStatus.INSTALLING
                            installed && entry.isBundled -> SkillStoreEntryStatus.RESTORE
                            installed -> SkillStoreEntryStatus.INSTALLED
                            else -> SkillStoreEntryStatus.AVAILABLE
                        },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SourceTagChip(sourceName = entry.sourceName)
                Text(
                    text = "${entry.category} · ${entry.sizeKb} KB · ${entry.compatibility}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalButton(
                onClick = onInstall,
                enabled = !installing && (entry.isBundled || !entry.sourceUrl.isNullOrBlank() || entry.archived),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            installing -> R.string.skill_catalog_installing
                            installed && entry.isBundled -> R.string.skill_store_restore
                            installed -> R.string.skill_catalog_installed
                            else -> R.string.skill_catalog_install
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Phase 19E — manage remote store sources. Add / remove / toggle sources and see each
 * source's last fetch status (fresh / cached / unreachable). Adding is inline (name +
 * URL fields at the bottom) rather than a dialog so the list stays visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoreSourcesSheet(
    sources: List<SkillStoreSource>,
    statuses: Map<String, SkillStoreSourceStatus>,
    onAdd: (name: String, url: String, onDone: (Boolean) -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // 0.9f 而非 0.7f：sheet 内容要同时装下 标题 + 提示 + 源列表 + 内联表单，
        // 0.7f 时留给源列表的 weighted 空间只剩 ~43dp（< 一行源行的 64dp），
        // 状态行（已更新 / 显示缓存 / 无法访问）被裁在视口下缘——既看不见，
        // 被裁掉的节点也不进无障碍树（滚动后才出现）。
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skill_store_manage_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.cancel))
                }
            }

            Text(
                text = stringResource(R.string.skill_store_sources_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                if (sources.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.skill_store_sources_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(sources, key = { it.id }) { source ->
                    StoreSourceRow(
                        source = source,
                        status = statuses[source.id],
                        onToggle = { onToggle(source.id, it) },
                        onRemove = { onRemove(source.id) },
                    )
                }
            }

            // Inline add form.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skill_store_add_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skill_store_add_url)) },
                    placeholder = { Text(stringResource(R.string.skill_store_catalog_url_hint)) },
                    isError = addError != null,
                    supportingText = addError?.let { { Text(stringResource(it)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        if (adding) return@FilledTonalButton
                        adding = true
                        addError = null
                        onAdd(name, url) { success ->
                            adding = false
                            if (success) {
                                name = ""
                                url = ""
                            } else {
                                addError =
                                    if (SkillStoreSourceStore.normalizeUrl(url) == null) {
                                        R.string.skill_store_invalid_url
                                    } else {
                                        R.string.skill_store_limit
                                    }
                            }
                        }
                    },
                    enabled = url.isNotBlank() && !adding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.skill_store_add))
                }
            }
        }
    }
}

@Composable
private fun StoreSourceRow(
    source: SkillStoreSource,
    status: SkillStoreSourceStatus?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = source.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                status?.let { s ->
                    Text(
                        text =
                            stringResource(
                                when (s) {
                                    SkillStoreSourceStatus.OK -> R.string.skill_store_status_ok
                                    SkillStoreSourceStatus.OK_CACHED -> R.string.skill_store_status_cached
                                    SkillStoreSourceStatus.FAILED -> R.string.skill_store_status_failed
                                },
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (s == SkillStoreSourceStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                    )
                }
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggle,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

