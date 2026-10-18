package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.skills.CatalogEntry
import me.rerere.rikkahub.skills.MergedSkillCatalog
import me.rerere.rikkahub.skills.RemoteCatalogFetcher
import me.rerere.rikkahub.skills.SkillCatalog
import me.rerere.rikkahub.skills.SkillStoreRepository
import me.rerere.rikkahub.skills.SkillStoreSource
import me.rerere.rikkahub.skills.SkillStoreSourceStore
import me.rerere.rikkahub.skills.SkillUrlImporter
import me.rerere.rikkahub.skills.SkillZipError
import me.rerere.rikkahub.skills.SkillZipImporter
import me.rerere.rikkahub.skills.loadCatalogFromAssets
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.LinkedHashMap
import kotlin.collections.iterator
import me.rerere.rikkahub.data.log.AppLog

class SkillsVM(
    private val context: Context,
    private val skillManager: SkillManager,
    private val urlImporter: SkillUrlImporter,
    private val sourceStore: SkillStoreSourceStore,
    private val fetcher: RemoteCatalogFetcher,
) : ViewModel() {
    companion object {
        private const val TAG = "SkillsVM"
        private const val MAX_MD_BYTES = 1L * 1024 * 1024 // 1 MB cap on local .md
        private const val MAX_IMPORT_FILE_BYTES = 20L * 1024 * 1024
        private const val MAX_IMPORT_TOTAL_BYTES = 20L * 1024 * 1024
        private const val MAX_IMPORT_FILES = 200
    }

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    /**
     * Phase 19D — flow-derived snapshot of currently-installed skill names. The catalog
     * sheet observes this so the "Install" / "Installed" button state stays in sync as
     * the user (or LLM) installs / deletes skills.
     */
    val installedSkillNames =
        _skills
            .map { list -> list.mapTo(mutableSetOf()) { it.name } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Phase 19D — bundled catalog. Loaded lazily on first access. */
    private val localCatalog: SkillCatalog by lazy { loadCatalogFromAssets(context) }

    // ---- Phase 19E: store sources + merged catalog ----

    private val repository = SkillStoreRepository(sourceStore, fetcher)

    /** User-configured store sources (insertion order, enabled and disabled alike). */
    val storeSources: StateFlow<List<SkillStoreSource>> =
        sourceStore.sources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _mergedCatalog = MutableStateFlow(MergedSkillCatalog())
    val mergedCatalog: StateFlow<MergedSkillCatalog> = _mergedCatalog.asStateFlow()

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    init {
        loadSkills()
        refreshCatalog()
    }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    fun saveSkill(
        name: String,
        content: String,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(name)
            _skills.value = skillManager.listSkills()
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    fun importSkillFromGitHub(
        repoUrl: String,
        onResult: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info =
                    parseGitHubUrl(repoUrl) ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "Invalid GitHub repository URL") }
                        return@launch
                    }

                // Collect all files recursively via GitHub Contents API
                val files = mutableListOf<Pair<String, String>>() // relativePath -> downloadUrl
                val listed = listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files)
                if (!listed) {
                    withContext(Dispatchers.Main) { onResult(false, "Failed to list GitHub directory contents") }
                    return@launch
                }
                if (files.size > MAX_IMPORT_FILES) {
                    withContext(Dispatchers.Main) { onResult(false, "Skill contains too many files") }
                    return@launch
                }

                val skillMdEntry =
                    files.find { it.first == "SKILL.md" } ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "No SKILL.md found in the directory") }
                        return@launch
                    }

                val skillMdBytes =
                    downloadBytes(skillMdEntry.second) ?: run {
                        withContext(
                            Dispatchers.Main,
                        ) { onResult(false, "Failed to download SKILL.md — check the URL and your network") }
                        return@launch
                    }

                val skillMdContent = skillMdBytes.toString(Charsets.UTF_8)
                val frontmatter = SkillFrontmatterParser.parse(skillMdContent)
                val name = frontmatter["name"]
                if (name.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { onResult(false, "SKILL.md is missing the required 'name' field") }
                    return@launch
                }

                val fileContents = LinkedHashMap<String, ByteArray>()
                var totalBytes = 0L
                for ((relativePath, downloadUrl) in files) {
                    val content = downloadText(downloadUrl)
                    if (content == null) {
                        withContext(Dispatchers.Main) { onResult(false, "Failed to download file: $relativePath") }
                        return@launch
                    }
                    totalBytes += content.size
                    if (totalBytes > MAX_IMPORT_TOTAL_BYTES) {
                        withContext(Dispatchers.Main) { onResult(false, "Skill exceeds the 20 MB import limit") }
                        return@launch
                    }
                    fileContents[relativePath] = content
                }

                val saved = skillManager.saveSkillFileBytesAtomically(name, fileContents)
                if (!saved) {
                    withContext(Dispatchers.Main) { onResult(false, "Failed to save skill files") }
                    return@launch
                }

                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) { onResult(true, name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Phase 19C — install a skill from a local file picked via SAF (`OpenDocument`).
     *
     * Accepts:
     *  - `.md` / `.markdown` (or `text/markdown` MIME): read up to [MAX_MD_BYTES] of UTF-8
     *    text, then run through [SkillUrlImporter.importFromText] (same format detection
     *    + HTML guard + transcoder pipeline as the GitHub URL path).
     *  - `.zip` (or `application/zip` MIME): extract via [SkillZipImporter] to a temp dir
     *    inside the app's cache, locate the SKILL.md, copy every file inside that root
     *    into the SkillManager via [SkillManager.saveSkillFilesAtomically].
     *
     * On failure, [onResult] receives `false` + a localised-string-key (`skill_import_*`)
     * the UI looks up via stringResource. On success, [onResult] receives `true` + the
     * installed skill's name.
     */
    fun importFromLocalFile(
        uri: Uri,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val type = detectFileType(uri)
            try {
                val outcome: Pair<Boolean, String> =
                    when (type) {
                        LocalFileType.Markdown -> importLocalMarkdown(uri)
                        LocalFileType.Zip -> importLocalZip(uri)
                        LocalFileType.Unsupported -> false to "skill_import_unsupported_file_type"
                    }
                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) { onResult(outcome.first, outcome.second) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                AppLog.w(TAG, "importFromLocalFile failed for $uri", t)
                withContext(Dispatchers.Main) {
                    onResult(false, t.message ?: "skill_import_unsupported_file_type")
                }
            }
        }
    }

    /**
     * Phase 19D — install a skill from a [CatalogEntry].
     *
     * If the entry is `is_bundled = true`, this is a no-op (the skill is already on disk
     * via [SkillManager.seedDefaultSkillsIfNeeded]) and we return success immediately so
     * the UI flips its row to "Installed". Otherwise [CatalogEntry.sourceUrl] is fetched
     * via [SkillUrlImporter.importFromUrl] under a 30-second hard timeout — same surface
     * as the existing GitHub-URL import path, including HTML guard + format detector.
     */
    fun installFromCatalog(
        entry: CatalogEntry,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (entry.isBundled && entry.archived) {
                // Phase 19E — archived bundled skill: install on demand from assets.
                val ok = skillManager.installArchivedSkill(entry.name)
                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) {
                    onResult(ok, if (ok) entry.name else "skill_catalog_install_failed")
                }
                return@launch
            }
            if (entry.isBundled) {
                // 无条件从 assets 重装：清除删除记录，删掉磁盘上的旧目录
                // （含用户覆盖安装留下的 .user-overridden 标记）后重新种入，
                // 即「恢复内置版」。用户此前的本地改动会被替换掉。
                skillManager.reinstallBundledSkill(entry.name)
                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) { onResult(true, entry.name) }
                return@launch
            }
            val url = entry.sourceUrl
            if (url.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onResult(false, "skill_catalog_install_failed") }
                return@launch
            }
            val result =
                withTimeoutOrNull(30_000) {
                    urlImporter.importFromUrl(url)
                }
            val (ok, msg) =
                when (result) {
                    null -> false to "skill_catalog_install_failed"
                    is SkillUrlImporter.Result.Ok -> true to result.metadata.name
                    is SkillUrlImporter.Result.Err -> false to result.detail
                }
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) { onResult(ok, msg) }
        }
    }

    // ---- Phase 19E: store source management ----

    /**
     * Re-fetch every enabled source and rebuild the merged catalog. Safe to call
     * repeatedly (the sheet calls it on open and on pull-to-refresh); failures never
     * throw — they surface as per-source statuses in [mergedCatalog].
     */
    fun refreshCatalog() {
        viewModelScope.launch {
            AppLog.i(TAG, "refreshCatalog: start")
            _catalogLoading.value = true
            try {
                val merged = repository.load(localCatalog)
                AppLog.i(TAG, "refreshCatalog: loaded entries=${merged.entries.size} statuses=${merged.sourceStatuses}")
                _mergedCatalog.value = merged
                AppLog.i(TAG, "refreshCatalog: assigned")
            } catch (t: Throwable) {
                AppLog.e(TAG, "refreshCatalog: FAILED", t)
            } finally {
                _catalogLoading.value = false
            }
        }
    }

    /**
     * Add (or re-enable) a store source. Returns false when the URL is unusable or the
     * source cap is reached — the UI shows the matching error string.
     */
    fun addStoreSource(
        name: String,
        url: String,
        onResult: (success: Boolean) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val created = sourceStore.add(name, url)
            AppLog.i(TAG, "addStoreSource: url=$url created=${created?.id}")
            withContext(Dispatchers.Main) { onResult(created != null) }
            if (created != null) refreshCatalog()
        }
    }

    /** Remove a source and drop its cached catalog body. */
    fun removeStoreSource(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sourceStore.remove(id)
            fetcher.invalidateCache(id)
            refreshCatalog()
        }
    }

    /** Toggle a source; disabled sources contribute no entries. */
    fun setStoreSourceEnabled(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            sourceStore.setEnabled(id, enabled)
            refreshCatalog()
        }
    }

    /**
     * Restore the pristine built-in copy of a bundled skill: wipes the on-disk directory
     * (including any `.user-overridden` marker from a previous override-install) and
     * re-copies from assets. The user's local edits to that skill are replaced.
     *
     * Archived skills (shipped but not seeded) restore from `assets/skill-archive/`.
     */
    fun restoreBundledSkill(
        name: String,
        onResult: (success: Boolean) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok =
                if (name in skillManager.archivedSkillNames()) {
                    skillManager.installArchivedSkill(name)
                } else {
                    skillManager.reinstallBundledSkill(name)
                    true
                }
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** True when [name] is a bundled (restorable) skill in the local catalog. */
    fun isBundledSkillName(name: String): Boolean = localCatalog.skills.any { it.name == name && it.isBundled }

    fun isReadOnlyBuiltin(name: String): Boolean = skillManager.isBuiltinSkillName(name)

    private enum class LocalFileType { Markdown, Zip, Unsupported }
    private fun detectFileType(uri: Uri): LocalFileType {
        val mime = context.contentResolver.getType(uri)?.lowercase()
        if (mime != null) {
            if (mime == "text/markdown" || mime == "text/x-markdown" || mime == "text/plain") {
                return LocalFileType.Markdown
            }
            if (mime == "application/zip" || mime == "application/x-zip-compressed") {
                return LocalFileType.Zip
            }
        }
        // Fall back to the displayed filename. Some pickers (e.g. Files by Google) don't
        // attach a MIME type for `.md` and surface it as `application/octet-stream`.
        val name = queryDisplayName(uri)?.lowercase().orEmpty()
        return when {
            name.endsWith(".md") || name.endsWith(".markdown") -> LocalFileType.Markdown
            name.endsWith(".zip") -> LocalFileType.Zip
            else -> LocalFileType.Unsupported
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    private fun importLocalMarkdown(uri: Uri): Pair<Boolean, String> {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { input ->
                // Read up to MAX_MD_BYTES + 1 to detect overflow without materialising the
                // whole stream blindly.
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_MD_BYTES) {
                        // Use the markdown-specific cap error, not the zip cap key.
                        return false to "skill_import_md_too_large"
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return false to "skill_import_unsupported_file_type"
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            return false to "skill_import_empty_file"
        }
        val sourceLabel = queryDisplayName(uri) ?: "local_file"
        val result = urlImporter.importFromText(text, sourceLabel = sourceLabel)
        return when (result) {
            is SkillUrlImporter.Result.Ok -> true to result.metadata.name
            is SkillUrlImporter.Result.Err -> false to result.detail
        }
    }

    private fun importLocalZip(uri: Uri): Pair<Boolean, String> {
        // Extract into a uniquely-named temp dir under cache so we never collide with
        // another import-in-flight. We delete it on success or failure.
        val tempRoot = File(context.cacheDir, "skill-zip-import")
        tempRoot.mkdirs()
        val workDir = Files.createTempDirectory(tempRoot.toPath(), "extract-").toFile()
        try {
            val skillRoot =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    SkillZipImporter.extractZipToDir(input, workDir)
                } ?: return false to "skill_import_unsupported_file_type"
            if (skillRoot.isFailure) {
                val err = skillRoot.exceptionOrNull()
                val key =
                    when (err) {
                        is SkillZipError.MissingSkillMd -> "skill_import_missing_skill_md"
                        is SkillZipError.PathTraversal -> "skill_import_path_traversal"
                        is SkillZipError.TooLarge -> "skill_import_zip_too_large"
                        else -> "skill_import_unsupported_file_type"
                    }
                return false to key
            }
            val rootDir = skillRoot.getOrThrow()
            val skillMd =
                rootDir.resolve("SKILL.md").takeIf { it.exists() }
                    ?: rootDir.listFiles()?.firstOrNull { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                    ?: return false to "skill_import_missing_skill_md"
            val frontmatter = SkillFrontmatterParser.parse(skillMd.readText())
            val skillName =
                frontmatter["name"]?.takeIf { it.isNotBlank() }
                    ?: return false to "skill_import_missing_skill_md"
            // Collect every file into a relativePath -> content map, then atomic-save.
            val files = LinkedHashMap<String, String>()
            rootDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                files[rel] = f.readText()
            }
            val saved = skillManager.saveSkillFilesAtomically(skillName, files)
            return if (saved) true to skillName else false to "skill_import_unsupported_file_type"
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadText(apiUrl) ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl =
                        item.optString("download_url").takeIf { it.isNotBlank() }
                            ?: return false
                    result.add(relativePath to downloadUrl)
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    private fun downloadText(url: String): String? = downloadBytes(url)?.toString(Charsets.UTF_8)

    private fun downloadBytes(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "RikkaHub")
            if (connection.responseCode !in 200..299) return null
            return connection.inputStream.use { readLimited(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** 读取全部字节；超过 [MAX_IMPORT_FILE_BYTES] 时返回 null，由调用方按失败处理。 */
    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_IMPORT_FILE_BYTES) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
