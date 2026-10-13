package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.SerializationException

private const val TAG = "UpdateChecker"

/** 日志里保留的失败详情最大长度，避免整段 HTML / 长 JSON 刷屏。 */
private const val MAX_DETAIL_LENGTH = 200

/** 本轮检查没成功的原因分类。 */
enum class UpdateCheckReason {
    /** 403：GitHub 匿名限流（60 次/小时/IP） */
    RATE_LIMITED,

    /** 5xx：服务端错误 */
    SERVER_ERROR,

    /** 连接 / 读写异常 */
    NETWORK_ERROR,

    /** 2xx 但响应体不是预期的 release JSON */
    MALFORMED_RESPONSE,

    /** 其它状态码，含「404 但 body 不是 GitHub 的 Not Found」 */
    UNKNOWN_STATUS,
}

/**
 * 一次检查「没能问到结果」（issue #13 的 B 类）：可能是限流、服务端错误、网络抖动或响应格式变了，
 * 但它**不携带任何「有没有发布 / 有没有更新」的信息**。
 *
 * 渲染端据此把它降级成不打扰的一行说明，而不是刺眼的红色「检查更新失败」横幅；同时它也不折叠进
 * A 类的「无发布」静默态——失败伪装成成功会让用户和后续排查都被误导。真实原因留在
 * [reason] / [statusCode] / [detail] 和 [me.rerere.rikkahub.data.log.AppLog] 里，供用户回报时定位。
 */
class UpdateCheckUnavailableException(
    val reason: UpdateCheckReason,
    val statusCode: Int?,
    /** GitHub 错误响应里的 `message`，或底层异常自己的 message（已截断） */
    val detail: String?,
    cause: Throwable? = null,
) : Exception(
        buildString {
            append("update check did not succeed: ")
            append(statusCode?.let { "HTTP $it" } ?: reason.name)
            if (!detail.isNullOrBlank()) {
                append(" (")
                append(detail)
                append(")")
            }
        },
        cause,
    )

