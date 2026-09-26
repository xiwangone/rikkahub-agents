package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.workspace.WorkspaceMountSwitch
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule =
    module {
        single {
            ConversationRepository(get(), get(), get(), get(), get(), get())
        }

        single {
            FolderRepository(get(), get())
        }

        single {
            MemoryRepository(get())
        }

        single {
            GenMediaRepository(get())
        }

        single {
            FilesRepository(get())
        }

        single {
            FavoriteRepository(get())
        }

        single {
            WorkspaceMountSwitch()
        }

        single {
            val context: Context = get()
            val mountSwitch: WorkspaceMountSwitch = get()
            WorkspaceManager(
                baseDir = File(context.filesDir, "workspaces"),
                shellRunner =
                    ProotShellRunner(
                        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                    ),
                // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
                bindMounts =
                    listOf(
                        WorkspaceBindMount(
                            source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                            target = "/skills",
                        ),
                        WorkspaceBindMount(
                            source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                            target = "/tool_outputs",
                        ),
                        WorkspaceBindMount(
                            source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                            target = "/upload",
                        ),
                        // App 私有数据直通（2026-08-14）：沙箱内直接访问 App 数据库/配置（分析用）
                        WorkspaceBindMount(
                            source = File(context.filesDir, "databases"),
                            target = "/workspace/app-databases",
                        ),
                        WorkspaceBindMount(
                            source = File(context.filesDir, "shared_prefs"),
                            target = "/workspace/app-shared-prefs",
                        ),
                        // 跨工作区共享目录（常开，AI 可自由读写）
                        WorkspaceBindMount(
                            source = File(context.filesDir, "shared").apply { mkdirs() },
                            target = "/mnt/shared",
                        ),
                    ),
                // 可选挂载：手机存储 → /sdcard，默认关，由用户在详情页开关（写仍走审批白名单）
                optionalMounts =
                    OptionalMounts(
                        mounts =
                            mapOf(
                                "sdcard" to
                                    WorkspaceBindMount(
                                        source = File("/storage/emulated/0"),
                                        target = "/sdcard",
                                    ),
                            ),
                        enabled = {
                            if (mountSwitch.sdcardEnabled) setOf("sdcard") else emptySet()
                        },
                        // 可选挂载访问留痕：AI 碰手机存储的每个路径都记一条（diagnostics kind=logs 可查）
                        onAccess = { target, path ->
                            AppLog.i("WorkspaceSdcard", "access $target: $path")
                        },
                    ),
            )
        }

        single {
            RootfsInstaller(get())
        }

        single {
            WorkspaceRepository(get(), get(), get(), get())
        }

        single {
            FilesManager(get(), get(), get())
        }

        single {
            SkillManager(get(), get())
        }
    }
