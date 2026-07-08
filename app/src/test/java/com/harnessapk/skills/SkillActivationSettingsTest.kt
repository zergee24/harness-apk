package com.harnessapk.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillActivationSettingsTest {
    @Test
    fun builtInSkillsDefaultToDisabled() {
        val settings = SkillActivationSettings.defaultFor(BundledSkills.defaults)

        assertFalse(settings.isEnabled("grill-me"))
        assertFalse(settings.isEnabled("superpowers"))
        assertEquals(emptySet<String>(), settings.enabledSkillIds)
    }

    @Test
    fun togglesKnownSkillWithoutEnablingOthers() {
        val settings = SkillActivationSettings.defaultFor(BundledSkills.defaults)
            .withSkillEnabled(
                skillId = "grill-me",
                enabled = true,
                availableSkills = BundledSkills.defaults,
            )

        assertTrue(settings.isEnabled("grill-me"))
        assertFalse(settings.isEnabled("superpowers"))
        assertEquals(setOf("grill-me"), settings.enabledSkillIds)
    }

    @Test
    fun ignoresUnknownSkillIds() {
        val settings = SkillActivationSettings(
            enabledSkillIds = setOf("unknown", "superpowers"),
        ).sanitizedFor(BundledSkills.defaults)

        assertFalse(settings.isEnabled("unknown"))
        assertTrue(settings.isEnabled("superpowers"))
        assertEquals(setOf("superpowers"), settings.enabledSkillIds)
    }
}
