package me.rerere.rikkahub.data.files

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.log.AppLog

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"

        /**
         * Upper bound on the size of a skill file we will read whole into memory and
         * cache. Skill bodies are prose/markdown meant to be inlined into the prompt;
         * a multi-megabyte file is either a mistake or an attempt to blow up the
         * context window / cache. Reads above this cap are refused (see [readCached]
         * and the `use_skill` read paths in SkillsTools) rather than silently loaded.
         */
        const val MAX_SKILL_FILE_BYTES: Long = 512L * 1024L

        /**
         * Sentinel marking a bundled skill whose on-disk copy was explicitly replaced
         * by the user (overwrite-install, import, or hand-authoring over a bundled
         * name — see [saveSkillFileBytesAtomically]). While this file exists,
         * [seedDefaultSkillsIfNeeded] never re-seeds the skill from assets, so the
         * user's version survives app upgrades instead of being clobbered by the
         * (possibly older) bundled copy. Cleared together with the directory by
         * [deleteSkill], and by [reinstallBundledSkill] ("恢复内置版").
         */
        private const val USER_OVERRIDE_SENTINEL = ".user-overridden"
    }

    /** Thrown by [readCached] when a skill file exceeds [MAX_SKILL_FILE_BYTES]. */
    class SkillFileTooLargeException(val lengthBytes: Long) :
        java.io.IOException("Skill file is $lengthBytes bytes, over the ${MAX_SKILL_FILE_BYTES}-byte cap")

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSkills(localeTag: String? = null): List<SkillMetadata> {
        val tag = localeTag ?: SkillLocale.currentLocaleTag()
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val defaultFile = dir.resolve("SKILL.md")
                if (!defaultFile.exists()) return@mapNotNull null
                // Prefer the locale-matched SKILL.<lang>.md; fall back to the default
                // file when the translation is missing or unparsable (a skill must
                // never disappear from the list just because one translation is bad).
                val localized = SkillPaths.resolveLocalizedSkillFile(dir, "SKILL.md", tag) ?: defaultFile
                parseSkillFile(localized, dir) ?: parseSkillFile(defaultFile, dir)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String, localeTag: String? = null): String? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        val skillFile = SkillPaths.resolveLocalizedSkillFile(
            skillDir, "SKILL.md", localeTag ?: SkillLocale.currentLocaleTag()
        ) ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(readCached(skillFile))
    }

    fun readSkillContent(skillName: String, localeTag: String? = null): String? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        val skillFile = SkillPaths.resolveLocalizedSkillFile(
            skillDir, "SKILL.md", localeTag ?: SkillLocale.currentLocaleTag()
        ) ?: return null
        if (!skillFile.exists()) return null
        return readCached(skillFile)
    }

    /**
     * Phase 16 audit fix — read-only accessor backing the `skill_get_content` LLM tool.
     *
     * Returns the parsed frontmatter (name / description / format / source label) plus the
     * markdown body and an optional args schema, or null when [skillName] does not resolve
     * to a skill directory with a readable SKILL.md. Reads from the same on-disk location
     * the install tools write to — no new persistence layer.
     *
     * `format` and `sourceLabel` are pulled from the optional `format:` / `source-url:`
     * frontmatter keys the install path writes; both are absent on hand-authored skills.
     * `argsSchema` is the optional `args_schema:` frontmatter key, parsed as a flat JSON
     * object string (the frontmatter parser is line-oriented, so a skill author declares it
     * on a single line); malformed or non-object values are dropped rather than surfaced.
     */
    fun getContent(skillName: String, localeTag: String? = null): SkillContent? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        val defaultFile = skillDir.resolve("SKILL.md")
        if (!defaultFile.exists()) return null
        val localized = SkillPaths.resolveLocalizedSkillFile(
            skillDir, "SKILL.md", localeTag ?: SkillLocale.currentLocaleTag()
        ) ?: return parseContentOrNull(defaultFile)
        return parseContentOrNull(localized) ?: parseContentOrNull(defaultFile)
    }

    private fun parseContentOrNull(skillFile: File): SkillContent? {
        if (!skillFile.exists()) return null
        val raw = runCatching { readCached(skillFile) }.getOrNull() ?: return null
        val frontmatter = SkillFrontmatterParser.parse(raw)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
        val argsSchema = frontmatter["args_schema"]?.takeIf { it.isNotBlank() }?.let { rawSchema ->
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(rawSchema)
            }.getOrNull() as? kotlinx.serialization.json.JsonObject
        }
        return SkillContent(
            name = name,
            description = description,
            format = frontmatter["format"]?.takeIf { it.isNotBlank() },
            sourceLabel = frontmatter["source-url"]?.takeIf { it.isNotBlank() },
            contentMd = SkillFrontmatterParser.extractBody(raw),
            argsSchema = argsSchema,
        )
    }

    /**
     * Read the body of an arbitrary file inside [skillName]'s directory (e.g. an
     * `auto_load_path` such as `SOUL.md`). Returns null if the skill or relative path
     * does not resolve, or the file does not exist. Backed by the same mtime-aware cache
     * used by [readSkillBody] / [readSkillContent], so the per-turn auto-load reads in
     * `SkillsTools.systemPrompt` are O(stat) on cache hit, not O(read).
     */
    fun readSkillFileCached(skillName: String, relativePath: String, localeTag: String? = null): String? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        // Same locale resolution as SKILL.md, so an auto_load_path like SOUL.md
        // resolves to SOUL.<lang>.md when the skill ships that translation.
        // Containment-checked by SkillPaths (an escaping relativePath fails here).
        val target = SkillPaths.resolveLocalizedSkillFile(
            skillDir, relativePath, localeTag ?: SkillLocale.currentLocaleTag()
        ) ?: return null
        if (!target.exists()) return null
        return readCached(target)
    }

    // ---- mtime-aware in-memory cache for SKILL.md and auto-loaded sidecars ----

    private data class CachedFile(val lastModifiedMs: Long, val length: Long, val text: String)

    private val bodyCache = java.util.concurrent.ConcurrentHashMap<String, CachedFile>()

    /**
     * Return [file]'s text, serving from [bodyCache] when both `lastModified` and
     * `length` match the cached values — invalidation falls out of the file system,
     * no explicit write hook needed (write paths necessarily change the mtime). Falls
     * back to a direct read on any cache miss / stat failure / anomalous length jump.
     */
    private fun readCached(file: File): String {
        val key = file.absolutePath
        val mtime = file.lastModified()
        val len = file.length()
        // Refuse oversized files before reading or caching them so a huge skill file
        // can never be loaded whole into memory or pinned in bodyCache. Checked on
        // every call (not just cache misses) so a file that grows past the cap after
        // a small initial read is caught on the next access.
        if (len > MAX_SKILL_FILE_BYTES) {
            bodyCache.remove(key)
            throw SkillFileTooLargeException(len)
        }
        val hit = bodyCache[key]
        if (hit != null && hit.lastModifiedMs == mtime && hit.length == len) {
            return hit.text
        }
        val text = file.readText()
        bodyCache[key] = CachedFile(mtime, len, text)
        return text
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        // 目录一删，里面的 .seeded 哨兵也没了，单看磁盘分不清「没装过」和「装过又删」。
        // 因此删除前先判断是否内置技能，并把这次删除记到技能目录之外。
        val isBundled = name in bundledSkillNames()
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            settingsStore.update { settings ->
                settings.copy(
                    deletedBundledSkills = deletedBundledSkillsAfterDelete(
                        current = settings.deletedBundledSkills,
                        deletedName = name,
                        isBundled = isBundled,
                    ),
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     * 清除 [name] 的删除记录并**无条件**从 assets 重新种入，供用户从技能目录重新安装一个内置技能。
     *
     * 先删掉目标目录再走种入流程：目录一删，`.user-overridden`（用户覆盖安装标记）与
     * `.core-bundled-hash`（hash 哨兵）随之消失，core 与非 core 分支都会重新播种，
     * 磁盘上得到的必然是 assets 里的当前版本。对「用户覆盖安装/手改过又后悔」的场景，
     * 这就是恢复内置版的入口。
     */
    suspend fun reinstallBundledSkill(name: String) {
        settingsStore.update { settings ->
            settings.copy(deletedBundledSkills = settings.deletedBundledSkills - name)
        }
        val targetDir = resolveSkillDir(name)
        if (targetDir != null && targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        seedDefaultSkillsIfNeeded()
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    /**
     * Doctor support, read-only: the skill names currently bundled in
     * `assets/default-skills/`, so the Doctor can tell a "bundled" skill directory
     * (seeded from assets) apart from a user-added one without duplicating the seeding
     * logic in [seedDefaultSkillsIfNeeded].
     */
    fun bundledSkillNames(): Set<String> = runCatching {
        context.assets.list("default-skills").orEmpty().toSet()
    }.getOrDefault(emptySet())

    /**
     * Doctor support, read-only: hash [skillName]'s currently-bundled assets the same
     * way [seedDefaultSkillsIfNeeded] does, so the Doctor can compare it against the
     * on-disk `.core-bundled-hash` sentinel without re-deriving the hashing algorithm.
     */
    fun bundledSkillAssetHash(skillName: String): String = computeBundledSkillHash("default-skills", skillName)

    /**
     * Phase 19E — skills shipped in `assets/skill-archive/<name>/`. These are NOT seeded
     * on first launch; the user installs them on demand from the store (or restores them
     * from the skill detail page). Keeping them out of `default-skills` keeps the seeded
     * set small while the files still ride along in the APK.
     */
    fun archivedSkillNames(): Set<String> =
        runCatching {
            context.assets.list("skill-archive").orEmpty().toSet()
        }.getOrDefault(emptySet())

    /**
     * Install (or restore) an archived bundled skill: wipe any on-disk copy and re-copy
     * `assets/skill-archive/<name>/` into place. Returns false when the archive has no
     * such skill or the copy fails. Unlike [reinstallBundledSkill] this never touches
     * the delete-recorded set — archived skills are not part of the seed loop.
     */
    fun installArchivedSkill(name: String): Boolean {
        if (name !in archivedSkillNames()) return false
        val targetDir = SkillPaths.resolveSkillDir(getSkillsDir(), name) ?: return false
        return try {
            if (targetDir.exists()) targetDir.deleteRecursively()
            copyAssetSkill("skill-archive", name, targetDir)
            true
        } catch (e: IOException) {
            // deleteRecursively/copyAssetSkill 仅声明 IOException
            AppLog.w(TAG, "installArchivedSkill: failed to install $name", e)
            false
        }
    }

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        return true
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            markUserOverrideIfBundled(skillName, targetDir)
            return true
        } catch (e: IOException) {
            // try 块内全部为 java.io 文件操作（writeBytes/renameTo/deleteRecursively），仅声明 IOException
            AppLog.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    /**
     * After an explicit full-skill save (install / import / hand-authoring over an
     * existing name), mark the directory as user-owned when the name collides with
     * a bundled skill. Without this marker the next [seedDefaultSkillsIfNeeded]
     * would treat the directory as ours and re-seed it from assets — which is how
     * overwrite-installs of bundled skills (e.g. a localized `agent-core`) used to
     * silently revert to the bundled English version on the next app launch.
     */
    private fun markUserOverrideIfBundled(skillName: String, targetDir: File) {
        if (skillName !in bundledSkillNames()) return
        runCatching {
            targetDir.resolve(USER_OVERRIDE_SENTINEL).writeText(System.currentTimeMillis().toString())
        }.onFailure {
            AppLog.w(TAG, "saveSkillFileBytesAtomically: cannot mark $skillName as user-overridden", it)
        }
    }

    /**
     * Copy any default skills bundled in `assets/default-skills/<name>/` into the user's
     * filesDir on first launch, but only if they have not already been installed before.
     * "Before" is tracked with a sentinel marker file inside each seeded skill so subsequent
     * launches skip the copy without checking individual file mtimes — and so the user can
     * delete a default skill and we will not silently re-install it.
     */
    fun seedDefaultSkillsIfNeeded() {
        val assetRoot = "default-skills"
        val assetMgr = context.assets
        // 用户显式删除过的内置技能不再种回（删除记录存放在技能目录之外，不随目录被删）
        val deletedBundledSkills = runCatching {
            settingsStore.settingsFlow.value.deletedBundledSkills
        }.getOrDefault(emptySet())
        val skillNames = try {
            assetMgr.list(assetRoot).orEmpty()
        } catch (e: IOException) {
            // AssetManager.list 仅声明 IOException
            AppLog.w(TAG, "seedDefaultSkillsIfNeeded: cannot list assets", e)
            return
        }
        for (skillName in skillNames) {
            if (skillName in deletedBundledSkills) continue
            val targetDir = SkillPaths.resolveSkillDir(getSkillsDir(), skillName) ?: continue

            // Read the bundled SKILL.md once to decide what to do.
            val bundledSkillMd = runCatching {
                assetMgr.open("$assetRoot/$skillName/SKILL.md").bufferedReader().use { it.readText() }
            }.getOrNull()
            val isCoreSkill = bundledSkillMd?.let { content ->
                SkillFrontmatterParser.parse(content)["auto_load"]?.equals("true", ignoreCase = true) == true
            } == true

            val sentinel = targetDir.resolve(".seeded")
            val coreVersionFile = targetDir.resolve(".core-bundled-hash")
            val userOverridden = targetDir.resolve(USER_OVERRIDE_SENTINEL).exists()
            // Core skills (auto_load=true) are unconditionally ours to re-seed when the
            // bundled content changes — except when the user explicitly replaced the
            // skill (userOverridden). Non-core skills are ours only while our `.seeded`
            // sentinel survives: a directory without it was created by the user
            // (manual install, overwrite-install, or a leftover from an older build)
            // and must never be touched.
            val ownedByUs = if (isCoreSkill) true else sentinel.exists()
            val bundledHash = if (isCoreSkill) computeBundledSkillHash(assetRoot, skillName) else ""
            val storedHash = if (isCoreSkill && coreVersionFile.exists()) coreVersionFile.readText().trim() else ""

            when (
                decideSeedAction(
                    ownedByUs = ownedByUs,
                    targetDirExists = targetDir.exists(),
                    targetDirNonEmpty = targetDir.listFiles()?.isNotEmpty() == true,
                    bundledHash = bundledHash,
                    storedHash = storedHash,
                    userOverridden = userOverridden,
                )
            ) {
                SeedDecision.SKIP -> continue
                SeedDecision.SEED -> {
                    try {
                        if (targetDir.exists()) targetDir.deleteRecursively()
                        copyAssetSkill(assetRoot, skillName, targetDir)
                        sentinel.writeText(System.currentTimeMillis().toString())
                        if (isCoreSkill) coreVersionFile.writeText(bundledHash)
                        AppLog.i(
                            TAG,
                            "seedDefaultSkillsIfNeeded: ${if (isCoreSkill) "re-seeded core skill" else "seeded"} $skillName"
                        )
                    } catch (e: IOException) {
                        // deleteRecursively/copyAssetSkill(assets 拷贝)/writeText 仅声明 IOException
                        AppLog.w(TAG, "seedDefaultSkillsIfNeeded: failed to seed $skillName", e)
                    }
                }
            }
        }
    }

    /**
     * Compute a stable hash over every file in the bundled skill (recursively, in sorted
     * order so the result is deterministic across runs). Used as the "version" of the
     * bundled core skill so we know when to re-seed the user's local copy.
     *
     * Asset-read failures are mixed into the digest as a stable marker rather than
     * silently skipped so a transient read failure can't change the hash on a later
     * successful read (which would trigger a spurious re-seed and clobber user edits).
     */
    private fun computeBundledSkillHash(assetRoot: String, skillName: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val readFailMarker = "<<read-failed>>".toByteArray()
        fun walk(path: String) {
            val children = context.assets.list(path).orEmpty().toList().sorted()
            for (child in children) {
                val childPath = "$path/$child"
                if (isAssetDirectory(childPath)) {
                    walk(childPath)
                } else {
                    md.update(child.toByteArray())  // include name so renames bump the hash
                    val ok = runCatching {
                        context.assets.open(childPath).use { input ->
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n <= 0) break
                                md.update(buf, 0, n)
                            }
                        }
                    }.isSuccess
                    if (!ok) {
                        AppLog.w(TAG, "computeBundledSkillHash: read failed for $childPath; marker mixed into digest")
                        md.update(readFailMarker)
                    }
                }
            }
        }
        walk("$assetRoot/$skillName")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAssetSkill(assetRoot: String, skillName: String, targetDir: File) {
        val assetMgr = context.assets
        targetDir.mkdirs()
        val children = assetMgr.list("$assetRoot/$skillName").orEmpty()
        for (child in children) {
            val source = "$assetRoot/$skillName/$child"
            if (isAssetDirectory(source)) {
                // Recurse into directories — including the genuinely-empty case where
                // listing returns []. The recursive call mkdirs the empty target
                // and exits cleanly without copying anything.
                copyAssetSkill("$assetRoot/$skillName", child, targetDir.resolve(child))
                continue
            }
            val outFile = targetDir.resolve(child)
            assetMgr.open(source).use { input ->
                outFile.outputStream().use { out -> input.copyTo(out) }
            }
        }
    }

    /**
     * Reliably distinguish an asset directory from an asset file. `AssetManager.list`
     * returns an empty array for both files and empty directories, which the previous
     * heuristic confused — any bundled skill shipping an empty placeholder subdir would
     * crash the seed when we later tried to `assetMgr.open` it as a file.
     *
     * The trick: try to open it as a file. Files succeed; directories throw. This is
     * the same approach AOSP's sample code recommends.
     */
    private fun isAssetDirectory(path: String): Boolean {
        return runCatching { context.assets.open(path).close() }.isFailure
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                autoLoad = frontmatter["auto_load"]?.equals("true", ignoreCase = true) == true,
                autoLoadPath = frontmatter["auto_load_path"]?.takeIf { it.isNotBlank() },
                skillDir = skillDir,
            )
        }.getOrElse {
            AppLog.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

internal enum class SeedDecision { SKIP, SEED }

/**
 * Pure decision for whether a bundled skill directory should be (re)written from assets.
 * Shared by both the core (`auto_load: true`) and non-core seeding branches of
 * [SkillManager.seedDefaultSkillsIfNeeded] so they cannot drift apart, and extracted out of
 * [SkillManager] itself so it is testable without a [android.content.Context] /
 * `AssetManager`.
 *
 * @param ownedByUs whether this directory is ours to overwrite: always `true` for core
 * skills (they are unconditionally ours to manage), or `sentinel.exists()` for non-core
 * skills (a directory that exists with no `.seeded` sentinel was never created by us and is
 * user-owned).
 * @param targetDirNonEmpty ignored when [ownedByUs] is `true`.
 * @param userOverridden the user explicitly replaced this skill (overwrite-install over a
 * bundled name — [SkillManager] marks `.user-overridden`). Always wins: never re-seed a
 * directory the user took ownership of. Only meaningful for core skills; non-core skills
 * never re-seed an existing user directory anyway.
 */
internal fun decideSeedAction(
    ownedByUs: Boolean,
    targetDirExists: Boolean,
    targetDirNonEmpty: Boolean,
    bundledHash: String,
    storedHash: String,
    userOverridden: Boolean = false,
): SeedDecision {
    if (userOverridden) {
        // Explicit user install beats the bundled version, even across APK upgrades.
        return SeedDecision.SKIP
    }
    if (!ownedByUs) {
        // Never touch a directory we did not create ourselves.
        return if (targetDirExists && targetDirNonEmpty) SeedDecision.SKIP else SeedDecision.SEED
    }
    return if (bundledHash == storedHash) SeedDecision.SKIP else SeedDecision.SEED
}

/**
 * 删除内置技能后应记录的名称集合。只有内置技能名会被记录：
 * 用户自建技能不参与种入流程，记录下来没有意义。
 */
internal fun deletedBundledSkillsAfterDelete(
    current: Set<String>,
    deletedName: String,
    isBundled: Boolean,
): Set<String> = if (isBundled) current + deletedName else current

/**
 * @property autoLoad If true, the skill's body (or [autoLoadPath] file if set) is injected
 * directly into the system prompt every turn instead of being lazy-loaded via the `use_skill`
 * tool. Use this for "core persona" skills like agent-core where the model needs the content
 * unconditionally — see SkillsTools.kt for where the injection happens. Frontmatter:
 * `auto_load: true`.
 * @property autoLoadPath Relative path inside the skill directory of the file to auto-load
 * (e.g. "SOUL.md"). Defaults to SKILL.md if not set. Frontmatter: `auto_load_path: SOUL.md`.
 */
data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    // NOTE: the `allowed-tools:` frontmatter key is intentionally NOT parsed. It was
    // never enforced anywhere (the tool set offered to the model is built from the
    // assistant's enabled tools/skills in ChatService, with no per-skill filtering),
    // so surfacing it as a field implied a sandbox boundary that did not exist. There
    // is no clean seam to enforce it: skills are loaded lazily mid-turn via use_skill,
    // while the tool list is fixed for the whole turn. Dropped rather than faked.
    val autoLoad: Boolean = false,
    val autoLoadPath: String? = null,
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

/**
 * Phase 16 audit fix — read-only view of a skill's SKILL.md, returned by
 * [SkillManager.getContent] and surfaced to the LLM via the `skill_get_content` tool.
 *
 * @property contentMd the markdown body with the YAML frontmatter stripped.
 * @property argsSchema optional structured arg description from the `args_schema:`
 * frontmatter key; null when the skill doesn't declare one.
 */
data class SkillContent(
    val name: String,
    val description: String,
    val format: String? = null,
    val sourceLabel: String? = null,
    val contentMd: String,
    val argsSchema: kotlinx.serialization.json.JsonObject? = null,
)

