package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Play
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.skills.SkillTestRunner
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SkillDetailPage(skillName: String) {
    val vm = koinViewModel<SkillDetailVM>()
    val skillsVm = koinViewModel<SkillsVM>()
    LaunchedEffect(skillName) { vm.init(skillName) }

    val tree by vm.tree.collectAsStateWithLifecycle()
    val isReadOnly by vm.readOnly.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current

    var editingFile by remember { mutableStateOf<SkillFile?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillFile?>(null) }
    var showTester by rememberSaveable { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    val isBundled = skillsVm.isBundledSkillName(skillName) && !isReadOnly
    val deleteFailedMsg = stringResource(R.string.skill_detail_page_delete_failed)
    val restoredMsg = stringResource(R.string.skill_store_restored)

    val scrollState = rememberScrollState()
    var previousScrollOffset by remember { mutableIntStateOf(0) }
    val fabVisible by remember {
        derivedStateOf {
            val delta = scrollState.value - previousScrollOffset
            previousScrollOffset = scrollState.value
            delta <= 0
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(skillName) },
                navigationIcon = { BackButton() },
                actions = {
                    if (isBundled) {
                        IconButton(onClick = { showRestoreConfirm = true }) {
                            Icon(
                                imageVector = HugeIcons.Refresh01,
                                contentDescription = stringResource(R.string.skill_store_restore_bundled),
                            )
                        }
                    }
                    if (isReadOnly) {
                        Text(
                            stringResource(R.string.skill_builtin_read_only),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(onClick = { showTester = true }) {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = stringResource(R.string.skill_tester_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = fabVisible && !isReadOnly,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(innerPadding + PaddingValues(16.dp)),
        ) {
            if (tree.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = CustomColors.listItemColors.containerColor,
                ) {
                    Column {
                        FileTree(
                            nodes = tree,
                            depth = 0,
                            onEdit = { editingFile = it },
                            onDelete = { if (!isReadOnly) deleteTarget = it },
                            readOnly = isReadOnly,
                        )
                    }
                }
            }
        }
    }

    editingFile?.let { skillFile ->
        EditFileDialog(
            skillFile = skillFile,
            readOnly = isReadOnly,
            initialContent = remember(skillFile.relativePath) { vm.readFile(skillFile) },
            onDismiss = { editingFile = null },
            onConfirm = { content ->
                vm.saveFile(skillFile.relativePath, content) { error ->
                    if (error == null) {
                        editingFile = null
                    } else {
                        toaster.show(error)
                    }
                }
            },
        )
    }

    if (showAddDialog) {
        AddFileDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { fileName, content ->
                vm.saveFile(fileName, content) { error ->
                    if (error == null) {
                        showAddDialog = false
                    } else {
                        toaster.show(error)
                    }
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skill_detail_page_delete_file),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { skillFile ->
                vm.deleteFile(skillFile) { success ->
                    if (!success) toaster.show(deleteFailedMsg)
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skill_detail_page_delete_confirm, deleteTarget?.relativePath ?: ""))
    }

    if (showTester) {
        SkillTesterSheet(
            skillName = skillName,
            onDismiss = { showTester = false },
        )
    }

    RikkaConfirmDialog(
        show = showRestoreConfirm,
        title = stringResource(R.string.skill_store_restore_bundled),
        confirmText = stringResource(R.string.skill_store_restore),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            skillsVm.restoreBundledSkill(skillName) {
                toaster.show(restoredMsg)
            }
            showRestoreConfirm = false
        },
        onDismiss = { showRestoreConfirm = false },
    ) {
        Text(stringResource(R.string.skill_store_restore_bundled_confirm))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillTesterSheet(
    skillName: String,
    onDismiss: () -> Unit,
) {
    val runner = koinInject<SkillTestRunner>()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var prompt by rememberSaveable { mutableStateOf("") }
    val stateFlow = remember { MutableStateFlow<SkillTestRunner.TestRunState>(SkillTestRunner.TestRunState.Idle) }
    val state by stateFlow.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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
                    text = stringResource(R.string.skill_tester_title, skillName),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.cancel))
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.skill_tester_prompt_placeholder)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            var isEnqueued by remember { mutableStateOf(false) }
            LaunchedEffect(state) {
                if (state is SkillTestRunner.TestRunState.Done ||
                    state is SkillTestRunner.TestRunState.Error ||
                    state is SkillTestRunner.TestRunState.Idle
                ) {
                    isEnqueued = false
                }
            }
            FilledTonalButton(
                onClick = {
                    val current = prompt
                    isEnqueued = true
                    scope.launch {
                        runner.runOnce(skillName, current).collect { stateFlow.value = it }
                    }
                },
                enabled =
                    prompt.isNotBlank() && !isEnqueued &&
                        state !is SkillTestRunner.TestRunState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.skill_tester_run))
            }

            when (val s = state) {
                is SkillTestRunner.TestRunState.Idle -> Unit

                is SkillTestRunner.TestRunState.Running -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skill_tester_running),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is SkillTestRunner.TestRunState.Done -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = s.text.ifBlank { stringResource(R.string.skill_tester_done_no_text) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (s.imageUrls.isNotEmpty()) {
                            Text(
                                text = "[${s.imageUrls.size} image part(s)]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                is SkillTestRunner.TestRunState.Error -> {
                    val msg =
                        if (s.error == "tester_timeout") {
                            stringResource(R.string.skill_tester_timeout_error)
                        } else {
                            "${s.error}${s.detail?.let { ": $it" }.orEmpty()}"
                        }
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTree(
    nodes: List<SkillFileNode>,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
    readOnly: Boolean = false,
) {
    nodes.fastForEach { node ->
        when (node) {
            is SkillFileNode.FileNode -> {
                FileItem(
                    skillFile = node.skillFile,
                    depth = depth,
                    onEdit = { onEdit(node.skillFile) },
                    onDelete = { onDelete(node.skillFile) },
                    readOnly = readOnly,
                )
            }

            is SkillFileNode.DirNode -> {
                DirItem(
                    node = node,
                    depth = depth,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    readOnly = readOnly,
                )
            }
        }
    }
}

private val TreeIndent = 24.dp

@Composable
private fun FileItem(
    skillFile: SkillFile,
    depth: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    readOnly: Boolean,
) {
    val deletable = !readOnly && skillFile.relativePath != "SKILL.md"
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .heightIn(min = 64.dp)
                .padding(start = 16.dp + TreeIndent * depth, end = if (deletable) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardGroupIcon(HugeIcons.File02)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = skillFile.file.name,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = skillFile.file.length().fileSizeToString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (deletable) {
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

@Composable
private fun DirItem(
    node: SkillFileNode.DirNode,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
    readOnly: Boolean,
) {
    var expanded by rememberSaveable(node.relativePath) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 0f else -90f)

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .heightIn(min = 64.dp)
                    .padding(start = 16.dp + TreeIndent * depth, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(if (expanded) HugeIcons.FolderOpen else HugeIcons.Folder01)
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
            )
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(20.dp)
                        .rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                FileTree(
                    nodes = node.children,
                    depth = depth + 1,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    readOnly = readOnly,
                )
            }
        }
    }
}

@Composable
private fun EditFileDialog(
    skillFile: SkillFile,
    readOnly: Boolean,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (content: String) -> Unit,
) {
    var content by remember(skillFile.relativePath) { mutableStateOf(initialContent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skillFile.relativePath, fontFamily = FontFamily.Monospace) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { if (!readOnly) content = it },
                readOnly = readOnly,
                label = { Text(stringResource(R.string.skill_detail_page_content)) },
                minLines = 10,
                maxLines = 20,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            if (!readOnly) {
                TextButton(onClick = { onConfirm(content) }) { Text(stringResource(R.string.skill_detail_page_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AddFileDialog(
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, content: String) -> Unit,
) {
    var fileName by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val fileNameError = fileName.isNotBlank() && (fileName.contains('\\'))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_detail_page_new_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.skill_detail_page_file_name)) },
                    placeholder = { Text("examples/basic.md", fontFamily = FontFamily.Monospace) },
                    supportingText = {
                        if (fileNameError) {
                            Text(
                                stringResource(R.string.skill_detail_page_file_name_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    isError = fileNameError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.skill_detail_page_content)) },
                    minLines = 6,
                    maxLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fileName.trim(), content) },
                enabled = fileName.isNotBlank() && !fileNameError,
            ) {
                Text(stringResource(R.string.skill_detail_page_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
