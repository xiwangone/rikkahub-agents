package me.rerere.rikkahub.data.sync.s3

import kotlinx.serialization.Serializable

@Serializable
data class S3Config(
    val id: String = "",
    val name: String = "",
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "us-east-1",
    val pathStyle: Boolean = true,
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.SETTINGS,
        BackupItem.AVATARS,
    ),
) {
    val displayName: String
        get() = name.ifBlank { if (endpoint.isBlank()) "未命名" else endpoint.removePrefix("https://").removePrefix("http://").substringBefore('/') }

    val host: String
        get() = endpoint
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

    val isHttps: Boolean
        get() = endpoint.startsWith("https://")

    fun bucketUrl(): String {
        return if (pathStyle) {
            "${endpoint.trimEnd('/')}/$bucket"
        } else {
            val scheme = if (isHttps) "https://" else "http://"
            "$scheme$bucket.$host"
        }
    }

    /**
     * 展开旧聚合项 FILES 为细分项（SKILLS+CHAT_FILES+FONTS_IMAGES+AVATARS）。
     * 细分项成熟后 UI 不再展示 FILES，存量配置含 FILES 时执行前展开。
     *
     * **保留 FILES 本身**：它同时是"旧配置"标记。[Settings.activeS3Config] 等访问器
     * 会在返回前就展开，调用方拿到的 items 里若没有 FILES，兼容判断就永远失效——
     * 这正是 3.0.3 发布包里旧配置丢 settings.json 的根因。保留标记后，展开前后判断结果一致。
     */
    fun withLegacyExpanded(): S3Config {
        if (BackupItem.FILES !in items) return this
        val expanded =
            buildList {
                items.forEach {
                    add(it)
                    if (it == BackupItem.FILES) {
                        add(BackupItem.SKILLS)
                        add(BackupItem.CHAT_FILES)
                        add(BackupItem.FONTS_IMAGES)
                        add(BackupItem.AVATARS)
                    }
                }
            }.distinct()
        return copy(items = expanded)
    }

    /**
     * 旧配置兼容判断：未命中任何新拆分项（只含 DATABASE/FILES 等旧枚举）时，历史行为是
     * 备份/恢复默认带上 settings.json。
     *
     * 因为 [withLegacyExpanded] 保留 FILES 标记，**展开前后调用结果一致**；
     * 但执行层仍建议在入口先判一次，避免未来某处再次"展开即抹除标记"。
     */
    fun includesSettingsByLegacyCompat(): Boolean =
        items.contains(BackupItem.SETTINGS) ||
            items.contains(BackupItem.FILES) ||
            items.none { it in LEGACY_COMPAT_NEW_ITEM_KINDS }

    @Serializable
    enum class BackupItem {
        /** 聊天/记忆/设置数据库（rikka_hub.db，含 vault 密文、SSH 主机、调度任务） */
        DATABASE,
        /** 设置与偏好（settings.json：助手/模型/外观/备份通道等；apiKey 明文随包） */
        SETTINGS,
        /** 头像图片（files/avatars/，与聊天图分离，体积小） */
        AVATARS,
        /** 工作区文档与状态目录（排除 tmp/.git/大件） */
        WORKSPACE_DOCS,
        /** 已装技能（files/skills/，递归） */
        SKILLS,
        /** 聊天图片/附件（files/upload/，体积大头，可选） */
        CHAT_FILES,
        /** 自定义字体与贴图（files/fonts/ files/images/） */
        FONTS_IMAGES,
        /** 工具输出缓存（files/tool_outputs/） */
        TOOL_OUTPUTS,
        /** 兼容旧配置的聚合项：等价于旧 FILES 的默认勾选集合（SKILLS+CHAT_FILES+FONTS_IMAGES+AVATARS） */
        FILES,

        // ── 按域逻辑备份（数据库/设置的细分粒度）──
        // 内容以明文 JSON 进入 migration/ 段，整包口令加密；恢复时用本机 Keystore 重新加密。
        /** 提供商与模型配置（来自 settings.json 的 providers，含 apiKey） */
        PROVIDERS,
        /** 凭证库条目（导出时逐条解密；恢复时用本机 Keystore 重新加密） */
        VAULT_CREDENTIALS,
        /** SSH 主机（导出时解密 password/privateKey；恢复时重新加密） */
        SSH_HOSTS,
        /** 定时任务（scheduled_jobs 表，仅配置与游标，不含运行记录） */
        SCHEDULED_JOBS;
    }

    companion object {
        /** 新拆分的枚举项（旧配置不含），用于 settings.json 的旧配置兼容判断 */
        private val LEGACY_COMPAT_NEW_ITEM_KINDS = setOf(
            BackupItem.SETTINGS,
            BackupItem.AVATARS,
            BackupItem.WORKSPACE_DOCS,
            BackupItem.SKILLS,
            BackupItem.CHAT_FILES,
            BackupItem.FONTS_IMAGES,
            BackupItem.TOOL_OUTPUTS,
            BackupItem.PROVIDERS,
            BackupItem.VAULT_CREDENTIALS,
            BackupItem.SSH_HOSTS,
            BackupItem.SCHEDULED_JOBS,
        )
    }
}
