package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry

/** 文件夹导出计划中的一项：要在目标目录里创建的目录，或要写入的文件。 */
internal data class FolderExportItem(
    val sourcePath: String,
    val parentPath: String,
    val name: String,
    val isDirectory: Boolean,
)

/**
 * 依据「按目录分层的列表」生成导出计划：**父目录一定排在子项之前**，
 * 这样调用方可以顺序创建目录、随用随查。
 *
 * [listing] 的键是目录路径（根目录用其自身路径），值是该目录下的直接子项。
 * 缺失的层按空处理 —— 某一层读取失败时只跳过该子树，而不是让整单失败。
 */
internal fun planWorkspaceFolderExport(
    rootPath: String,
    listing: Map<String, List<WorkspaceFileEntry>>,
): List<FolderExportItem> {
    val plan = mutableListOf<FolderExportItem>()
    val visited = mutableSetOf<String>()

    fun walk(dirPath: String) {
        if (!visited.add(dirPath)) return
        for (child in listing[dirPath].orEmpty()) {
            plan += FolderExportItem(
                sourcePath = child.path,
                parentPath = dirPath,
                name = child.name,
                isDirectory = child.isDirectory,
            )
            if (child.isDirectory) walk(child.path)
        }
    }

    walk(rootPath)
    return plan
}
