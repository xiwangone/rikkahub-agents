package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前内容所属的工作区 ID。
 *
 * Markdown 里的图片可能写作 Rootfs 绝对路径（如 `/workspace/out.png`），
 * 需要它才能解析到宿主机上的真实文件；未绑定工作区时为 null，路径原样交给 Coil。
 */
val LocalWorkspaceId = compositionLocalOf<String?> { null }
