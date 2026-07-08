package com.harnessapk.skills

object BundledSkills {
    val defaults = listOf(
        BundledSkill(
            id = "grill-me",
            name = "Grill Me",
            summary = "用高压提问打磨方案、需求和实现边界。",
            kind = BundledSkillKind.SKILL,
            assetPath = "skills/grill-me/SKILL.md",
            sourceUrl = "https://github.com/mattpocock/skills/tree/main/skills/productivity/grill-me",
            sourceCommit = "4691566bbb2744bf56505531f8050102f02a7a12",
            isReadOnly = true,
        ),
        BundledSkill(
            id = "superpowers",
            name = "Superpowers",
            summary = "一套面向编码智能体的技能化研发工作流。",
            kind = BundledSkillKind.SKILL,
            assetPath = "skills/superpowers/README.md",
            sourceUrl = "https://github.com/obra/superpowers",
            sourceCommit = "d884ae04edebef577e82ff7c4e143debd0bbec99",
            isReadOnly = true,
        ),
    )
}

data class BundledSkill(
    val id: String,
    val name: String,
    val summary: String,
    val kind: BundledSkillKind,
    val assetPath: String,
    val sourceUrl: String,
    val sourceCommit: String,
    val isReadOnly: Boolean,
)

enum class BundledSkillKind { SKILL, PLUGIN }

data class SkillActivationSettings(
    val enabledSkillIds: Set<String> = emptySet(),
) {
    fun isEnabled(skillId: String): Boolean = skillId in enabledSkillIds

    fun withSkillEnabled(
        skillId: String,
        enabled: Boolean,
        availableSkills: List<BundledSkill>,
    ): SkillActivationSettings {
        val availableIds = availableSkills.mapTo(mutableSetOf()) { it.id }
        if (skillId !in availableIds) return sanitizedFor(availableSkills)
        val nextIds = if (enabled) {
            enabledSkillIds + skillId
        } else {
            enabledSkillIds - skillId
        }
        return SkillActivationSettings(nextIds).sanitizedFor(availableSkills)
    }

    fun sanitizedFor(availableSkills: List<BundledSkill>): SkillActivationSettings {
        val availableIds = availableSkills.mapTo(mutableSetOf()) { it.id }
        return SkillActivationSettings(enabledSkillIds.filterTo(mutableSetOf()) { it in availableIds })
    }

    companion object {
        fun defaultFor(availableSkills: List<BundledSkill>): SkillActivationSettings =
            SkillActivationSettings().sanitizedFor(availableSkills)
    }
}
