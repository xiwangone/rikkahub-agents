package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.rikkahub.R

import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 工作区文本文件编辑/预览页.
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val supportsPreview = extension in setOf("html", "htm", "svg")
    var showPreview by rememberSaveable(id, area, path) { mutableStateOf(supportsPreview) }

    // 不能用 rememberTextFieldState: 它会把全文存进 saved state Bundle, 大文件切后台时触发 TransactionTooLargeException.
    // 内容本就由下方 LaunchedEffect 从磁盘加载, 无需 saveable.
    val textState = remember(id, area, path) { TextFieldState() }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 加载完成时的文本：与当前内容比对即得到脏状态；保存成功后同步更新，脏标记随之清除。
    var initialText by remember { mutableStateOf("") }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val isDirty = editable && !loading && loadError == null && textState.text.toString() != initialText

    // 有未保存改动时先弹确认（保存/放弃/取消），没改动直接返回；只读预览页没有脏状态。
    fun requestBack() {
        if (isDirty) {
            showUnsavedDialog = true
        } else {
            navController.popBackStack()
        }
    }

    fun save(onDone: (() -> Unit)? = null) {
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                repository.writeText(
                    id = id,
                    path = path,
                    text = textState.text.toString(),
                    overwrite = true,
                )
            }.onSuccess {
                initialText = textState.text.toString()
                toaster.show(context.getString(R.string.file_editor_saved), type = ToastType.Success)
                onDone?.invoke()
            }.onFailure {
                toaster.show(it.message ?: context.getString(R.string.file_editor_save_failed), type = ToastType.Error)
            }
            saving = false
        }
    }

    BackHandler(enabled = isDirty) { showUnsavedDialog = true }

    LaunchedEffect(id, area, path) {
        loading = true
        loadError = null
        runCatching {
            repository.readTextForPreview(id, area, path)
        }.onSuccess { content ->
            textState.setTextAndPlaceCursorAtEnd(content)
            initialText = content
            loading = false
        }.onFailure {
            loadError = it.message ?: context.getString(R.string.file_editor_read_failed)
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isDirty) "$fileName *" else fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // 与 BackButton 同一样式，但返回前要先过脏检查，不能直接 pop。
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = ::requestBack,
                        shapes = IconButtonDefaults.shapes(),
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = CustomColors.listItemColors.containerColor,
                            ),
                    ) {
                        Icon(
                            imageVector = HugeIcons.ArrowLeft01,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (supportsPreview && !loading && loadError == null) {
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Text(if (showPreview) stringResource(R.string.workspace_file_editor_source) else stringResource(R.string.common_preview))
                        }
                    }
                    if (editable && !loading && loadError == null) {
                        TextButton(
                            onClick = { save() },
                            enabled = isDirty && !saving,
                        ) {
                            Text(stringResource(R.string.workspace_file_editor_save))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            loadError != null -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                ) {
                    Text(
                        text = loadError ?: "",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            showPreview -> WorkspaceWebPreview(
                content = textState.text.toString(),
                isSvg = extension == "svg",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )

            else -> {
                TextField(
                    state = textState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .imePadding(),
                    readOnly = !editable,
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    textStyle =
                        LocalTextStyle.current.copy(
                            fontFamily = JetbrainsMono,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                )
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("未保存的更改") },
            text = { Text("此文件有未保存的更改，直接返回将丢失这些更改。") },
            // 保存 / 放弃 / 取消三路：放弃直接返回丢弃改动，保存成功后再返回。
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    navController.popBackStack()
                }) {
                    Text("放弃")
                }
                TextButton(onClick = {
                    showUnsavedDialog = false
                    save { navController.popBackStack() }
                }) {
                    Text(stringResource(R.string.workspace_file_editor_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkspaceWebPreview(
    content: String,
    isSvg: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = content,
        baseUrl = "https://workspace-preview.invalid/",
        mimeType = if (isSvg) "image/svg+xml" else "text/html",
        settings = {
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )
    WebView(state = state, modifier = modifier)
}