class UpdateChecker(
    private val client: OkHttpClient,
    appScope: AppScope,
    private val apiUrl: String = BuildConfig.UPDATE_API_URL,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val updateState: StateFlow<UiState<UpdateInfo>> =
        checkUpdate().stateIn(
            scope = appScope,
            started = SharingStarted.Lazily,
            initialValue = UiState.Loading,
        )

    private fun checkUpdate(): Flow<UiState<UpdateInfo>> =
        flow {
            emit(UiState.Loading)
            if (apiUrl.isBlank()) {
                emit(UiState.Success(noReleaseInfo()))
                return@flow
            }
            // null = A 类「暂无可安装发布」：走静默态，不弹失败横幅
            emit(UiState.Success(data = fetchUpdateInfo() ?: noReleaseInfo()))
        }.catch {
            // B 类的原因已在分类处写过一次日志，这里只兜住「未预期失败」
            if (it !is UpdateCheckUnavailableException) {
                AppLog.w(TAG, "update check ended in unexpected error: ${it.javaClass.simpleName}: ${it.message}")
            }
            emit(UiState.Error(it))
        }.flowOn(Dispatchers.IO)

    /**
     * 拉取最新 release，并按 issue #13 的三态分离返回/抛出：
     *
     * - [UpdateInfo]：C 类，拿到一份真实发布（到底"有没有更新"仍由 UI 比版本号决定）
     * - `null`：A 类，「没有可安装的发布」——404 且 body 是 GitHub 的 `Not Found`，或 release 存在
     *   但一个安装包 asset 都没有
     * - [UpdateCheckUnavailableException]：B 类，本次未成功（403 限流 / 5xx / 网络异常 / 解析失败）
     * - 其它异常：未预期失败，保留原来的强提示
     */
    private suspend fun fetchUpdateInfo(): UpdateInfo? =
        try {
            val response =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(apiUrl)
                            .get()
                            .addHeader(
                                "User-Agent",
                                "RikkaHub Agents $currentVersionName #${BuildConfig.VERSION_CODE}",
                            ).build(),
                    ).await()
            val body = response.body.string()
            when {
                // GitHub Releases API: releases/latest 响应 → UpdateInfo
                response.isSuccessful -> parseRelease(body)
                response.code == 404 && apiMessage(body).equals("Not Found", ignoreCase = true) -> {
                    AppLog.w(TAG, "no release published yet (404 Not Found): $apiUrl")
                    null
                }
                else -> throw unavailableForStatus(response.code, body)
            }
        } catch (e: IOException) {
            throw unavailable(
                reason = UpdateCheckReason.NETWORK_ERROR,
                statusCode = null,
                detail = e.message.abbreviate(),
                cause = e,
            )
        } catch (e: SerializationException) {
            throw unavailable(
                reason = UpdateCheckReason.MALFORMED_RESPONSE,
                statusCode = null,
                detail = e.message.abbreviate(),
                cause = e,
            )
        }

    /** C 类解析；「有 release 但没有任何安装包 asset」按 A 类处理（用户拿不到可下载的东西）。 */
    private fun parseRelease(body: String): UpdateInfo? {
        val info = parseGitHubRelease(body)
        if (info.downloads.isEmpty()) {
            AppLog.w(
                TAG,
                "release ${info.version} ships no installable assets: treated as \"nothing to update to\"",
            )
            return null
        }
        return info
    }

    /** 非 2xx（以及「404 但 body 不是 Not Found」）⇒ B 类，按状态码归类原因。 */
    private fun unavailableForStatus(
        code: Int,
        body: String,
    ): UpdateCheckUnavailableException {
        val reason =
            when {
                code == 403 -> UpdateCheckReason.RATE_LIMITED
                code in 500..599 -> UpdateCheckReason.SERVER_ERROR
                else -> UpdateCheckReason.UNKNOWN_STATUS
            }
        return unavailable(
            reason = reason,
            statusCode = code,
            detail = (apiMessage(body) ?: body).abbreviate(),
        )
    }

    /** 构造 B 类异常，并把真实原因留在应用内日志里（用户回报时能定位）。 */
    private fun unavailable(
        reason: UpdateCheckReason,
        statusCode: Int?,
        detail: String?,
        cause: Throwable? = null,
    ): UpdateCheckUnavailableException {
        val error = UpdateCheckUnavailableException(reason, statusCode, detail, cause)
        AppLog.w(TAG, error.message.orEmpty())
        return error
    }

    /**
     * 「无可用发布」静默态：与 `apiUrl` 为空时完全一致——版本号回填当前版本，
     * UpdateCard 的 `latest > current` 不成立，侧边栏什么都不显示。
     */
    private fun noReleaseInfo(): UpdateInfo =
        UpdateInfo(
            version = currentVersionName,
            publishedAt = "",
            changelog = "",
            downloads = emptyList(),
        )

    /** GitHub 错误响应形如 `{"message":"Not Found", …}` / `{"message":"API rate limit exceeded…", …}`。 */
    private fun apiMessage(body: String): String? =
        runCatching {
            json.decodeFromString<GitHubApiError>(body).message.ifBlank { null }
        }.getOrNull()

    /** 只留一行并截断：错误 body 可能是一整页 HTML 代理页，不适合原样进日志。 */
    private fun String?.abbreviate(): String? = this?.substringBefore('\n')?.take(MAX_DETAIL_LENGTH)?.ifBlank { null }

    /**
     * 解析 GitHub Releases `releases/latest` 响应为 [UpdateInfo]。
     * 兼容字段：tag_name / published_at / body / assets[].{name, browser_download_url, size}
     */
    private fun parseGitHubRelease(body: String): UpdateInfo {
        val release = json.decodeFromString<GitHubRelease>(body)
        val version = release.tag_name.removePrefix("v")
        return UpdateInfo(
            version = version,
            publishedAt = release.published_at.orEmpty(),
            changelog = release.body.orEmpty(),
            downloads =
                release.assets
                    .filter { it.browser_download_url.isNotBlank() }
                    .map { asset ->
                        UpdateDownload(
                            name = asset.name,
                            url = asset.browser_download_url,
                            size = asset.size?.toString().orEmpty(),
                        )
                    },
        )
    }

    @Serializable
    private data class GitHubRelease(
        val tag_name: String = "",
        val published_at: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String = "",
        val browser_download_url: String = "",
        val size: Long? = null,
    )

    /** GitHub API 的错误响应体（只有 `message` 这一项我们关心）。 */
    @Serializable
    private data class GitHubApiError(
        val message: String = "",
    )

    fun downloadUpdate(
        context: Context,
        download: UpdateDownload,
    ) {
        runCatching {
            val request =
                DownloadManager.Request(download.url.toUri()).apply {
                    // 设置下载时通知栏的标题和描述
                    setTitle(download.name)
                    setDescription(context.getString(R.string.update_download_description))
                    // 下载完成后通知栏可见
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    // 允许在移动网络和WiFi下下载
                    setAllowedNetworkTypes(
                        DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE,
                    )
                    // 设置文件保存路径
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                    // 允许下载的文件类型
                    setMimeType("application/vnd.android.package-archive")
                }
            // 获取系统的DownloadManager
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            // 你可以保存返回的downloadId到本地，以便后续查询下载进度或状态
        }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.toast_update_failed),
                Toast.LENGTH_SHORT,
            ).show()
            context.openUrl(download.url) // 跳转到下载页面
        }
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>,
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(
    val value: String,
) : Comparable<Version> {
    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) =
            if (hyphenIndex >= 0) {
                withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
            } else {
                withoutBuild to null
            }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(
            version1: String,
            version2: String,
        ): Int = Version(version1).compareTo(Version(version2))

        private fun comparePrerelease(
            a: List<String>,
            b: List<String>,
        ): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp =
                    when {
                        // 都是字：按数值比较
                        aNum != null && bNum != null -> aNum.compareTo(bNum)

                        // 数字优先级低于字符串
                        aNum != null -> -1

                        bNum != null -> 1

                        // 都是字符串：按字典序比较
                        else -> a[i].compareTo(b[i])
                    }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)

operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
