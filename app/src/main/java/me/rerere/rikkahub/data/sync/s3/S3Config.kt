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
        BackupItem.WORKSPACE_DOCS,
        BackupItem.SKILLS,
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
     * 展开旧聚合项 FILES 为细分项（SKILLS+CHAT_FILES+FONTS_IMAGES）。
     * 细分项成熟后 UI 不再展示 FILES，存量配置含 FILES 时执行前展开。
     */
    fun withLegacyExpanded(): S3Config {
        if (FILES !in items) return this
        val expanded =
            buildList {
                items.forEach {
                    if (it == FILES) {
                        add(BackupItem.SKILLS)
                        add(BackupItem.CHAT_FILES)
                        add(BackupItem.FONTS_IMAGES)
                    } else {
                        add(it)
                    }
                }
            }.distinct()
        return copy(items = expanded)
    }

    @Serializable
    enum class BackupItem {
        /** 聊天/记忆/设置数据库（rikka_hub.db，含 vault 密文、SSH 主机、调度任务） */
        DATABASE,
        /** 设置与偏好（settings.json：助手/模型/外观/备份通道等；apiKey 明文随包） */
        SETTINGS,
        /** 头像图片（files/avatars/，与聊天图分离，体积小） */
        AVATARS,
        /** 工作区文档与状态（workspace/ 下真源/记忆/learnings/skills-lock 等，排除 tmp/.git/大件） */
        WORKSPACE_DOCS,
        /** 已装技能（files/skills/，递归） */
        SKILLS,
        /** 聊天图片/附件（files/upload/，体积大头，可选） */
        CHAT_FILES,
        /** 自定义字体与贴图（files/fonts/ files/images/） */
        FONTS_IMAGES,
        /** 工具输出缓存（files/tool_outputs/） */
        TOOL_OUTPUTS,
        /** 兼容旧配置的聚合项：等价于旧 FILES 的默认勾选集合（SKILLS+CHAT_FILES+FONTS_IMAGES） */
        FILES;

        /** 是否属于"文件类"（需打包 upload/skills/fonts/images 等）。旧 FILES 聚合与任一拆分项都算。 */
        fun wantsFiles(): Boolean = this == FILES || this == SKILLS || this == CHAT_FILES ||
            this == FONTS_IMAGES || this == AVATARS
    }
}
