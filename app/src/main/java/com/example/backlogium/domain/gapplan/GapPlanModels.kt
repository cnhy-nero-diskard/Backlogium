package com.example.backlogium.domain.gapplan

import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.PersonalPaceProfile
import java.time.LocalDate

/**
 * Which HowLongToBeat run the player is planning. Two intents, not four: the request is "what can
 * I finish before this comes out", and the two lengths that answer it are the shortest credible
 * one and the longest.
 */
enum class GapPlanIntent {
    STORY,
    COMPLETIONIST,
}

/**
 * The intent's HLTB basis. Fixed here once so the planner, the explanations, and the collection
 * the player eventually accepts all measure the same thing — a plan built on Main Story that
 * became a Completionist collection would silently triple its own workload.
 */
fun GapPlanIntent.timeBasis(): CollectionTimeBasis = when (this) {
    GapPlanIntent.STORY -> CollectionTimeBasis.MAIN_STORY
    GapPlanIntent.COMPLETIONIST -> CollectionTimeBasis.COMPLETIONIST
}

/**
 * Where the plan's capacity figure came from. This is not cosmetic: a Personal Pace forecast is a
 * claim the app is entitled to make from tracked history, and a manual budget is the player's own
 * guess. Presenting the second as the first would attribute a confidence the data cannot support,
 * so the provenance travels with the snapshot all the way to the card.
 */
enum class CapacityProvenance {
    PERSONAL_PACE,
    MANUAL,
}

/**
 * The three planning intensities, as whole percents of the request's full capacity.
 *
 * They are *planning* intensities, not confidence intervals, and nothing here should be read as a
 * probability. Inflating every HLTB estimate and also shrinking capacity was rejected: two
 * arbitrary safety adjustments are hard to explain and compound into excessive conservatism. One
 * visible utilization choice, made by the player, is the honest version of the same idea.
 *
 * The percent is a **target**, not merely a ceiling — see [GapPlanSelection]. Under a ceiling
 * alone, "fits within 70%" and "fits within 100%" are both satisfied by the same two-hour game,
 * so all three tiers could offer the same length and choosing between them would say nothing.
 */
enum class PlanIntensity(val percent: Int) {
    RELAXED(70),
    BALANCED(85),
    FULL(100),
}

/**
 * This intensity's share of capacity, in whole minutes.
 *
 * Integer arithmetic rather than `floor(minutes * 0.70)` deliberately. The two agree
 * mathematically, but binary floating point does not represent 0.70 exactly, so the double form
 * can land a hair below an exact boundary and floor to one minute less. A pick is reproducible
 * from its inputs and its seed, and that property would be quietly conditional on rounding if the
 * arithmetic deciding shares went through doubles.
 */
fun PlanIntensity.budgetMinutes(fullCapacityMinutes: Int): Int =
    (fullCapacityMinutes.toLong().coerceAtLeast(0L) * percent / 100).toInt()

/** Why a request cannot be planned yet. Each maps to something the player can actually fix. */
enum class GapPlanRequestError {
    /** Nothing to name the plan or the collection after. */
    MISSING_TITLE,

    /** Today or earlier: there is no gap to plan for. */
    TARGET_DATE_NOT_FUTURE,

    /**
     * Beyond [PersonalPaceProfile.DEFAULT_FIT_HORIZON_DAYS]. The pace forecast walks the range one
     * day at a time, so an unbounded target date is both an unbounded loop and a meaningless plan.
     * The bound is the horizon the existing fit derivation already applies, not a new policy.
     */
    TARGET_DATE_BEYOND_HORIZON,

    /** Personal Pace is learning and no positive one-off budget was supplied. */
    MISSING_MANUAL_BUDGET,
}

/**
 * The player's inputs. Plain values only — no clock, no repository, no Android — so the whole
 * engine is exercisable from a JVM test with an injected date.
 *
 * [includeUnplayed] and [includeStarted] both default to true and are request-scoped: switching
 * one off narrows this plan and writes nothing to the library.
 *
 * [manualTotalHours] is read only when Personal Pace is learning. It is never stored as a pace
 * preference — a one-off answer to one question is not a durable fact about the player.
 */
