package com.harnessapk.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledSkillsTest {
    @Test
    fun bundledSkillsStartWithGrillMeAndSuperpowersAsReadOnlySkills() {
        val skills = BundledSkills.defaults

        assertEquals(listOf("grill-me", "superpowers"), skills.map { it.id })
        assertTrue(skills.all { it.kind == BundledSkillKind.SKILL })
        assertTrue(skills.all { it.isReadOnly })
    }

    @Test
    fun bundledSkillsRememberSourceAndAssetPaths() {
        assertEquals(
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
            BundledSkills.defaults.first { it.id == "grill-me" },
        )
        assertEquals(
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
            BundledSkills.defaults.first { it.id == "superpowers" },
        )
    }
}
