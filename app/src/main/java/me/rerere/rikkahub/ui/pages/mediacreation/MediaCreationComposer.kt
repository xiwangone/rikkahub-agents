package me.rerere.rikkahub.ui.pages.mediacreation

import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.SlidersHorizontal
import me.rerere.hugeicons.stroke.Video01
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationCapabilities
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.canSubmit
import me.rerere.rikkahub.data.model.mixesFramesWithReferences
import me.rerere.rikkahub.data.model.withRequired
import me.rerere.rikkahub.ui.pages.setting.components.label
import java.io.File

private const val MAX_PICKED_IMAGES = 8

/**
 * 页面底部的输入区：素材、提示词，以及模型和参数的选择。
 */
@Composable
internal fun MediaCreationComposer(
    vm: MediaCreationVM,
    draft: MediaCreationDraft,
    selection: MediaCreationModelSelection?,
    providers: List<MediaGenerationProviderSetting>,
    recentOutputs: List<MediaCreationRecentOutput>,
    uploadConfigured: Boolean,
    onOpenMediaSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showModels by remember { mutableStateOf(false) }
    var showParams by remember { mutableStateOf(false) }

    // 正在为哪个角色挑选素材，非空时显示素材选择面板
    var pickingRole by rememberSaveable { mutableStateOf<ImageRole?>(null) }

    // 系统相册返回时面板已经关闭，这里记住选中的文件该放进哪个角色
    var importRole by rememberSaveable { mutableStateOf(ImageRole.REFERENCE) }
    val imagesPicker = rememberSafeLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_IMAGES)
    ) { uris ->
        vm.importAssets(uris, MediaCreationAssetType.IMAGE, importRole)
    }
    val imagePicker = rememberSafeLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        vm.importAssets(listOfNotNull(uri), MediaCreationAssetType.IMAGE, importRole)
    }
    val videoPicker = rememberSafeLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        vm.importAssets(listOfNotNull(uri), MediaCreationAssetType.VIDEO, ImageRole.REFERENCE)
    }

    val capabilities = selection?.capabilities
    val hasContent = vm.promptState.text.isNotEmpty() || draft.assets.isNotEmpty()
    val canSubmit = capabilities != null &&
        draft.copy(prompt = vm.promptState.text.toString()).canSubmit(capabilities)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (draft.editingNodeId != null) {
                EditingBar(onStop = vm::stopEditing)
            }
            if (capabilities != null && capabilities.acceptsAssets) {
                AssetRow(
                    assets = draft.assets,
                    capabilities = capabilities,
                    resolve = vm::resolve,
                    onPick = { pickingRole = it },
                    onRemove = vm::removeAsset,
                    onSetRole = vm::setAssetRole,
                )
                if (draft.assets.mixesFramesWithReferences(capabilities)) {
                    Text(
                        text = stringResource(R.string.media_creation_page_frames_with_references),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (capabilities.requiresRemoteInputs && draft.assets.isNotEmpty() && !uploadConfigured) {
                    Text(
                        text = stringResource(R.string.media_creation_page_upload_not_configured_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(onClick = onOpenMediaSettings),
                    )
                }
            }

            OutlinedTextField(
                state = vm.promptState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.media_creation_page_prompt_placeholder)) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 5),
                shape = MaterialTheme.shapes.large,
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerChip(
                        icon = if (selection?.model?.kind == MediaKind.VIDEO) HugeIcons.Video01 else HugeIcons.Image02,
                        text = selection?.model?.name ?: stringResource(R.string.media_creation_page_select_model),
                        onClick = { showModels = true },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selection != null && capabilities != null && capabilities.parameters.isNotEmpty()) {
                        val summary = draft.params
                            .withRequired(capabilities, selection.provider.presets(selection.model.kind).defaults)
                            .summary(selection.model.kind)
                        ComposerChip(
                            icon = HugeIcons.SlidersHorizontal,
                            text = summary.take(3).joinToString(" · ")
                                .ifEmpty { stringResource(R.string.media_creation_page_params) },
                            onClick = { showParams = true },
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    }
                }
                if (hasContent) {
                    IconButton(onClick = vm::clearDraft) {
                        Icon(
                            imageVector = HugeIcons.Eraser,
                            contentDescription = stringResource(R.string.media_creation_page_clear_input),
                        )
                    }
                }
                GenerateButton(enabled = canSubmit, onClick = vm::generate)
            }
        }
    }

    if (showModels) {
        ModelSheet(
            providers = providers,
            selectedModelId = draft.modelId,
            onSelect = { provider, model ->
                vm.selectModel(provider, model)
                showModels = false
            },
            onManage = {
                showModels = false
                onOpenMediaSettings()
            },
            onDismiss = { showModels = false },
        )
    }

    if (showParams && selection != null) {
        ParamsSheet(
            selection = selection,
            params = draft.params,
            onParamsChange = vm::updateParams,
            onDismiss = { showParams = false },
        )
    }

    val role = pickingRole
    if (role != null && capabilities != null) {
        // 视频只能作为参考素材
        val acceptsVideo = capabilities.videoInput && role == ImageRole.REFERENCE
        AssetPickerSheet(
            role = role,
            acceptsVideo = acceptsVideo,
            recentOutputs = recentOutputs.filter { acceptsVideo || !it.output.isVideo },
            resolve = vm::resolve,
            onPickImages = {
                pickingRole = null
                importRole = role
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                // 首帧和尾帧各只有一张
                if (role == ImageRole.REFERENCE) imagesPicker.launch(request) else imagePicker.launch(request)
            },
            onPickVideo = {
                pickingRole = null
                videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            onSelect = { output ->
                pickingRole = null
                if (output.isVideo) vm.useVideo(output) else vm.useImage(output, role)
            },
            onDismiss = { pickingRole = null },
        )
    }
}