data class GapPlanRequest(
    val anticipatedTitle: String,
    val targetDate: LocalDate,
    val intent: GapPlanIntent,
    val includeUnplayed: Boolean = true,
    val includeStarted: Boolean = true,
    val manualTotalHours: Int? = null,
) {
    /**
     * Validates everything that can be decided from the request and the date alone. The manual
     * budget is deliberately not checked here: whether one is *required* depends on the pace
     * profile, which is not part of the request — see [resolveCapacity].
     */
    fun validate(today: LocalDate): GapPlanRequestError? = when {
        anticipatedTitle.isBlank() -> GapPlanRequestError.MISSING_TITLE
        !targetDate.isAfter(today) -> GapPlanRequestError.TARGET_DATE_NOT_FUTURE
        targetDate.isAfter(today.plusDays(HORIZON_DAYS)) ->
            GapPlanRequestError.TARGET_DATE_BEYOND_HORIZON
        else -> null
    }

    companion object {
        /** Reuses the pace engine's own horizon rather than inventing a second one. */
        const val HORIZON_DAYS: Long = PersonalPaceProfile.DEFAULT_FIT_HORIZON_DAYS.toLong()
    }
}

/** The resolved capacity for one request: how many minutes, and on whose authority. */
data class GapPlanCapacity(
    val fullCapacityMinutes: Int,
    val provenance: CapacityProvenance,
    /** Tomorrow through the target date inclusive — the window the figure describes. */
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/**
 * Resolves the request's full capacity from tomorrow through the target date inclusive.
 *
 * Planning starts *tomorrow*, not today: today is partly spent, and a forecast that includes it
 * would quietly promise time that has already gone.
 *
 * [today] is passed in rather than read from a clock because the caller must supply a freshly read
 * date. `PersonalPaceRepository` is a `@Singleton` that captures its own `today` at construction,
 * so a long-lived process that crosses local midnight would otherwise compute "tomorrow" from a
 * stale date and forecast one day too many.
 *
 * A learning profile cannot support the definitive feasibility language the reliable path uses, so
 * it demands a positive one-off budget instead and records [CapacityProvenance.MANUAL].
 */
fun GapPlanRequest.resolveCapacity(
    profile: PersonalPaceProfile,
    today: LocalDate,
): Result<GapPlanCapacity> {
    validate(today)?.let { return Result.failure(GapPlanRequestException(it)) }
    val startDate = today.plusDays(1)
    if (profile.isReliable) {
        val minutes = profile.forecast(startDate, targetDate).expectedGamingMinutes
        return Result.success(
            GapPlanCapacity(
                // Floored: a fractional minute of expected capacity is not a minute of play.
                fullCapacityMinutes = minutes.coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt(),
                provenance = CapacityProvenance.PERSONAL_PACE,
                startDate = startDate,
                endDate = targetDate,
            ),
        )
    }
    val hours = manualTotalHours
    if (hours == null || hours <= 0) {
        return Result.failure(GapPlanRequestException(GapPlanRequestError.MISSING_MANUAL_BUDGET))
    }
    return Result.success(
        GapPlanCapacity(
            fullCapacityMinutes = (hours.toLong() * MINUTES_PER_HOUR)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            provenance = CapacityProvenance.MANUAL,
            startDate = startDate,
            endDate = targetDate,
        ),
    )
}

/** Carries a [GapPlanRequestError] out of a `Result`, so callers branch on the enum, not a string. */
class GapPlanRequestException(val error: GapPlanRequestError) :
    IllegalArgumentException(error.name)

/**
 * One fact a pick's card states, so the player can judge the suggestion themselves.
 *
 * These are **presented**, never used to choose. That reversal is what the name records: they were
 * `GapPlanReason` while a weighted composite ranked candidates by them, and calling them reasons
 * now would claim they justified a selection that is in fact uniform. A recommender that ranks by
 * an opaque score is asking to be trusted; one that offers a fitting game and states its rating,
 * its volume, and how much of it is left is asking to be checked.
 *
 * Every case carries the numbers it was derived from rather than a phrase, so the surface states
 * something checkable. A composite is deliberately not representable here: there is no `Score`
 * case to reach for, and nothing left that would populate one.
 */
sealed interface GapPlanFact {
    /** Steam's own description plus the volume behind it — never a rating the app invented. */
    data class Reviews(
        val description: String,
        val positive: Int,
        val total: Int,
    ) : GapPlanFact

    /** The player's own recent history, named by the genre it matched. */
    data class GenreAffinity(val genreLabel: String) : GapPlanFact

    /** Existing progress through the selected basis, as played and total minutes. */
    data class Progress(val playedMinutes: Int, val estimateMinutes: Int) : GapPlanFact

    /** A live, non-persisted concurrent-player count. Only ever added after finalization. */
    data class PlayingNow(val players: Int) : GapPlanFact
}

/**
 * One eligible game, with everything a card needs to present it.
 *
 * [remainingMinutes], [genreLabels], and [source] are structural rather than entries in [facts],
 * because the card renders each of them in its own place — the remaining time as the pick's
 * subtitle, the genres as their own line, family sharing as a label. A fact case that duplicated
 * one of them would only let the card print the same sentence twice.
 */
data class GapPlanCandidate(
    val appId: Long,
    val name: String,
    val source: GameSource,
    /** `max(selected estimate - truthful displayed playtime, 0)`, always positive here. */
    val remainingMinutes: Int,
    /** The selected-basis estimate this candidate's remaining work was measured against. */
    val estimateMinutes: Int,
    val playedMinutes: Int,
    /** Broad Store genre labels, already resolved. Empty means unknown, and is shown as nothing. */
    val genreLabels: List<String>,
    /** True only when cached participation categories say so; unknown categories are not true. */
    val multiplayer: Boolean,
    val facts: List<GapPlanFact>,
)

/**
 * One tier's offer: its share of the request's capacity, and the single game drawn for it.
 *
 * [game] is nullable because "nothing in your library fits this much time" is a real answer. The
 * tier says so and the other two still present theirs, rather than the whole result failing.
 *
 * [unusedMinutes] is measured against **this tier's own share**, not the request's full capacity.
 * Both figures are presented, because a Relaxed card showing only "4,200 available" would conceal
 * the 1,800 minutes the 70% intensity deliberately withheld — the opposite of what choosing an
 * intensity is meant to offer.
 */
data class GapPlanPick(
    val intensity: PlanIntensity,
    val budgetMinutes: Int,
    val game: GapPlanCandidate?,
) {
    val isEmpty: Boolean get() = game == null
    val plannedMinutes: Int get() = game?.remainingMinutes ?: 0
    val unusedMinutes: Int get() = (budgetMinutes - plannedMinutes).coerceAtLeast(0)
}

/**
 * What the library could not contribute, stated rather than hidden.
 *
 * A game with no estimate for the selected basis is *unknown*, never zero work — treating it as
 * zero would make it look free and offer it first. Counting it here lets the result say the pool
 * was smaller than the library, instead of implying full coverage.
 *
 * [withCachedReviews] and [withKnownGenres] no longer describe how well informed a *ranking* was,
 * because nothing is ranked. They describe how much a card will be able to show.
 */
data class GapPlanCoverage(
    val visibleGames: Int,
    val withSelectedEstimate: Int,
    val missingSelectedEstimate: Int,
    val alreadyComplete: Int,
    val withCachedReviews: Int,
    val withKnownGenres: Int,
) {
    val isComplete: Boolean get() = missingSelectedEstimate == 0
}

/**
 * One immutable generation. Nothing here is persisted; the snapshot exists for as long as the
 * player is looking at it, and only the pick they accept becomes durable state.
 *
 * [seed] is retained so the generation is reproducible: identical inputs and an identical seed
 * produce identical picks, which is what lets a shown result hold still while it is being
 * considered. Rebuilding draws a new seed, which is what makes the control a real reroll rather
 * than the guaranteed no-op it was when membership was fully determined by the inputs.
 *
 * [canVary] records whether any tier actually had a choice. Without it, a reroll over a pool too
 * small to produce a different set would look like a control the app had ignored.
 */
data class GapPlanSnapshot(
    val request: GapPlanRequest,
    val capacity: GapPlanCapacity,
    val seed: Long,
    val picks: List<GapPlanPick>,
    val coverage: GapPlanCoverage,
    val canVary: Boolean,
) {
    fun pick(intensity: PlanIntensity): GapPlanPick? =
        picks.firstOrNull { it.intensity == intensity }

    /** The picked games, in tier order: the identity a reroll has to change to have done anything. */
    val pickedAppIds: List<Long> get() = picks.mapNotNull { it.game?.appId }
}

/** Minutes in an hour, named so the manual-budget conversion does not read as a magic number. */
internal const val MINUTES_PER_HOUR = 60L
