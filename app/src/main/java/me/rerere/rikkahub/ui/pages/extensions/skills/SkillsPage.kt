package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val visibleSkills = remember(skills, searchQuery) { filterSkills(skills, searchQuery) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.skills_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCatalog = true }) {
                        Icon(
                            imageVector = Lucide.Globe,
                            contentDescription = stringResource(R.string.skill_store_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(onClick = { showImportDialog = true }) {
                    Icon(
                        HugeIcons.Download01,
                        contentDescription = stringResource(R.string.skills_page_import_from_github),
                    )
                }
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(
                        HugeIcons.Add01,
                        contentDescription = stringResource(R.string.accessibility_add_skill),
                    )
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                innerPadding +
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 16.dp + 72.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (skills.isEmpty()) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.skills_page_empty_title),
                        description = stringResource(R.string.skills_page_empty_hint),
                        icon = HugeIcons.Puzzle,
                    )
                }
            }

            if (skills.isNotEmpty()) {
                item(key = "search") {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.skills_page_search)) },
                        leadingIcon = {
                            Icon(HugeIcons.Search01, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        HugeIcons.Cancel01,
                                        contentDescription = stringResource(R.string.cancel),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = CircleShape,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                    )
                }
            }

            if (skills.isNotEmpty() && visibleSkills.isEmpty()) {
                item(key = "no_result") {
                    Text(
                        text = stringResource(R.string.skills_page_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            items(visibleSkills, key = { it.skillDir.absolutePath }) { skill ->
                SkillCard(
                    skill = skill,
                    onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                    onDelete = { deleteTarget = skill },
                )
            }
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, content ->
                vm.saveSkill(name, content) { success ->
                    showAddDialog = false
                    if (!success) {
                        toaster.show(resources.getString(R.string.skills_page_save_failed))
                    }
                }
            },
        )
    }

    val openDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                vm.importFromLocalFile(uri) { success, message ->
                    showImportDialog = false
                    if (success) {
                        toaster.show(resources.getString(R.string.skills_page_import_success, message))
                    } else {
                        val key = mapImportErrorKeyToString(context, message)
                        toaster.show(resources.getString(R.string.skills_page_import_failed, key))
                    }
                }
            }
        }

    if (showImportDialog) {
        ImportSkillDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { repoUrl ->
                vm.importSkillFromGitHub(repoUrl) { success, message ->
                    showImportDialog = false
                    if (success) {
                        toaster.show(resources.getString(R.string.skills_page_import_success, message))
                    } else {
                        toaster.show(resources.getString(R.string.skills_page_import_failed, message))
                    }
                }
            },
            onPickFile = {
                openDocumentLauncher.launch(
                    arrayOf(
                        "text/markdown",
                        "text/plain",
                        "application/zip",
                        "application/octet-stream",
                    ),
                )
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteSkill(it.name) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skills_page_delete_message, deleteTarget?.name ?: ""))
    }

    val mergedCatalog by vm.mergedCatalog.collectAsStateWithLifecycle()
    val catalogLoading by vm.catalogLoading.collectAsStateWithLifecycle()
    val storeSources by vm.storeSources.collectAsStateWithLifecycle()
    var showSources by rememberSaveable { mutableStateOf(false) }

    if (showCatalog) {
        val installedNames by vm.installedSkillNames.collectAsStateWithLifecycle()
        FeaturedCatalogSheet(
            merged = mergedCatalog,
            loading = catalogLoading,
            sources = storeSources,
            installedNames = installedNames,
            onRefresh = { vm.refreshCatalog() },
            onInstall = { entry, onDone ->
                vm.installFromCatalog(entry) { success, message ->
                    if (success) {
                        toaster.show(resources.getString(R.string.skills_page_import_success, message))
                    } else {
                        val key = mapImportErrorKeyToString(context, message)
                        toaster.show(resources.getString(R.string.skill_catalog_install_failed, key))
                    }
                    onDone()
                }
            },
            onManageSources = { showSources = true },
            onDismiss = { showCatalog = false },
        )
    }

    if (showSources) {
        StoreSourcesSheet(
            sources = storeSources,
            statuses = mergedCatalog.sourceStatuses,
            onAdd = { name, url, onDone ->
                vm.addStoreSource(name, url) { success ->
                    onDone(success)
                }
            },
            onRemove = { vm.removeStoreSource(it) },
            onToggle = { id, enabled -> vm.setStoreSourceEnabled(id, enabled) },
            onDismiss = { showSources = false },
        )
    }
}

@Composable
private fun SkillCard(
    skill: SkillMetadata,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(HugeIcons.Puzzle)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (skill.source) {
                    me.rerere.rikkahub.data.files.SkillSource.BUILTIN ->
                        Text(
                            stringResource(R.string.skill_builtin_read_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    me.rerere.rikkahub.data.files.SkillSource.USER_OVERRIDE ->
                        Text(
                            stringResource(R.string.skill_builtin_user_override),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    me.rerere.rikkahub.data.files.SkillSource.USER -> Unit
                }
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!skill.compatibility.isNullOrBlank()) {
                    Text(
                        text = skill.compatibility,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (skill.source != me.rerere.rikkahub.data.files.SkillSource.BUILTIN) {
                ItemActionMenu(
                    actions =
                        listOf(
                            ItemAction(
                                text = stringResource(R.string.delete),
                                icon = HugeIcons.Delete01,
                                destructive = true,
                                onClick = onDelete,
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var content by rememberSaveable { mutableStateOf("") }

    val name =
        remember(content) {
            SkillFrontmatterParser.parse(content)["name"]?.trim() ?: ""
        }
    val nameError = content.isNotBlank() && name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skills_page_skill_content_label)) },
                placeholder = {
                    Text(
                        "---\nname: my-skill\ndescription: \"...\"\n---\n\nSkill body goes here...",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                supportingText = {
                    if (nameError) {
                        Text(
                            stringResource(R.string.skills_page_name_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (name.isNotBlank()) {
                        Text(stringResource(R.string.skills_page_skill_name, name))
                    } else {
                        Text(stringResource(R.string.skills_page_paste_hint))
                    }
                },
                isError = nameError,
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, content) },
                enabled = name.isNotBlank() && !nameError,
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
    onPickFile: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.skills_page_import_from_github)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = { Text("https://github.com/owner/repo", fontFamily = FontFamily.Monospace) },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.skill_import_from_file_label))
                }
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skills_page_downloading),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true
                    onConfirm(url)
                },
                enabled = url.isNotBlank() && !loading,
            ) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun mapImportErrorKeyToString(
    context: android.content.Context,
    key: String,
): String =
    when (key) {
        "skill_import_unsupported_file_type" -> {
            context.getString(R.string.skill_import_unsupported_file_type)
        }

        "skill_import_missing_skill_md" -> {
            context.getString(R.string.skill_import_missing_skill_md)
        }

        "skill_import_path_traversal" -> {
            context.getString(R.string.skill_import_path_traversal)
        }

        "skill_import_zip_too_large" -> {
            context.getString(R.string.skill_import_zip_too_large)
        }

        "skill_import_md_too_large" -> {
            context.getString(R.string.skill_import_md_too_large)
        }

        "skill_import_empty_file" -> {
            context.getString(R.string.skill_import_empty_file)
        }

        else -> {
            key
        }
    }
