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
 */
enum class PlanIntensity(val percent: Int) {
    RELAXED(70),
    BALANCED(85),
    FULL(100),
}

/**
 * This intensity's budget, in whole minutes.
 *
 * Integer arithmetic rather than `floor(minutes * 0.70)` deliberately. The two agree
 * mathematically, but binary floating point does not represent 0.70 exactly, so the double form
 * can land a hair below an exact boundary and floor to one minute less — a difference that would
 * make an otherwise-identical rerun produce a different plan. Determinism is a stated property of
 * this engine, so the arithmetic that decides budgets does not go through doubles at all.
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
 * One fact that materially supported a recommendation.
 *
 * Every case carries the numbers it was derived from rather than a phrase, so the presentation
 * layer states a fact the player can check. An opaque composite score is deliberately not
 * representable here: there is no `Score` case to reach for.
 */
sealed interface GapPlanReason {
    /** Steam's own description plus the volume behind it — never a rating the app invented. */
    data class ReviewQuality(
        val description: String,
        val positive: Int,
        val total: Int,
    ) : GapPlanReason

    /** The player's own recent history, named by the genre it matched. */
    data class GenreAffinity(val genreLabel: String) : GapPlanReason

    /** Existing progress through the selected basis, as played and total minutes. */
    data class Progress(val playedMinutes: Int, val estimateMinutes: Int) : GapPlanReason

    /** How the game fits the variant it is in. */
    data class Fit(val remainingMinutes: Int) : GapPlanReason

    /** Family Sharing is always labelled — the player does not own this one. */
    data object FamilyShared : GapPlanReason

    /** A live, non-persisted concurrent-player count. Only ever added after finalization. */
    data class PlayingNow(val players: Int) : GapPlanReason
}

/**
 * One eligible game, with the derived component values that produced its reasons retained
 * alongside them.
 *
 * The components are kept because the composer needs them numerically and the explanation needs
 * them factually; deriving them twice would let the two disagree about the same game.
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
    /** Broad Store genres. Empty means unknown, which scores neutral rather than zero. */
    val genreIds: List<String>,
    /** True only when cached participation categories say so; unknown categories are not true. */
    val multiplayer: Boolean,
    val reviewQuality: Double,
    val genreAffinity: Double,
    val completionMomentum: Double,
    val reasons: List<GapPlanReason>,
) {
    /**
     * The weighted quality of this candidate on its own, before any plan-level consideration.
     *
     * Reviews carry half of it because they are the only signal about the game itself rather than
     * about the player's relationship to it. Momentum is deliberately the smallest term: existing
     * progress should help a finishable game surface, not turn every plan into backlog cleanup.
     */
    val quality: Double
        get() = REVIEW_WEIGHT * reviewQuality +
            GENRE_WEIGHT * genreAffinity +
            MOMENTUM_WEIGHT * completionMomentum

    companion object {
        const val REVIEW_WEIGHT = 0.50
        const val GENRE_WEIGHT = 0.30
        const val MOMENTUM_WEIGHT = 0.20
    }
}

/**
 * One finalized plan variant.
 *
 * [reserveMinutes] is measured against **this variant's own budget**, not the request's full
 * capacity. Both figures are presented, because a Relaxed card showing only "4,200 available, 300
 * reserve" would conceal the 1,800 minutes the 70% intensity deliberately withheld — the opposite
 * of what the intensity choice is meant to offer.
 */
data class GapPlanVariant(
    val intensity: PlanIntensity,
    val budgetMinutes: Int,
    val members: List<GapPlanCandidate>,
) {
    val plannedMinutes: Int get() = members.sumOf { it.remainingMinutes }
    val reserveMinutes: Int get() = (budgetMinutes - plannedMinutes).coerceAtLeast(0)
    val isEmpty: Boolean get() = members.isEmpty()

    companion object {
        /** A focused set the player can actually read and act on, not a backlog dump. */
        const val MAX_MEMBERS = 5
    }
}

/**
 * What the library could not contribute, stated rather than hidden.
 *
 * A game with no estimate for the selected basis is *unknown*, never zero work — treating it as
 * zero would make it look free and rank it first. Counting it here lets the result say the
 * ranking was less informed than it looks, instead of implying full coverage.
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
 * player is looking at it, and only the membership they accept becomes durable state.
 *
 * [eligiblePool] is retained with the result so a removal or replacement can be validated locally
 * against the same facts the plan was built from, without a regeneration that would also change
 * the other variants under the player.
 */
data class GapPlanSnapshot(
    val request: GapPlanRequest,
    val capacity: GapPlanCapacity,
    val variants: List<GapPlanVariant>,
    val coverage: GapPlanCoverage,
    val eligiblePool: List<GapPlanCandidate>,
) {
    fun variant(intensity: PlanIntensity): GapPlanVariant? =
        variants.firstOrNull { it.intensity == intensity }
}

/** Minutes in an hour, named so the manual-budget conversion does not read as a magic number. */
internal const val MINUTES_PER_HOUR = 60L
