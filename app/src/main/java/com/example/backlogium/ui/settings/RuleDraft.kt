package com.example.backlogium.ui.settings

import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig

/**
 * One editable numeric rule. Carrying the label, the minimum, and the reason together keeps a
 * field's validation next to the text that explains it, so the screen can render every control
 * from the same loop and the view model can validate without knowing about the UI.
 *
 * [minimum] is the smallest value the engine can *meaningfully* use, not the smallest it
 * survives. `Gamification` guards degenerate input (a `levelBase` of 0 would otherwise divide
 * by zero), but a crash-avoidance guard is not a UX: these are rejected at entry instead.
 */
enum class RuleField(
    val label: String,
    val minimum: Int,
    /** Shown inline when the entered value is below [minimum] or above [maximum]. */
    val rejection: String,
    /**
     * The largest value the engine can safely use, inclusive. [Int.MAX_VALUE] (no additional
     * ceiling) for a field that does not feed XP/level arithmetic, since no value of its own can
     * overflow it — see [errorFor].
     *
     * The XP-arithmetic fields below are capped with headroom under the widened `Long`
     * accumulation (auditfix-session-ledger-integrity, #114): assuming a maximal library of
     * 50,000 tracked games at up to 10,000,000 minutes each (~19 years of continuous play,
     * already far beyond any real library or lifetime of play), the worst-case total XP at these
     * ceilings stays roughly two orders of magnitude under `Long.MAX_VALUE`.
     */
    val maximum: Int = Int.MAX_VALUE,
    val advanced: Boolean,
) {
    QUEST_GOAL_MINUTES(
        label = "Daily quest goal (minutes)",
        minimum = 1,
        rejection = "Enter at least 1 minute — a goal of zero would mark every day complete.",
        advanced = false,
    ),
    STREAK_GRACE_DAYS(
        label = "Streak grace (days)",
        minimum = 0,
        rejection = "Enter 0 or more days.",
        advanced = false,
    ),
    XP_PER_MINUTE(
        label = "XP per minute",
        minimum = 1,
        rejection = "Enter a value from 1 to $XP_PER_MINUTE_MAXIMUM — a rate this large would " +
            "produce numbers the engine can't use safely.",
        maximum = XP_PER_MINUTE_MAXIMUM,
        advanced = true,
    ),
    LEVEL_BASE(
        label = "Level curve base",
        minimum = 1,
        rejection = "Enter a value from 1 to $LEVEL_BASE_MAXIMUM — the level curve is " +
            "undefined at zero and unusable this large.",
        maximum = LEVEL_BASE_MAXIMUM,
        advanced = true,
    ),
    COMMON_ACHIEVEMENT_XP(
        "Common achievement XP",
        0,
        ACHIEVEMENT_XP_REJECTION,
        maximum = ACHIEVEMENT_XP_MAXIMUM,
        advanced = true,
    ),
    UNCOMMON_ACHIEVEMENT_XP(
        "Uncommon achievement XP",
        0,
        ACHIEVEMENT_XP_REJECTION,
        maximum = ACHIEVEMENT_XP_MAXIMUM,
        advanced = true,
    ),
    RARE_ACHIEVEMENT_XP(
        "Rare achievement XP",
        0,
        ACHIEVEMENT_XP_REJECTION,
        maximum = ACHIEVEMENT_XP_MAXIMUM,
        advanced = true,
    ),
    EPIC_ACHIEVEMENT_XP(
        "Epic achievement XP",
        0,
        ACHIEVEMENT_XP_REJECTION,
        maximum = ACHIEVEMENT_XP_MAXIMUM,
        advanced = true,
    ),
    LEGENDARY_ACHIEVEMENT_XP(
        "Legendary achievement XP",
        0,
        ACHIEVEMENT_XP_REJECTION,
        maximum = ACHIEVEMENT_XP_MAXIMUM,
        advanced = true,
    ),
    ;

    /** Whether a change to this field alters daily-quest or streak evaluation. */
    val affectsQuestRules: Boolean
        get() = this == QUEST_GOAL_MINUTES || this == STREAK_GRACE_DAYS
}

private const val XP_PER_MINUTE_MAXIMUM = 100_000
private const val LEVEL_BASE_MAXIMUM = 1_000_000
private const val ACHIEVEMENT_XP_MAXIMUM = 1_000_000
private const val ACHIEVEMENT_XP_REJECTION = "Enter a value from 0 to $ACHIEVEMENT_XP_MAXIMUM."

