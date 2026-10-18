package me.rerere.rikkahub.ui.pages.extensions.skills

import me.rerere.rikkahub.data.files.SkillMetadata

/** Case-insensitive local filter over skill display name and description. */
internal fun filterSkills(skills: List<SkillMetadata>, query: String): List<SkillMetadata> {
    val needle = query.trim()
    if (needle.isEmpty()) return skills
    return skills.filter { skill ->
        skill.name.contains(needle, ignoreCase = true) ||
            skill.description.contains(needle, ignoreCase = true)
    }
}
