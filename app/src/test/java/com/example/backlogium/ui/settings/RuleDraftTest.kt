package com.example.backlogium.ui.settings

import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Input validation and change classification for the Settings rule controls — the layer that
 * decides what may reach the engine and how a pending change is explained.
 */
class RuleDraftTest {

    @Test
    fun draftRoundTripsEveryConfiguredRule() {
        // Including the five per-tier achievement awards: a field that does not round-trip here
        // would appear editable and silently revert.
        val config = RuleConfig(
            xpPerMinute = 3,
            levelBase = 75,
            questThresholdMin = 45,
            questMode = QuestMode.GOAL_ONLY,
            streakGraceDays = 2,
            commonAchievementXp = 1,
            uncommonAchievementXp = 2,
            rareAchievementXp = 3,
            epicAchievementXp = 4,
            legendaryAchievementXp = 5,
        )
        assertEquals(config, RuleDraft.from(config).toConfig())
    }

    @Test
    fun nonPositiveLevelBaseIsRejectedWithAReason() {
        val draft = RuleDraft.from(RuleConfig()).with(RuleField.LEVEL_BASE, "0")

        assertEquals(RuleField.LEVEL_BASE.rejection, draft.errorFor(RuleField.LEVEL_BASE))
        // Nothing usable comes out of an invalid draft, so the engine's degenerate-input guard
        // is never what stands between the user and a divide-by-zero level curve.
        assertNull(draft.toConfig())
    }

    @Test
    fun nonPositiveXpPerMinuteIsRejectedWithAReason() {
        val draft = RuleDraft.from(RuleConfig()).with(RuleField.XP_PER_MINUTE, "-1")

        assertEquals(RuleField.XP_PER_MINUTE.rejection, draft.errorFor(RuleField.XP_PER_MINUTE))
        assertNull(draft.toConfig())
    }

    // --- auditfix-session-ledger-integrity #114: rule configuration is bounded above too ---

    @Test
    fun extremeXpPerMinuteIsRejected_theSpecificValueTheAuditReported() {
        val draft = RuleDraft.from(RuleConfig())
            .with(RuleField.XP_PER_MINUTE, "${Int.MAX_VALUE}")

        assertEquals(RuleField.XP_PER_MINUTE.rejection, draft.errorFor(RuleField.XP_PER_MINUTE))
        assertNull(draft.toConfig())
    }

    @Test
    fun valueAboveTheCeilingIsRejectedForEveryXpArithmeticField() {
        listOf(
            RuleField.XP_PER_MINUTE,
            RuleField.LEVEL_BASE,
            RuleField.COMMON_ACHIEVEMENT_XP,
            RuleField.UNCOMMON_ACHIEVEMENT_XP,
            RuleField.RARE_ACHIEVEMENT_XP,
            RuleField.EPIC_ACHIEVEMENT_XP,
            RuleField.LEGENDARY_ACHIEVEMENT_XP,
        ).forEach { field ->
            val atCeiling = RuleDraft.from(RuleConfig()).with(field, "${field.maximum}")
            assertNull("$field should accept its own ceiling", atCeiling.errorFor(field))

            val aboveCeiling = RuleDraft.from(RuleConfig()).with(field, "${field.maximum + 1}")
            assertEquals(
                "$field should reject one past its ceiling",
                field.rejection,
                aboveCeiling.errorFor(field),
            )
        }
    }

    @Test
    fun theCeilingDoesNotInterfereWithOrdinaryTuning() {
        // "high enough not to interfere with any plausible tuning choice" — a generous but
        // ordinary rate is accepted.
        val draft = RuleDraft.from(RuleConfig()).with(RuleField.XP_PER_MINUTE, "50")

        assertNull(draft.errorFor(RuleField.XP_PER_MINUTE))
        assertNotNull(draft.toConfig())
    }