/**
 * An in-progress edit of the rule configuration.
 *
 * Numeric fields are held as raw text rather than `Int` so a half-typed or invalid entry stays
 * on screen with a reason attached, instead of being silently coerced into something the user
 * did not ask for. [toConfig] is the only way out, and it returns null while anything is
 * invalid — so an unusable configuration can never reach the engine.
 */
data class RuleDraft(
    val values: Map<RuleField, String>,
    val questMode: QuestMode,
) {
    /** The inline reason this field is unusable, or null when it is fine. */
    fun errorFor(field: RuleField): String? {
        val entered = values[field].orEmpty()
        val parsed = entered.trim().toIntOrNull()
        return if (parsed == null || parsed < field.minimum || parsed > field.maximum) {
            field.rejection
        } else {
            null
        }
    }

    val invalidFields: List<RuleField> get() = RuleField.entries.filter { errorFor(it) != null }

    /**
     * The configuration this draft describes, or null while any field is invalid.
     *
     * Built by copying [base] — the stored config — so the two HowLongToBeat taper constants
     * this screen does not expose carry through untouched instead of being silently reasserted
     * to their defaults by an unrelated edit.
     */
    fun toConfig(base: RuleConfig = RuleConfig()): RuleConfig? {
        if (invalidFields.isNotEmpty()) return null
        fun value(field: RuleField) = values.getValue(field).trim().toInt()
        return base.copy(
            xpPerMinute = value(RuleField.XP_PER_MINUTE),
            levelBase = value(RuleField.LEVEL_BASE),
            questThresholdMin = value(RuleField.QUEST_GOAL_MINUTES),
            questMode = questMode,
            streakGraceDays = value(RuleField.STREAK_GRACE_DAYS),
            commonAchievementXp = value(RuleField.COMMON_ACHIEVEMENT_XP),
            uncommonAchievementXp = value(RuleField.UNCOMMON_ACHIEVEMENT_XP),
            rareAchievementXp = value(RuleField.RARE_ACHIEVEMENT_XP),
            epicAchievementXp = value(RuleField.EPIC_ACHIEVEMENT_XP),
            legendaryAchievementXp = value(RuleField.LEGENDARY_ACHIEVEMENT_XP),
        )
    }

    fun with(field: RuleField, text: String) = copy(values = values + (field to text))

    companion object {
        fun from(config: RuleConfig) = RuleDraft(
            values = mapOf(
                RuleField.QUEST_GOAL_MINUTES to config.questThresholdMin.toString(),
                RuleField.STREAK_GRACE_DAYS to config.streakGraceDays.toString(),
                RuleField.XP_PER_MINUTE to config.xpPerMinute.toString(),
                RuleField.LEVEL_BASE to config.levelBase.toString(),
                RuleField.COMMON_ACHIEVEMENT_XP to config.commonAchievementXp.toString(),
                RuleField.UNCOMMON_ACHIEVEMENT_XP to config.uncommonAchievementXp.toString(),
                RuleField.RARE_ACHIEVEMENT_XP to config.rareAchievementXp.toString(),
                RuleField.EPIC_ACHIEVEMENT_XP to config.epicAchievementXp.toString(),
                RuleField.LEGENDARY_ACHIEVEMENT_XP to config.legendaryAchievementXp.toString(),
            ),
            questMode = config.questMode,
        )
    }
}

/**
 * Which *kind* of rule changed between [this] and [candidate]. Quest-rule changes are explained
 * by their effect on streaks; advanced changes by their effect on XP and level. A single save
 * can be both.
 */
fun RuleConfig.changeKind(candidate: RuleConfig): RuleChangeKind = RuleChangeKind(
    questRules = questThresholdMin != candidate.questThresholdMin ||
        questMode != candidate.questMode ||
        streakGraceDays != candidate.streakGraceDays,
    advancedRules = xpPerMinute != candidate.xpPerMinute ||
        levelBase != candidate.levelBase ||
        commonAchievementXp != candidate.commonAchievementXp ||
        uncommonAchievementXp != candidate.uncommonAchievementXp ||
        rareAchievementXp != candidate.rareAchievementXp ||
        epicAchievementXp != candidate.epicAchievementXp ||
        legendaryAchievementXp != candidate.legendaryAchievementXp,
)

data class RuleChangeKind(val questRules: Boolean, val advancedRules: Boolean) {
    val any: Boolean get() = questRules || advancedRules
}