/**
 * 输入区的内容是从时间线上的一条记录填回来的：生成的结果会成为它的新版本。退出后内容保留，生成时另起一条。
 */
@Composable
private fun EditingBar(onStop: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.PencilEdit01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.media_creation_page_editing_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = HugeIcons.Cancel01,
            contentDescription = stringResource(R.string.media_creation_page_stop_editing),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onStop)
                .padding(4.dp)
                .size(16.dp),
        )
    }
}

private val MediaGenerationCapabilities.acceptsAssets: Boolean
    get() = imageRoles.isNotEmpty() || videoInput

internal val MediaGenerationModel.name: String
    get() = displayName.ifBlank { modelId }

@Composable
private fun ComposerChip(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GenerateButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = HugeIcons.ArrowUp02,
                contentDescription = stringResource(R.string.media_creation_page_generate),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ---- 素材 ----

/**
 * 视频模型按用途分槽位：首帧、尾帧各一张，其后是任意数量的参考素材。图像模型只有参考图。
 */
@Composable
private fun AssetRow(
    assets: List<MediaCreationAsset>,
    capabilities: MediaGenerationCapabilities,
    resolve: (String) -> File,
    onPick: (ImageRole) -> Unit,
    onRemove: (MediaCreationAsset) -> Unit,
    onSetRole: (MediaCreationAsset, ImageRole) -> Unit,
) {
    val frameRoles = listOf(ImageRole.FIRST_FRAME, ImageRole.LAST_FRAME).filter { it in capabilities.imageRoles }
    val showLabels = frameRoles.isNotEmpty()

    @Composable
    fun Tile(asset: MediaCreationAsset) {
        val isVideo = asset.type == MediaCreationAssetType.VIDEO
        AssetTile(
            file = resolve(asset.path),
            isVideo = isVideo,
            label = if (isVideo) stringResource(R.string.video) else asset.role.label.takeIf { showLabels },
            // 图片可以在各个槽位之间挪动
            roleOptions = if (isVideo) emptyList() else capabilities.imageRoles.filter { it != asset.role },
            onSetRole = { onSetRole(asset, it) },
            onRemove = { onRemove(asset) },
        )
    }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        frameRoles.forEach { role ->
            val asset = assets.lastOrNull { it.type == MediaCreationAssetType.IMAGE && it.role == role }
            if (asset != null) Tile(asset) else EmptyAssetTile(label = role.label, onClick = { onPick(role) })
        }
        assets
            .filter { it.type == MediaCreationAssetType.VIDEO || it.role == ImageRole.REFERENCE }
            .forEach { Tile(it) }
        if (ImageRole.REFERENCE in capabilities.imageRoles || capabilities.videoInput) {
            EmptyAssetTile(
                label = if (showLabels) {
                    ImageRole.REFERENCE.label
                } else {
                    stringResource(R.string.media_creation_page_reference_image)
                },
                onClick = { onPick(ImageRole.REFERENCE) },
            )
        }
    }
}

private val AssetTileSize = 60.dp
private val AssetTileShape = RoundedCornerShape(12.dp)

@Composable
private fun AssetTile(
    file: File,
    isVideo: Boolean,
    label: String?,
    roleOptions: List<ImageRole>,
    onSetRole: (ImageRole) -> Unit,
    onRemove: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.size(AssetTileSize)) {
        MediaThumbnail(
            file = file,
            isVideo = isVideo,
            playIconSize = 10.dp,
            modifier = Modifier
                .fillMaxSize()
                .clip(AssetTileShape)
                .clickable(enabled = roleOptions.isNotEmpty()) { showMenu = true },
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(vertical = 1.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Cancel01,
                contentDescription = stringResource(R.string.media_creation_page_remove),
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            roleOptions.forEach { role ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (role) {
                                    ImageRole.FIRST_FRAME -> R.string.media_creation_page_set_as_first_frame
                                    ImageRole.LAST_FRAME -> R.string.media_creation_page_set_as_last_frame
                                    ImageRole.REFERENCE -> R.string.media_creation_page_set_as_reference
                                }
                            )
                        )
                    },
                    onClick = {
                        showMenu = false
                        onSetRole(role)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyAssetTile(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(AssetTileSize),
        shape = AssetTileShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

