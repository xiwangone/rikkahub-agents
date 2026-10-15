package me.rerere.rikkahub.data.files

import java.io.File

internal object SkillPaths {
    fun resolveSkillDir(skillsRoot: File, skillName: String): File? {
        if (skillName.isBlank()) return null
        if (skillName == "." || skillName == "..") return null
        if (skillName.contains('/') || skillName.contains('\\')) return null

        val canonicalRoot = skillsRoot.canonicalFile
        val canonicalDir = canonicalRoot.resolve(skillName).canonicalFile
        val parent = canonicalDir.parentFile ?: return null

        if (parent != canonicalRoot) return null
        if (!canonicalDir.isSameOrInside(canonicalRoot)) return null

        return canonicalDir
    }

    fun resolveSkillFile(skillDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null

        val canonicalSkillDir = skillDir.canonicalFile
        val canonicalTarget = canonicalSkillDir.resolve(relativePath).canonicalFile

        return canonicalTarget.takeIf { it.isSameOrInside(canonicalSkillDir) }
    }

    /**
     * Resolve [fileName] inside [skillDir], preferring the locale-matched variant
     * (`SKILL.zh.md` for `SKILL.md` under a zh locale — see [SkillLocale]).
     * Containment-checked exactly like [resolveSkillFile]: a candidate that would
     * escape the skill directory fails the whole resolution (null).
     *
     * Returns the first existing candidate, or the default [fileName] path (which
     * may not exist — callers check) when no localized variant is present.
     */
    fun resolveLocalizedSkillFile(skillDir: File, fileName: String, localeTag: String?): File? {
        for (candidate in SkillLocale.localizedCandidates(fileName, localeTag)) {
            val resolved = resolveSkillFile(skillDir, candidate) ?: return null
            if (resolved.isFile) return resolved
        }
        return resolveSkillFile(skillDir, fileName)
    }

    private fun File.isSameOrInside(root: File): Boolean {
        val rootPath = root.canonicalFile.path
        val currentPath = canonicalFile.path
        return currentPath == rootPath || currentPath.startsWith(rootPath + File.separator)
    }
}