    @Test
    fun aConfigStoredBeforeTheCeilingExistedLoadsWithoutCrashing() {
        // A value above the new ceiling, already stored from before this change shipped, must not
        // make the screen unusable: the draft loads, and only the offending field is flagged —
        // exactly like any other invalid entry, editable rather than fatal.
        val stale = RuleConfig(xpPerMinute = Int.MAX_VALUE)
        val draft = RuleDraft.from(stale)

        assertEquals("${Int.MAX_VALUE}", draft.values.getValue(RuleField.XP_PER_MINUTE))
        assertEquals(RuleField.XP_PER_MINUTE.rejection, draft.errorFor(RuleField.XP_PER_MINUTE))
        assertNull(draft.toConfig())

        // The screen remains usable: correcting just the flagged field makes the draft saveable.
        val corrected = draft.with(RuleField.XP_PER_MINUTE, "50")
        assertNull(corrected.errorFor(RuleField.XP_PER_MINUTE))
        assertNotNull(corrected.toConfig())
    }

    @Test
    fun fieldsWithoutXpArithmeticStayUnbounded() {
        // QUEST_GOAL_MINUTES and STREAK_GRACE_DAYS feed no multiplication the engine could
        // overflow, so they carry no additional ceiling beyond Int.MAX_VALUE.
        assertEquals(Int.MAX_VALUE, RuleField.QUEST_GOAL_MINUTES.maximum)
        assertEquals(Int.MAX_VALUE, RuleField.STREAK_GRACE_DAYS.maximum)
    }

    @Test
    fun blankAndNonNumericEntriesAreRejected() {
        RuleField.entries.forEach { field ->
            val blank = RuleDraft.from(RuleConfig()).with(field, "")
            val words = RuleDraft.from(RuleConfig()).with(field, "lots")
            assertNotNull("$field should reject a blank entry", blank.errorFor(field))
            assertNotNull("$field should reject a non-numeric entry", words.errorFor(field))
        }
    }

    @Test
    fun zeroIsAcceptedWhereItIsMeaningful() {
        // No grace and zero-XP awards are legitimate configurations; only values the engine
        // cannot use are rejected.
        val draft = RuleDraft.from(RuleConfig())
            .with(RuleField.STREAK_GRACE_DAYS, "0")
            .with(RuleField.COMMON_ACHIEVEMENT_XP, "0")

        assertNull(draft.errorFor(RuleField.STREAK_GRACE_DAYS))
        assertNull(draft.errorFor(RuleField.COMMON_ACHIEVEMENT_XP))
        assertNotNull(draft.toConfig())
    }

    @Test
    fun advancedFieldsArePartitionedFromThePrimaryOnes() {
        val primary = RuleField.entries.filterNot { it.advanced }
        assertEquals(
            listOf(RuleField.QUEST_GOAL_MINUTES, RuleField.STREAK_GRACE_DAYS),
            primary,
        )
    }

    @Test
    fun questRuleChangesAreClassifiedSeparatelyFromAdvancedOnes() {
        val saved = RuleConfig()

        val questOnly = saved.changeKind(saved.copy(questThresholdMin = 60))
        assertTrue(questOnly.questRules)
        assertFalse(questOnly.advancedRules)

        val modeOnly = saved.changeKind(saved.copy(questMode = QuestMode.GOAL_ONLY))
        assertTrue(modeOnly.questRules)

        val advancedOnly = saved.changeKind(saved.copy(legendaryAchievementXp = 500))
        assertFalse(advancedOnly.questRules)
        assertTrue(advancedOnly.advancedRules)

        val both = saved.changeKind(saved.copy(streakGraceDays = 1, xpPerMinute = 2))
        assertTrue(both.questRules)
        assertTrue(both.advancedRules)

        assertFalse(saved.changeKind(saved).any)
    }

    @Test
    fun uiStateIsOnlyDirtyForAValidChange() {
        val saved = RuleConfig()
        val base = SettingsUiState(loading = false, savedConfig = saved, draft = RuleDraft.from(saved))

        assertFalse("an untouched draft is not a change", base.dirty)

        val changed = base.copy(draft = base.draft.with(RuleField.QUEST_GOAL_MINUTES, "60"))
        assertTrue(changed.dirty)
        assertEquals(60, changed.candidate?.questThresholdMin)

        val invalid = base.copy(draft = base.draft.with(RuleField.LEVEL_BASE, "0"))
        assertFalse("an invalid draft is never saveable", invalid.dirty)
        assertTrue(invalid.hasInvalidField)
        assertNull(invalid.candidate)
    }

    @Test
    fun advancedSectionIsCollapsedByDefault() {
        assertFalse(SettingsUiState().advancedExpanded)
    }
}
